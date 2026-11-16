package com.voxbridge.runtime;

import ai.onnxruntime.*;
import java.nio.*;
import java.util.*;

/** Persistent static source64/beam2/target192 runner. No tokenizer, Android UI, network or fallback. */
public final class StaticMtEngine implements AutoCloseable {
    public static final int SOURCE=64, TARGET=192;
    public static final class Decoded {
        public final int[] tokens; public final boolean hitTokenLimit;
        public final double encoderMs,decoderMs,reorderMs,deviceDecodeMs;
        Decoded(int[] tokens,double encoderMs,double decoderMs,double reorderMs,double total){this(tokens,false,encoderMs,decoderMs,reorderMs,total);}
        Decoded(int[] tokens,boolean looped,double encoderMs,double decoderMs,double reorderMs,double total){
            this.tokens=tokens;hitTokenLimit=looped || tokens.length-1>=TARGET;
            this.encoderMs=encoderMs;this.decoderMs=decoderMs;this.reorderMs=reorderMs;deviceDecodeMs=total;
        }
    }
    private final OrtEnvironment env;
    private final OrtSession encoder,decoder;
    private final Set<OnnxTensor> owned=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String,OnnxTensor> encoderOutputs=new LinkedHashMap<>();
    private final List<Map<String,OnnxTensor>> outputs=new ArrayList<>();
    private final Map<String,OnnxTensor> cross=new LinkedHashMap<>(),scratch=new LinkedHashMap<>(),zeros=new LinkedHashMap<>();
    private final Map<String,ByteBuffer> buffers=new HashMap<>();
    private final OnnxTensor sourceIds,sourceMask,decoderIds,selfMask,crossMask,position;
    private final float[] logits,selfValues=new float[2*TARGET],crossValues=new float[2*SOURCE];
    private boolean closed;
    public StaticMtEngine(OrtEnvironment env,OrtSession encoder,OrtSession decoder) throws OrtException {
        this.env=env;this.encoder=encoder;this.decoder=decoder;
        try {
            sourceIds=allocate("source_ids",new long[]{1,SOURCE},OnnxJavaType.INT32);
            sourceMask=allocate("source_mask",new long[]{1,SOURCE},OnnxJavaType.INT32);
            decoderIds=allocate("decoder_ids",new long[]{2,1},OnnxJavaType.INT32);
            selfMask=allocate("self_mask",new long[]{2,1,1,TARGET},OnnxJavaType.FLOAT);
            crossMask=allocate("cross_mask",new long[]{2,1,1,SOURCE},OnnxJavaType.FLOAT);
            Map<String,NodeInfo> inputs=decoder.getInputInfo();
            position=inputs.containsKey("position_ids")?allocate("position_ids",new long[]{2,1},OnnxJavaType.INT32):null;
            for(Map.Entry<String,NodeInfo> e:encoder.getOutputInfo().entrySet()){
                TensorInfo info=(TensorInfo)e.getValue().getInfo();encoderOutputs.put(e.getKey(),allocate("enc_"+e.getKey(),info.getShape(),info.type));
                if(e.getKey().startsWith("cross_")){
                    String[] name=e.getKey().split("_");String key="cache_"+name[1]+"_cross_"+name[2];
                    long[] shape=info.getShape().clone();shape[0]=2;cross.put(key,allocate("cross_"+key,shape,info.type));
                }
            }
            for(int i=0;i<2;i++){
                Map<String,OnnxTensor> pinned=new LinkedHashMap<>();outputs.add(pinned);
                for(Map.Entry<String,NodeInfo> e:decoder.getOutputInfo().entrySet()){
                    TensorInfo info=(TensorInfo)e.getValue().getInfo();pinned.put(e.getKey(),allocate("out"+i+"_"+e.getKey(),info.getShape(),info.type));
                }
            }
            Map<String,OnnxTensor> sharedZeros=new HashMap<>();
            for(Map.Entry<String,NodeInfo> e:inputs.entrySet())if(e.getKey().contains("_self_")){
                TensorInfo info=(TensorInfo)e.getValue().getInfo();String shape=Arrays.toString(info.getShape());
                OnnxTensor zero=sharedZeros.get(shape);
                if(zero==null){zero=allocate("zero_"+shape,info.getShape(),info.type);sharedZeros.put(shape,zero);}
                zeros.put(e.getKey(),zero);
                scratch.put(e.getKey(),allocate("scratch_"+e.getKey(),info.getShape(),info.type));
            }
            logits=new float[Math.toIntExact(outputs.get(0).get("logits").getInfo().getNumElements())];
            if(logits.length%2!=0 || cross.isEmpty() || zeros.isEmpty())throw new IllegalArgumentException("Incompatible static MT export");
        }catch(Throwable failure){close();throw failure;}
    }
    private OnnxTensor allocate(String key,long[] shape,OnnxJavaType type)throws OrtException{
        long count=1;for(long n:shape){if(n<1)throw new IllegalArgumentException("Dynamic model shape: "+key);count=Math.multiplyExact(count,n);}
        if(type!=OnnxJavaType.FLOAT && type!=OnnxJavaType.INT32)throw new IllegalArgumentException("Unsupported dtype "+type);
        ByteBuffer data=ByteBuffer.allocateDirect(Math.toIntExact(count*4)).order(ByteOrder.nativeOrder());
        OnnxTensor t=OnnxTensor.createTensor(env,data,shape,type);owned.add(t);buffers.put(key,data);return t;
    }
    private static ByteBuffer data(OnnxTensor t){
        Buffer ref=t.getBufferRef().orElse(null);return ref instanceof ByteBuffer?(ByteBuffer)ref:t.getByteBuffer();
    }
    private void ints(String name,int[] values){ByteBuffer b=buffers.get(name).duplicate().order(ByteOrder.nativeOrder());b.clear();b.asIntBuffer().put(values);}
    private void floats(String name,float[] values){ByteBuffer b=buffers.get(name).duplicate().order(ByteOrder.nativeOrder());b.clear();b.asFloatBuffer().put(values);}
    public synchronized Decoded decode(int[] ids,int[] attention,int start,int eos,boolean early,int forcedEos)throws OrtException{
        if(closed)throw new IllegalStateException("MT runtime closed");
        if(ids.length!=SOURCE || attention.length!=SOURCE)throw new IllegalArgumentException("Expected exactly64 source tokens");
        for(int m:attention)if(m!=0&&m!=1)throw new IllegalArgumentException("Invalid source attention mask");
        long began=System.nanoTime(),step=began,encoderNs,decoderNs=0,reorderNs=0;
        ints("source_ids",ids);ints("source_mask",attention);
        try(OrtSession.Result result=encoder.run(Map.of("input_ids",sourceIds,"attention_mask",sourceMask),encoderOutputs)){
            encoderNs=System.nanoTime()-step;
            for(Map.Entry<String,OnnxTensor> e:cross.entrySet()){
                String[] parts=e.getKey().split("_");OnnxTensor src=encoderOutputs.get("cross_"+parts[1]+"_"+parts[3]);
                StaticMtTensorOps.gatherBytes(data(src),buffers.get("cross_"+e.getKey()),1,new int[]{0,0});
            }
        }
        for(int i=0;i<crossValues.length;i++)crossValues[i]=attention[i%SOURCE]==1?0f:-10000f;
        floats("cross_mask",crossValues);
        StaticMtBeam2 beam=new StaticMtBeam2(start,eos,TARGET,early,forcedEos);
        Map<String,OnnxTensor> cache=new LinkedHashMap<>(zeros);
        Set<String> accepted=decoder.getInputNames();
        for(int pos=0;pos<TARGET;pos++){
            Map<String,OnnxTensor> feed=new LinkedHashMap<>(cross);step=System.nanoTime();int[] parents=beam.parents();
            for(Map.Entry<String,OnnxTensor> entry:cache.entrySet()){
                OnnxTensor tensor=entry.getValue();
                if(pos>0 && (parents[0]!=0||parents[1]!=1)){
                    StaticMtTensorOps.gatherBytes(data(tensor),buffers.get("scratch_"+entry.getKey()),2,parents);tensor=scratch.get(entry.getKey());
                }
                feed.put(entry.getKey(),tensor);
            }
            reorderNs+=System.nanoTime()-step;
            Arrays.fill(selfValues,-10000f);for(int b=0;b<2;b++)Arrays.fill(selfValues,b*TARGET+TARGET-1-pos,(b+1)*TARGET,0f);
            ints("decoder_ids",beam.inputTokens());floats("self_mask",selfValues);
            feed.put("input_ids",decoderIds);feed.put("self_mask",selfMask);feed.put("cross_mask",crossMask);
            if(position!=null){ints("position_ids",new int[]{pos,pos});feed.put("position_ids",position);}
            feed.keySet().retainAll(accepted);Map<String,OnnxTensor> pinned=outputs.get(pos%2);step=System.nanoTime();
            try(OrtSession.Result result=decoder.run(feed,pinned)){
                decoderNs+=System.nanoTime()-step;
                pinned.get("logits").getFloatBuffer().get(logits);beam.advance(logits,logits.length/2);
                cache.clear();for(String name:zeros.keySet()){
                    String[] parts=name.split("_");cache.put(name,pinned.get("present_"+parts[1]+"_"+parts[3]));
                }
            }
            if(beam.done())break;
            // A loop that has repeated 3x in both beams can only end at the 192-token cap, which the
            // decision layer rejects anyway. Stop now: same AskRepeat outcome, ~2 s sooner.
            if(beam.looping())return new Decoded(beam.runningBest(),true,encoderNs/1e6,decoderNs/1e6,reorderNs/1e6,(System.nanoTime()-began)/1e6);
        }
        return new Decoded(beam.best(),encoderNs/1e6,decoderNs/1e6,reorderNs/1e6,(System.nanoTime()-began)/1e6);
    }
    @Override public synchronized void close(){
        if(closed)return;closed=true;for(OnnxTensor t:owned)t.close();owned.clear();buffers.clear();
        try{encoder.close();}catch(OrtException ignored){}try{decoder.close();}catch(OrtException ignored){}
    }
}
