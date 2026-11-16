import argparse,json,subprocess
from pathlib import Path
import numpy as np
p=argparse.ArgumentParser();p.add_argument('--fixtures',type=Path,required=True);p.add_argument('--classpath',required=True);a=p.parse_args()
report={'scope':'Java app frontend vs NeMo deterministic CPU frontend; no device latency claim','cases':[]}
for r in json.loads((a.fixtures/'manifest.json').read_text()):
 id=r['id'];out=a.fixtures/(id+'.java.f32')
 subprocess.run(['java','-cp',a.classpath,'ParakeetMelCli',str(a.fixtures/'mel_128_257.f32'),str(a.fixtures/'hann_400.f32'),str(a.fixtures/(id+'.pcm.f32')),str(out)],check=True,capture_output=True)
 got=np.fromfile(out,dtype='<f4');expected=np.fromfile(a.fixtures/(id+'.mel.f32'),dtype='<f4')
 delta=np.abs(got-expected);ok=bool(np.allclose(got,expected,rtol=2e-3,atol=2e-3))
 report['cases'].append(dict(id=id,max_abs=float(delta.max()),mean_abs=float(delta.mean()),passed=ok));out.unlink()
report.update(n=len(report['cases']),passed=all(r['passed'] for r in report['cases']),max_abs=max(r['max_abs'] for r in report['cases']))
(a.fixtures/'java-parity.json').write_text(json.dumps(report,indent=2));print('JAVA_FRONTEND_PARITY',report['n'],report['passed'],report['max_abs'])
if not report['passed']:raise SystemExit(1)
