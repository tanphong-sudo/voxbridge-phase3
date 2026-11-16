"""PhoWhisper full greedy static-cache export, compatible with existing AI_HUB_QNN IO layout."""
import torch
from torch import nn

class PhoEncoder(nn.Module):
    def __init__(self,model):
        super().__init__();self.encoder=model.model.encoder
        self.keys=nn.ModuleList([layer.encoder_attn.k_proj for layer in model.model.decoder.layers])
        self.values=nn.ModuleList([layer.encoder_attn.v_proj for layer in model.model.decoder.layers])
        self.heads=model.config.decoder_attention_heads;self.dim=model.config.d_model//self.heads;self.frames=model.config.max_source_positions
    def forward(self,input_features):
        hidden=self.encoder(input_features,return_dict=False)[0];out=[]
        for k,v in zip(self.keys,self.values):
            # Existing Android ABI: head-major K [H,1,D,T], V [H,1,T,D].
            out.extend([k(hidden).view(1,self.frames,self.heads,self.dim).permute(2,0,3,1),
                        v(hidden).view(1,self.frames,self.heads,self.dim).permute(2,0,1,3)])
        return tuple(out)

class PhoDecoder(nn.Module):
    def __init__(self,model,history=199):
        super().__init__();self.model=model;self.history=history;self.heads=model.config.decoder_attention_heads;self.dim=model.config.d_model//self.heads
    def shape(self,t):return t.view(1,1,self.heads,self.dim).transpose(1,2)
    def unshape(self,t):return t.transpose(1,2).reshape(1,1,self.heads*self.dim)
    def forward(self,input_ids,attention_mask,position_ids,*cache):
        decoder=self.model.model.decoder
        hidden=decoder.embed_tokens(input_ids)+nn.functional.embedding(position_ids.to(torch.long),decoder.embed_positions.weight).unsqueeze(0)
        outputs=[]
        for i,layer in enumerate(decoder.layers):
            kprev,vprev,kcross,vcross=cache[i*4:i*4+4]
            norm=layer.self_attn_layer_norm(hidden);att=layer.self_attn
            q=self.shape(att.q_proj(norm))*att.scaling
            k=torch.cat([kprev.permute(1,0,3,2),self.shape(att.k_proj(norm))],dim=2)
            v=torch.cat([vprev.permute(1,0,2,3),self.shape(att.v_proj(norm))],dim=2)
            weights=nn.functional.softmax(torch.matmul(q,k.transpose(-1,-2))+attention_mask,dim=-1)
            hidden=hidden+att.out_proj(self.unshape(torch.matmul(weights,v)))
            norm=layer.encoder_attn_layer_norm(hidden);att=layer.encoder_attn
            q=self.shape(att.q_proj(norm))*att.scaling
            weights=nn.functional.softmax(torch.matmul(q,kcross.permute(1,0,2,3)),dim=-1)
            hidden=hidden+att.out_proj(self.unshape(torch.matmul(weights,vcross.permute(1,0,2,3))))
            hidden=hidden+layer.fc2(layer.activation_fn(layer.fc1(layer.final_layer_norm(hidden))))
            outputs.extend([k[:,:,1:,:].permute(1,0,3,2),v[:,:,1:,:].permute(1,0,2,3)])
        logits=self.model.proj_out(decoder.layer_norm(hidden))
        return (logits,)+tuple(outputs)
