"""Authenticated loopback/ADB bridge. No QNN tensor caches cross the network."""
import json,socket,struct,time
import numpy as np
from protocol import encode_tensor,decode_tensor
class Remote:
    def __init__(self,host,port,token):
        self.socket=socket.create_connection((host,port),timeout=600);self.socket.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
        self.token=token;self.run_ms=0.;self.copy_ms=0.;self.calls=0;self.pending=[];self.drops=[];self.fetched={};self.next=0
    def exact(self,n):
        result=bytearray()
        while len(result)<n:
            block=self.socket.recv(n-len(result))
            if not block:raise ConnectionError('QDC disconnected; output is resumable')
            result.extend(block)
        return result
    def request(self,op,**args):
        data=json.dumps(dict(op=op,token=self.token,**args),separators=(',',':')).encode()
        self.socket.sendall(struct.pack('>I',len(data))+data)
        size=struct.unpack('>I',self.exact(4))[0]
        if size>64*1024*1024:raise ValueError('Response too large')
        result=json.loads(self.exact(size))
        if not result.get('ok'):raise RuntimeError(result.get('error','QNN bridge failure'))
        return result
    def close(self):self.socket.close()
    def reset(self):self.request('reset');self.run_ms=0.;self.copy_ms=0.;self.calls=0;self.pending=[];self.drops=[];self.fetched={}
    def queue(self,op,**args):
        self.next+=1;handle='c'+str(self.next);self.pending.append(dict(op=op,id=handle,**args));return handle
    def put(self,array):return self.queue('put',tensor=encode_tensor(array))
    def zeros(self,shape):return self.queue('put',tensor=dict(shape=list(shape),dtype='float32'))
    def get(self,handle):
        if handle in self.fetched:return decode_tensor(self.fetched[handle])
        return decode_tensor(self.request('get',handle=handle)['tensor'])
    def gather(self,handle,indices):return self.queue('gather',handle=handle,indices=list(map(int,indices)))
    def drop(self,handles):self.drops.extend(handles)
    def mt_fast(self,input_ids,attention_mask,start,eos,early,forced_eos):
        response=self.request('mt',reuse_buffers=getattr(self,'reuse_buffers',True),input_ids=encode_tensor(input_ids),attention_mask=encode_tensor(attention_mask),start=int(start),eos=int(eos),early=bool(early),forced_eos=int(forced_eos))
        self.last_timing={k:v for k,v in response.items() if k.endswith('_ms')};self.device_decode_ms=response['device_decode_ms'];self.run_ms=response['run_ms'];self.copy_ms=response['output_copy_ms'];self.calls=response['graph_calls']
        return response['tokens'],response['token_cap']
    def asr_fast(self,mel):
        response=self.request('asr',mel=encode_tensor(mel));self.last_timing={k:v for k,v in response.items() if k.endswith('_ms')};self.device_decode_ms=response['device_decode_ms'];self.run_ms=response['run_ms'];self.copy_ms=response['output_copy_ms'];self.calls=response['graph_calls']
        return response['tokens'],'token_cap' if response['token_cap'] else 'eos'
    def run(self,session,inputs):
        response=self.request('run',session=session,pending=self.pending,drop=list(set(self.drops)),inputs={k:v if isinstance(v,str) else encode_tensor(v) for k,v in inputs.items()})
        self.pending=[];self.drops=[];self.fetched=response.get('fetched',{})
        self.run_ms+=response['run_ms'];self.copy_ms+=response['output_copy_ms'];self.calls+=1
        return response['outputs']
