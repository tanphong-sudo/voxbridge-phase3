"""Use identical HF beam search; only graph inference/cache storage move to QDC."""
import numpy as np
import torch
from torch import nn
from transformers import PreTrainedModel,GenerationMixin
from transformers.modeling_outputs import BaseModelOutput,Seq2SeqLMOutput

class RemoteEncoder(nn.Module):
    main_input_name='input_ids'
    def __init__(self,remote,owner):
        super().__init__();self.remote=remote
        # Avoid registering owner as a child module (recursive state traversal).
        object.__setattr__(self,'owner',owner)
    def forward(self,input_ids,attention_mask=None,**kwargs):
        self.remote.reset()
        result=self.remote.run('encoder',dict(input_ids=input_ids.numpy().astype(np.int32),attention_mask=attention_mask.numpy().astype(np.int32)))
        self.owner.cross={}
        for name,handle in result.items():
            if name.startswith('cross_'):
                self.owner.cross[name]=self.remote.gather(handle,[0,0])
        self.remote.drop(list(result.values()))
        self.owner.cache={}
        # HF needs shape/device metadata only; encoder hidden states are already folded into cross KV.
        return BaseModelOutput(last_hidden_state=torch.zeros((1,input_ids.shape[1],self.owner.config.d_model)))

class RemoteMt(PreTrainedModel,GenerationMixin):
    main_input_name='input_ids'
    _supports_cache_class=False
    def __init__(self,config,generation_config,remote,input_specs):
        super().__init__(config);self.anchor=nn.Parameter(torch.zeros(1),requires_grad=False)
        self.generation_config=generation_config;self.remote=remote;self.specs=input_specs
        self.layers=config.decoder_layers if config.model_type=='mbart' else config.num_decoder_layers
        self.cross={};self.cache={};self.encoder_runtime=RemoteEncoder(remote,self)
    def get_encoder(self):return self.encoder_runtime
    def prepare_inputs_for_generation(self,input_ids,past_key_values=None,attention_mask=None,encoder_outputs=None,**kwargs):
        return dict(decoder_input_ids=input_ids[:,-1:],position=input_ids.shape[-1]-1,past_key_values=past_key_values,attention_mask=attention_mask)
    def forward(self,decoder_input_ids,position=0,past_key_values=None,attention_mask=None,**kwargs):
        if position>=192 or decoder_input_ids.shape[0]!=2:raise ValueError('Expected source64/target192/beam2 contract')
        mask=np.full((2,1,1,192),-10000.,dtype=np.float32);mask[...,-(position+1):]=0
        cross=np.repeat((1-attention_mask[:1].numpy().astype(np.float32))[:,None,None,:]*-10000.,2,axis=0)
        feeds=dict(input_ids=decoder_input_ids.numpy().astype(np.int32),position_ids=np.full((2,1),position,dtype=np.int32),self_mask=mask,cross_mask=cross)
        old=list(self.cache.values());gathered=[]
        for i in range(self.layers):
            for k in ['k','v']:
                name=f'cache_{i}_self_{k}'
                if position==0:
                    handle=self.remote.zeros(self.specs[name]['shape']);gathered.append(handle)
                else:
                    handle=self.cache[f'present_{i}_{k}'];indices=past_key_values[0].tolist()
                    if indices!=[0,1]:handle=self.remote.gather(handle,indices);gathered.append(handle)
                feeds[name]=handle
                feeds[f'cache_{i}_cross_{k}']=self.cross[f'cross_{i}_{k}']
        outputs=self.remote.run('decoder',{k:v for k,v in feeds.items() if k in self.specs})
        logits_handle=outputs.pop('logits');logits=self.remote.get(logits_handle)
        self.remote.drop(old+gathered+[logits_handle])
        # Logits handle is also freed: only KV survives this step.
        self.cache=outputs
        return Seq2SeqLMOutput(logits=torch.from_numpy(logits),past_key_values=(torch.arange(2),))
    def _reorder_cache(self,past_key_values,beam_idx):return (beam_idx.cpu(),)
