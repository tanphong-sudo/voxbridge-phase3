#!/usr/bin/env python3
"""Fetch exact user-confirmed PhoWhisper-small checkpoint into the existing experiment cache."""
import argparse,json
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);a=p.parse_args()
 from huggingface_hub import snapshot_download
 import torch
 from transformers import WhisperForConditionalGeneration,AutoProcessor
 torch.set_num_threads(4)
 repo='vinai/PhoWhisper-small';revision='a86b604c346caf7148c37512eafe783a16420adb'
 folder=snapshot_download(repo_id=repo,revision=revision,cache_dir=str(a.root/'hf-cache'),allow_patterns=['*.json','*.txt','*.model','*.bin'],ignore_patterns=['optimizer*','training*'])
 model=WhisperForConditionalGeneration.from_pretrained(folder,local_files_only=True,use_safetensors=False,attn_implementation='eager').eval()
 assert model.config.d_model==768 and model.config.decoder_layers==12
 out=a.root/'phowhisper-small-selected';out.mkdir(exist_ok=True)
 (out/'source.json').write_text(json.dumps(dict(model=repo,revision=revision,snapshot=folder,parameters=sum(p.numel() for p in model.parameters()),source='user-corrected selection'),indent=2)+'\n')
 print('PHOSMALL_CHECKPOINT_READY',folder,flush=True)
if __name__=='__main__':main()
