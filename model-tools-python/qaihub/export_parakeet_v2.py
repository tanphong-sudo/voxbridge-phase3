#!/usr/bin/env python3
"""Export the pinned, unchanged Parakeet v2 components for QNN feasibility.

No quantization, no alternate ASR checkpoint, no phone latency or quality claim.
The acoustic frontend and TDT token/duration loop must still be validated separately.
"""
import argparse, hashlib, json, os, time
from pathlib import Path

PIN_SHA = 'd99e39955c9d3d0350d8fb7c75e40c64a2b2eaeb003883d7c941fd2e8747b28c'

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--checkpoint',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--frames',type=int,default=1600)
    a=p.parse_args()
    if not 64 <= a.frames <= 4096:p.error('frames must be 64..4096')
    digest=hashlib.sha256()
    with a.checkpoint.open('rb') as f:
        while block:=f.read(4*1024*1024):digest.update(block)
    if digest.hexdigest()!=PIN_SHA:raise ValueError('Not the selected Parakeet v2 checkpoint')
    a.out.mkdir(parents=True,exist_ok=False)
    import torch
    torch.set_num_threads(4);torch.set_num_interop_threads(1);torch.manual_seed(0)
    import nemo.collections.asr as na
    from omegaconf import OmegaConf
    model=na.models.ASRModel.restore_from(str(a.checkpoint),map_location='cpu').float().eval()
    model.preprocessor.featurizer.dither=0.0
    (a.out/'model-config.yaml').write_text(OmegaConf.to_yaml(model.cfg))
    info={'model_id':'nvidia/parakeet-tdt-0.6b-v2','revision':'ae9ad07059c7c739ffaf932226a8fe64ae2620b0',
          'checkpoint_sha256':PIN_SHA,'precision':'FP32 source, unchanged weights','frames':a.frames,
          'scope':'ONNX export feasibility only; not QNN, not integrated ASR WER, not phone latency','parts':{}}
    (a.out/'status.json').write_text(json.dumps(info,indent=2)+'\n')
    # Explicit fixed shapes are required by QNN. Length remains an input value for padding masks.
    feature_dim=int(model.cfg.preprocessor.features)
    decoder_example=model.decoder_joint.input_example(max_batch=1,max_dim=1)
    # NeMo's generic example uses 8192 acoustic frames. A TDT decoder step consumes ONE
    # encoded frame; exporting 8192 would add thousands of needless joint projections per token.
    decoder_example=(decoder_example[0][...,:1],*decoder_example[1:])
    examples={'encoder':(torch.randn(1,feature_dim,a.frames),torch.tensor([a.frames],dtype=torch.int64)),
              'decoder_joint':decoder_example}
    for name in ['decoder_joint','encoder']:
        module=getattr(model,name)
        dest=a.out/name;dest.mkdir()
        begin=time.perf_counter()
        # The encoder exceeds protobuf's 2 GiB in-memory checker limit. NeMo writes its
        # initializers as ONNX external-data files; runtime parity is checked separately after
        # loading the saved graph, so do not ask the exporter to materialize/check the whole blob.
        result=module.export(str(dest/'model.onnx'),input_example=examples[name],dynamic_axes={},
                             onnx_opset_version=17,check_trace=False,check_tolerance=1e-3)
        info['parts'][name]={'export_ms':(time.perf_counter()-begin)*1000,'files':result[0]}
        (a.out/'status.json').write_text(json.dumps(info,indent=2,default=str)+'\n')
        print('EXPORTED',name,flush=True)
    info['export_completed']=True
    info['requires_before_compile']=['Inspect fixed IO shapes','CPU ONNX vs source on real padded features and recurrent states',
                                     'Validate full TDT greedy loop and WER100','Only then compile matching QAIRT2.50 contexts']
    (a.out/'status.json').write_text(json.dumps(info,indent=2,default=str)+'\n')

if __name__=='__main__':main()
