import unittest
import numpy as np
from remote import Remote
from protocol import encode_tensor

class InMemoryRemote(Remote):
    def __init__(self):
        self.pending=[];self.drops=[];self.fetched={};self.next=0;self.run_ms=0;self.copy_ms=0;self.calls=0;self.frames=[]
    def request(self,op,**args):
        self.frames.append((op,args))
        if op=='run':return dict(outputs={'logits':'t99'},fetched={'t99':encode_tensor(np.array([[[1.,2.]]],dtype=np.float32))},run_ms=2,output_copy_ms=1)
        if op=='reset':return {}
        raise AssertionError('Unexpected network request: '+op)

class RemoteBatchTest(unittest.TestCase):
    def test_cache_create_gather_drop_and_logits_use_one_frame_per_token(self):
        remote=InMemoryRemote();zero=remote.zeros([2,1,3,4]);gather=remote.gather('t1',[1,1]);remote.drop(['t0'])
        self.assertEqual(remote.frames,[])
        result=remote.run('decoder',dict(cache=zero,other=gather,input_ids=np.array([[1],[2]],dtype=np.int32)))
        np.testing.assert_array_equal(remote.get(result['logits']),np.array([[[1.,2.]]],dtype=np.float32))
        self.assertEqual(len(remote.frames),1)
        sent=remote.frames[0][1];self.assertEqual([x['op'] for x in sent['pending']],['put','gather']);self.assertEqual(sent['drop'],['t0'])
        self.assertEqual(remote.pending,[]);self.assertEqual(remote.drops,[])
        self.assertEqual(remote.run_ms,2)
    def test_reset_discards_pending_handles_from_previous_sentence(self):
        remote=InMemoryRemote();remote.zeros([2,1]);remote.drop(['stale']);remote.reset()
        self.assertEqual(remote.pending,[]);self.assertEqual(remote.drops,[]);self.assertEqual(remote.fetched,{})

if __name__=='__main__':unittest.main()

class NativeFastPathTest(unittest.TestCase):
    def test_mt_uses_verified_buffer_reuse_without_an_extra_flag(self):
        class Stub(Remote):
            def __init__(self): self.sent = None
            def request(self, op, **args):
                self.sent = args
                return dict(tokens=[0,1],token_cap=False,device_decode_ms=1.,run_ms=.5,output_copy_ms=0.,graph_calls=2)
        remote=Stub()
        remote.mt_fast(np.zeros((1,64),dtype=np.int32),np.ones((1,64),dtype=np.int32),0,1,False,-1)
        self.assertTrue(remote.sent['reuse_buffers'])
