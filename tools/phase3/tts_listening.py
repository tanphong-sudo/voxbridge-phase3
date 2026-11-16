#!/usr/bin/env python3
"""Export paired full/chunked listening WAVs from measured production sentence plans."""
import argparse
import html
import json
import wave
from pathlib import Path


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--plan',type=Path,required=True)
    p.add_argument('--models',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    a=p.parse_args()
    import sherpa_onnx
    import numpy as np
    rows=[json.loads(x) for x in a.plan.read_text().splitlines()]
    selected=[]
    # Three longest multi-chunk outputs per model/language. Deliberate review set, not accuracy sample.
    for model in ['envit5','vinai']:
        for lang in ['en','vi']:
            selected+=sorted([r for r in rows if r['model']==model and r['target_lang']==lang and len(r['chunks'])>1],
                             key=lambda r:len(r['tts_text']),reverse=True)[:3]
    a.out.mkdir(parents=True,exist_ok=True)
    for lang,bundle in [('en','vits-piper-en_US-lessac-medium'),('vi','vits-piper-vi_VN-vais1000-medium')]:
        d=a.models/'text_to_speech/piper'/bundle
        graph,=d.glob('*.onnx')
        tts=sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(graph),tokens=str(d/'tokens.txt'),data_dir=str(d/'espeak-ng-data'),
            noise_scale=0.,noise_scale_w=0.,length_scale=1.),num_threads=2,provider='cpu')))
        for r in [r for r in selected if r['target_lang']==lang]:
            r['files']={}
            for mode,texts in [('full',[r['spoken_full']]),('chunks',r['chunks'])]:
                parts=[tts.generate(t,sid=0,speed=1.).samples for t in texts]
                samples=np.concatenate(parts)
                pcm=(np.clip(samples,-1,1)*32767).astype('<i2')
                name=f"{r['model']}-{r['id']}-{mode}.wav"
                with wave.open(str(a.out/name),'wb') as w:
                    w.setnchannels(1);w.setsampwidth(2);w.setframerate(22050);w.writeframes(pcm.tobytes())
                r['files'][mode]=name
        del tts
    page=['<!doctype html><meta charset="utf-8"><title>TTS đối chiếu</title>',
          '<style>body{max-width:1000px;margin:32px auto;font:17px system-ui}article{padding:20px;border-bottom:1px solid #ddd}audio{width:100%}</style>',
          '<h1>TTS: nguyên đoạn / từng câu</h1><p>12 bản dịch dài để nghe kiểm tra; không phải điểm chất lượng. Audio từng câu được nối đúng thứ tự, chưa mô phỏng khoảng hở AudioTrack trên điện thoại. Kiểm số máy, phủ định, đơn vị, chỗ ngắt và độ rõ.</p>']
    for r in selected:
        page.append(f"<article><h2>{html.escape(r['model']+' '+r['id'])}</h2><p>{html.escape(r['tts_text'])}</p>")
        for mode,label in [('full','Nguyên đoạn'),('chunks','Từng câu')]:
            page.append(f'<p>{label}</p><audio controls preload="none" src="{r["files"][mode]}"></audio>')
        page.append('<p>Các câu: '+html.escape(' | '.join(r['chunks']))+'</p></article>')
    (a.out/'index.html').write_text('\n'.join(page))
    (a.out/'cases.json').write_text(json.dumps(selected,ensure_ascii=False,indent=2)+'\n')
    print('Exported',len(selected),'pairs')

if __name__=='__main__':main()
