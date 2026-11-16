"""Validation-only bridge for comparing the benchmark decoder against source ONNX."""
import numpy as np
class CpuBridge:
    def __init__(self,paths):
        import onnxruntime as ort
        opts=ort.SessionOptions();opts.intra_op_num_threads=3;opts.inter_op_num_threads=1
        self.sessions={k:ort.InferenceSession(str(v),sess_options=opts,providers=['CPUExecutionProvider']) for k,v in paths.items()}
        self.specs={k:dict(inputs={i.name:dict(shape=i.shape,dtype=i.type) for i in s.get_inputs()},outputs={i.name:{} for i in s.get_outputs()}) for k,s in self.sessions.items()}
        self.next=0;self.reset()
    def reset(self):self.data={};self.run_ms=0.;self.copy_ms=0.;self.calls=0
    def put(self,array):self.next+=1;k=str(self.next);self.data[k]=np.array(array);return k
    def zeros(self,shape):return self.put(np.zeros(shape,dtype=np.float32))
    def gather(self,handle,indices):return self.put(self.data[handle][indices])
    def get(self,handle):return self.data[handle]
    def drop(self,handles):
        for h in handles:self.data.pop(h,None)
    def run(self,session,inputs):
        import time
        s=self.sessions[session];feeds={k:self.data[v] if isinstance(v,str) else v for k,v in inputs.items()}
        started=time.perf_counter();out=s.run(None,feeds);self.run_ms+=(time.perf_counter()-started)*1000;self.calls+=1
        return {m.name:self.put(v) for m,v in zip(s.get_outputs(),out)}
    def close(self):self.sessions.clear();self.reset()
