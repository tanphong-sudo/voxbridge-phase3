"""Static-cache seq2seq graphs with complete single-token autoregressive decoding.

Source64, target192, beam batch2. Past history is right-aligned; position remains
an explicit input for mBART. T5 relative positions are invariant under this shift.
"""
import torch
from torch import nn

SOURCE_LENGTH=64
TARGET_LENGTH=192
BEAMS=2

class StaticEncoder(nn.Module):
    def __init__(self, model, kind):
        super().__init__();self.encoder=model.get_encoder();self.kind=kind
        layers=model.model.decoder.layers if kind=='vinai' else model.decoder.block
        self.keys=nn.ModuleList([layer.encoder_attn.k_proj if kind=='vinai' else layer.layer[1].EncDecAttention.k for layer in layers])
        self.values=nn.ModuleList([layer.encoder_attn.v_proj if kind=='vinai' else layer.layer[1].EncDecAttention.v for layer in layers])
        self.heads=model.config.decoder_attention_heads if kind=='vinai' else model.config.num_heads
        self.head_dim=model.config.d_model//self.heads if kind=='vinai' else model.config.d_kv
    def forward(self,input_ids,attention_mask):
        h=self.encoder(input_ids=input_ids,attention_mask=attention_mask,return_dict=False)[0]
        result=[h]
        for k,v in zip(self.keys,self.values):
            result.extend([k(h).view(1,SOURCE_LENGTH,self.heads,self.head_dim).transpose(1,2),
                           v(h).view(1,SOURCE_LENGTH,self.heads,self.head_dim).transpose(1,2)])
        return tuple(result)

class StaticDecoder(nn.Module):
    def __init__(self,model,kind):
        super().__init__();self.kind=kind;self.model=model
        self.heads=model.config.decoder_attention_heads if kind=='vinai' else model.config.num_heads
        self.head_dim=model.config.d_model//self.heads if kind=='vinai' else model.config.d_kv
        if kind=='vinai':self.register_buffer('cross_placeholder',torch.zeros(BEAMS,SOURCE_LENGTH,model.config.d_model))
        else:
            att=model.decoder.block[0].layer[0].SelfAttention
            bias=att.compute_bias(1,TARGET_LENGTH,cache_position=torch.tensor([TARGET_LENGTH-1])).detach()
            self.register_buffer('relative_bias',bias)
    def shape(self,t):return t.view(BEAMS,1,self.heads,self.head_dim).transpose(1,2)
    def unshape(self,t):return t.transpose(1,2).reshape(BEAMS,1,self.heads*self.head_dim)
    def forward(self,input_ids,position_ids,self_mask,cross_mask,*cache):
        outputs=[]
        if self.kind=='vinai':
            decoder=self.model.model.decoder
            h=decoder.embed_tokens(input_ids)+nn.functional.embedding(position_ids.to(torch.long)+decoder.embed_positions.offset,decoder.embed_positions.weight)
            h=decoder.layernorm_embedding(h)
            for i,layer in enumerate(decoder.layers):
                past=tuple(cache[i*4:i*4+4])
                value=layer(h,attention_mask=self_mask,encoder_hidden_states=self.cross_placeholder,
                            encoder_attention_mask=cross_mask,past_key_value=past,use_cache=True,output_attentions=False)
                h=value[0];present=value[1]
                outputs.extend([present[0][:,:,1:,:],present[1][:,:,1:,:]])
            h=decoder.layer_norm(h)
            logits=self.model.lm_head(h)+self.model.final_logits_bias
        else:
            decoder=self.model.decoder;h=decoder.embed_tokens(input_ids)
            for i,block in enumerate(decoder.block):
                kpast,vpast,kcross,vcross=cache[i*4:i*4+4]
                att=block.layer[0].SelfAttention;norm=block.layer[0].layer_norm(h)
                q=self.shape(att.q(norm));k=torch.cat([kpast,self.shape(att.k(norm))],dim=2);v=torch.cat([vpast,self.shape(att.v(norm))],dim=2)
                weights=nn.functional.softmax(torch.matmul(q,k.transpose(-1,-2))+self.relative_bias+self_mask,dim=-1)
                h=h+att.o(self.unshape(torch.matmul(weights,v)))
                cross=block.layer[1].EncDecAttention;norm=block.layer[1].layer_norm(h);q=self.shape(cross.q(norm))
                weights=nn.functional.softmax(torch.matmul(q,kcross.transpose(-1,-2))+cross_mask,dim=-1)
                h=h+cross.o(self.unshape(torch.matmul(weights,vcross)))
                h=block.layer[2](h)
                outputs.extend([k[:,:,1:,:],v[:,:,1:,:]])
            h=decoder.final_layer_norm(h)
            if self.model.config.tie_word_embeddings:h=h*(self.model.model_dim**-0.5)
            logits=self.model.lm_head(h)
        return (logits,)+tuple(outputs)

def empty_cache(model,kind):
    layers=model.config.decoder_layers if kind=='vinai' else model.config.num_decoder_layers
    heads=model.config.decoder_attention_heads if kind=='vinai' else model.config.num_heads
    dim=model.config.d_model//heads if kind=='vinai' else model.config.d_kv
    return [(torch.zeros(BEAMS,heads,TARGET_LENGTH-1,dim),torch.zeros(BEAMS,heads,TARGET_LENGTH-1,dim)) for _ in range(layers)]
