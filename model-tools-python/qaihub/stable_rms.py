"""Algebraically identical T5 RMSNorm with bounded square operands for FP16 backends."""
import torch
from torch import nn
class StableRmsNorm(nn.Module):
 def __init__(self,original):super().__init__();self.weight=original.weight;self.eps=original.variance_epsilon
 def forward(self,x):
  scaled=x/32.0
  return self.weight*scaled*torch.rsqrt(scaled.pow(2).mean(-1,keepdim=True)+self.eps/1024.0)
def replace_norms(module):
 from transformers.models.t5.modeling_t5 import T5LayerNorm
 count=0
 for name,child in list(module.named_children()):
  if isinstance(child,T5LayerNorm):setattr(module,name,StableRmsNorm(child));count+=1
  else:count+=replace_norms(child)
 return count
