"""Full autoregressive beam search via Transformers using the exact static ONNX graphs."""
import numpy as np
import torch
from torch import nn
from transformers import PreTrainedModel,GenerationMixin,GenerationConfig
from transformers.modeling_outputs import BaseModelOutput,Seq2SeqLMOutput
from static_mt import SOURCE_LENGTH,TARGET_LENGTH,BEAMS
from graph_validation import align_outputs

class EncoderRuntime(nn.Module):
    main_input_name='input_ids'
    def __init__(self,session,layers):super().__init__();self.session=session;self.layers=layers
    def forward(self,input_ids,attention_mask=None,**kwargs):
        output=self.session.run(None,{'input_ids':input_ids.numpy().astype(np.int32),'attention_mask':attention_mask.numpy().astype(np.int32)})
        names=['encoder_hidden_states']+[f'cross_{i}_{k}' for i in range(self.layers) for k in ['k','v']]
        output=align_outputs(names,{m.name:v for m,v in zip(self.session.get_outputs(),output)})
        result=BaseModelOutput(last_hidden_state=torch.from_numpy(output[0]));result['cross_cache']=tuple(output[1:]);return result

class OrtStaticMt(PreTrainedModel,GenerationMixin):
    main_input_name='input_ids'
    _supports_cache_class=False
    def __init__(self,config,encoder,decoder,generation_config):
        super().__init__(config);self.anchor=nn.Parameter(torch.zeros(1),requires_grad=False)
        self.encoder_runtime=EncoderRuntime(encoder,config.decoder_layers if config.model_type=='mbart' else config.num_decoder_layers);self.decoder_session=decoder;self.generation_config=generation_config
        self.layers=config.decoder_layers if config.model_type=='mbart' else config.num_decoder_layers
        self.heads=config.decoder_attention_heads if config.model_type=='mbart' else config.num_heads
        self.dim=config.d_model//self.heads if config.model_type=='mbart' else config.d_kv
        self.decoder_input_names={x.name for x in decoder.get_inputs()}
    def get_encoder(self):return self.encoder_runtime
    def prepare_inputs_for_generation(self,input_ids,past_key_values=None,attention_mask=None,encoder_outputs=None,**kwargs):
        return dict(decoder_input_ids=input_ids[:,-1:],position=input_ids.shape[-1]-1,past_key_values=past_key_values,
                    attention_mask=attention_mask,encoder_outputs=encoder_outputs)
    def forward(self,decoder_input_ids,position=0,past_key_values=None,attention_mask=None,encoder_outputs=None,**kwargs):
        if position>=TARGET_LENGTH:raise ValueError('Target token limit exceeded')
        n=decoder_input_ids.shape[0]
        if n not in [1,BEAMS]:raise ValueError('Static decoder supports greedy or beam2 only')
        ids=decoder_input_ids.numpy().astype(np.int32)
        if n==1:ids=np.repeat(ids,BEAMS,axis=0)
        mask=np.full((BEAMS,1,1,TARGET_LENGTH),-10000.,dtype=np.float32);mask[...,-(position+1):]=0.
        cross=(1-attention_mask[:1].numpy().astype(np.float32))[:,None,None,:]*-10000.;cross=np.repeat(cross,BEAMS,axis=0)
        feeds=dict(input_ids=ids,position_ids=np.full((BEAMS,1),position,dtype=np.int32),self_mask=mask,cross_mask=cross)
        for i in range(self.layers):
            for j,kind in enumerate(['self_k','self_v']):
                value=past_key_values[i][j].numpy() if past_key_values is not None else np.zeros((BEAMS,self.heads,TARGET_LENGTH-1,self.dim),dtype=np.float32)
                if value.shape[0]==1:value=np.repeat(value,BEAMS,axis=0)
                feeds[f'cache_{i}_{kind}']=value
            for j,kind in enumerate(['cross_k','cross_v']):feeds[f'cache_{i}_{kind}']=np.repeat(encoder_outputs['cross_cache'][i*2+j][:1],BEAMS,axis=0)
        values=self.decoder_session.run(None,{k:v for k,v in feeds.items() if k in self.decoder_input_names})
        names=['logits']+[f'present_{i}_{k}' for i in range(self.layers) for k in ['k','v']]
        values=align_outputs(names,{m.name:v for m,v in zip(self.decoder_session.get_outputs(),values)})
        cache=tuple((torch.from_numpy(values[1+i*2][:n]),torch.from_numpy(values[2+i*2][:n])) for i in range(self.layers))
        return Seq2SeqLMOutput(logits=torch.from_numpy(values[0][:n]),past_key_values=cache)
    def _reorder_cache(self,past_key_values,beam_idx):return tuple(tuple(t.index_select(0,beam_idx) for t in layer) for layer in past_key_values)
