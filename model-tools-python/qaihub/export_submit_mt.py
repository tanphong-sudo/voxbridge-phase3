#!/usr/bin/env python3
"""Export and immediately submit each pinned MT graph. No credentials written to disk.

Compile feasibility candidates only: fixed source length128, batch1, cached decoder
past length8. These graph artifacts are not a full autoregressive Android runtime.
Pass ClientConfig JSON via stdin; never echo it. Each submission is recorded before
continuing. No automatic resubmission after an ambiguous network failure.
"""
import argparse
import gc
import hashlib
import json
import sys
import time
from pathlib import Path


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cache',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--export-only',action='store_true')
    args=p.parse_args()
    client=None
    if not args.export_only:
        from qai_hub.client import ClientConfig
        import qai_hub as hub
        client=hub.Client(ClientConfig(**json.loads(sys.stdin.readline())))
    import torch
    import numpy as np
    import onnx
    import onnxruntime as ort
    from transformers import AutoModelForSeq2SeqLM,AutoTokenizer
    torch.set_num_threads(4)
    torch.set_num_interop_threads(1)
    args.out.mkdir(parents=True,exist_ok=True)
    ledger_path=args.out/'jobs.json'
    ledger=json.loads(ledger_path.read_text()) if ledger_path.exists() else {}
    candidates=[
        ('envit5','VietAI/envit5-translation','840bc88104d5a4277af740eaedb024df8c3093e7','vi'),
        ('vinai-vi2en','vinai/vinai-translate-vi2en-v2','ae7baa85da07dbe8e23ac26a9f5ef560c17e2138','vi'),
        ('vinai-en2vi','vinai/vinai-translate-en2vi-v2','82f8c91bd22e82085186b45a8a76373f5a79f667','en'),
    ]
    def save():
        temp=ledger_path.with_suffix('.tmp');temp.write_text(json.dumps(ledger,indent=2)+'\n');temp.replace(ledger_path)
    for name,repo,rev,src in candidates:
        folder=args.cache/('models--'+repo.replace('/','--'))/'snapshots'/rev
        model=AutoModelForSeq2SeqLM.from_pretrained(str(folder),local_files_only=True,use_safetensors=False,
                                                   attn_implementation='eager').eval()
        tok=AutoTokenizer.from_pretrained(str(folder),local_files_only=True,
            **({'src_lang':{'vi':'vi_VN','en':'en_XX'}[src]} if name!='envit5' else {}))
        encoded=tok(('vi: Dừng máy và kiểm tra van.' if name=='envit5' else
                     'Dừng máy và kiểm tra van.' if src=='vi' else 'Stop the machine and check the valve.'),
                     return_tensors='pt',padding='max_length',max_length=128,truncation=True)
        ids=encoded['input_ids'].to(torch.int32);mask=encoded['attention_mask'].to(torch.int32)
        start=model.config.decoder_start_token_id if name=='envit5' else tok.lang_code_to_id['en_XX' if src=='vi' else 'vi_VN']
        dec=torch.tensor([[start]],dtype=torch.int32)
        with torch.inference_mode():
            hidden=model.get_encoder()(input_ids=ids,attention_mask=mask,return_dict=False)[0]
            history=model(encoder_outputs=(hidden,),attention_mask=mask,
                          decoder_input_ids=dec.repeat(1,8),use_cache=True,return_dict=True).past_key_values
        class Encoder(torch.nn.Module):
            def __init__(self):super().__init__();self.encoder=model.get_encoder()
            def forward(self,input_ids,attention_mask):
                return self.encoder(input_ids=input_ids,attention_mask=attention_mask,return_dict=False)[0]
        class Decoder(torch.nn.Module):
            def __init__(self,cached):super().__init__();self.m=model;self.cached=cached
            def forward(self,decoder_input_ids,encoder_hidden_states,encoder_attention_mask,*past):
                kv=tuple(tuple(past[i:i+4]) for i in range(0,len(past),4)) if self.cached else None
                result=self.m(encoder_outputs=(encoder_hidden_states,),attention_mask=encoder_attention_mask,
                              decoder_input_ids=decoder_input_ids,past_key_values=kv,use_cache=True,return_dict=True)
                # Cross-attention cache is returned only at bootstrap; subsequent steps retain it.
                cache=tuple(t for layer in result.past_key_values for t in (layer[:2] if self.cached else layer))
                return (result.logits,)+cache
        n_layers=len(history)
        for graph in ['encoder','decoder-init','decoder-step']:
            key=name+'/'+graph
            if key in ledger and ledger[key].get('status') == 'submitting':
                raise RuntimeError('Ambiguous earlier submission; inspect AI Hub before retry: '+key)
            if key in ledger and ledger[key].get('job_id'):
                print('REUSE',key,ledger[key]['job_id'],flush=True);continue
            if graph=='encoder':
                wrapper=Encoder().eval();inputs=(ids,mask);names=['input_ids','attention_mask'];outputs=['encoder_hidden_states']
            else:
                cached=graph=='decoder-step';wrapper=Decoder(cached).eval()
                inputs=(dec,hidden,mask)+(tuple(t for layer in history for t in layer) if cached else ())
                names=['decoder_input_ids','encoder_hidden_states','encoder_attention_mask']
                if cached:names += [f'past_{i}_{kind}' for i in range(n_layers) for kind in ['self_k','self_v','cross_k','cross_v']]
                outputs=['logits']+[f'present_{i}_{kind}' for i in range(n_layers)
                                     for kind in (['self_k','self_v'] if cached else ['self_k','self_v','cross_k','cross_v'])]
            dest=args.out/name/graph;dest.mkdir(parents=True,exist_ok=True);path=dest/'model.onnx'
            print('EXPORT',key,flush=True)
            with torch.inference_mode():
                reference=wrapper(*inputs)
                torch.onnx.export(wrapper,inputs,str(path),input_names=names,output_names=outputs,
                                  opset_version=17,do_constant_folding=True,dynamo=False)
            # Canonical external-data package accepted by AI Hub (one graph + one .data file).
            exported=onnx.load(str(path),load_external_data=True)
            for old in dest.glob('*.data'):old.unlink()
            onnx.save_model(exported,str(path),save_as_external_data=True,all_tensors_to_one_file=True,
                            location='model.data',size_threshold=0,convert_attribute=False)
            del exported;gc.collect()
            onnx.checker.check_model(str(path))
            session_opts=ort.SessionOptions();session_opts.intra_op_num_threads=2;session_opts.inter_op_num_threads=1
            session=ort.InferenceSession(str(path),sess_options=session_opts,providers=['CPUExecutionProvider'])
            feeds={n:t.detach().cpu().numpy() for n,t in zip(names,inputs)}
            actual_names=[x.name for x in session.get_inputs()];feeds={n:feeds[n] for n in actual_names}
            actual=session.run(None,feeds);expected=reference if isinstance(reference,tuple) else (reference,)
            errors=[]
            for a,b in zip(actual,expected):
                b=b.detach().cpu().numpy();np.testing.assert_allclose(a,b,rtol=5e-3,atol=5e-3)
                errors.append(float(np.max(np.abs(a-b))))
            assert len(actual)==len(expected)
            if graph!='encoder':assert np.argmax(actual[0][0,-1])==int(expected[0][0,-1].argmax())
            specs={n:(tuple(int(d) for d in t.shape),str(t.dtype).removeprefix('torch.')) for n,t in zip(names,inputs) if n in actual_names}
            row=dict(repo=repo,revision=rev,graph=graph,source_length=128,decoder_past_length=8 if graph=='decoder-step' else 0,
                     batch=1,parity_max_abs=errors,input_specs=specs,onnx_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                     bytes=sum(f.stat().st_size for f in dest.iterdir()),status='validated_export',
                     precision='unquantized compile feasibility; not calibrated',android_ready=False)
            ledger[key]=row;save()
            del session,actual,reference;gc.collect()
            if client:
                # Record intent before the request; ambiguous failures require inspection, never blind retry.
                row['status']='submitting';save()
                job=client.submit_compile_job(str(dest),device=hub.Device('Samsung Galaxy S24 Ultra'),
                    name='1Voice '+key+' 20261113 feasibility',options='--target_runtime qnn_dlc',retry=False)
                row.update(job_id=job.job_id,url=job.url,status='submitted');save()
                print('SUBMITTED',key,job.job_id,job.url,flush=True)
            del wrapper,inputs;gc.collect()
        del model,tok,hidden,history;gc.collect()
    print('ALL_EXPORTED_AND_SUBMITTED',flush=True)

if __name__=='__main__':main()
