package com.voxbridge.runtime;
import ai.onnxruntime.*;
import java.nio.*;
import java.util.*;

/** Parakeet v2 TDT greedy, state retained only on nonblank; all models run in caller sessions. */
public final class ParakeetEngine implements AutoCloseable {
 public static final class Recognition {
  public final int[] tokens;public final boolean tokenLimit;public final double elapsedMs;
  Recognition(List<Integer> ids,boolean limit,long start){tokens=ids.stream().mapToInt(Integer::intValue).toArray();tokenLimit=limit;elapsedMs=(System.nanoTime()-start)/1e6;}
 }
 private final OrtEnvironment env;private final OrtSession encoder,decoder;
 private final List<OnnxTensor> owned=new ArrayList<>();
 private final Map<String,OnnxTensor> encoded=new LinkedHashMap<>();
 private final List<Map<String,OnnxTensor>> decoded=new ArrayList<>();
 private final Map<String,ByteBuffer> bytes=new HashMap<>();
 private final OnnxTensor features,length,frame,label,labelLength,zeroH,zeroC;
 private final float[] acoustic=new float[1024*200],currentFrame=new float[1024],logits=new float[1030];
 private boolean closed;
 public ParakeetEngine(OrtEnvironment env,OrtSession encoder,OrtSession decoder)throws OrtException{
  this.env=env;this.encoder=encoder;this.decoder=decoder;
  try {
   features=tensor("features",new long[]{1,128,1600},OnnxJavaType.FLOAT);
   OnnxJavaType lengthType=((TensorInfo)encoder.getInputInfo().get("length").getInfo()).type;
   if(lengthType!=OnnxJavaType.INT64 && lengthType!=OnnxJavaType.INT32)throw new IllegalArgumentException("Invalid length input type");
   length=tensor("length",new long[]{1},lengthType);
   frame=tensor("frame",new long[]{1,1024,1},OnnxJavaType.FLOAT);
   label=tensor("label",new long[]{1,1},OnnxJavaType.INT32);
   labelLength=tensor("labelLength",new long[]{1},OnnxJavaType.INT32);putInt("labelLength",1);
   zeroH=tensor("zeroH",new long[]{2,1,640},OnnxJavaType.FLOAT);zeroC=tensor("zeroC",new long[]{2,1,640},OnnxJavaType.FLOAT);
   for(Map.Entry<String,NodeInfo> e:encoder.getOutputInfo().entrySet()){
    TensorInfo info=(TensorInfo)e.getValue().getInfo();encoded.put(e.getKey(),tensor("enc_"+e.getKey(),info.getShape(),info.type));
   }
   for(int i=0;i<2;i++){
    Map<String,OnnxTensor> set=new LinkedHashMap<>();decoded.add(set);
    for(Map.Entry<String,NodeInfo> e:decoder.getOutputInfo().entrySet()){
     TensorInfo info=(TensorInfo)e.getValue().getInfo();set.put(e.getKey(),tensor("dec"+i+"_"+e.getKey(),info.getShape(),info.type));
    }
   }
   out(encoded,"outputs",0);out(encoded,"encoded_lengths",1);
   for(Map<String,OnnxTensor> set:decoded){out(set,"outputs",0);out(set,"output_states_1",2);out(set,"output_states_2",3);}
  }catch(Throwable t){close();throw t;}
 }
 private static OnnxTensor out(Map<String,OnnxTensor> values,String source,int index){
  OnnxTensor value=values.get(source);if(value==null)value=values.get("output_"+index);
  if(value==null)throw new IllegalArgumentException("Missing Parakeet output "+source);return value;
 }
 private OnnxTensor tensor(String key,long[] dims,OnnxJavaType type)throws OrtException{
  long count=1;for(long n:dims){if(n<1)throw new IllegalArgumentException("Dynamic Parakeet dimension");count=Math.multiplyExact(count,n);}
  if(type!=OnnxJavaType.FLOAT&&type!=OnnxJavaType.INT32&&type!=OnnxJavaType.INT64)throw new IllegalArgumentException("Invalid tensor type");
  ByteBuffer b=ByteBuffer.allocateDirect(Math.toIntExact(count*(type==OnnxJavaType.INT64?8:4))).order(ByteOrder.nativeOrder());bytes.put(key,b);
  OnnxTensor t=OnnxTensor.createTensor(env,b,dims,type);owned.add(t);return t;
 }
 private void putInt(String name,int value){bytes.get(name).duplicate().order(ByteOrder.nativeOrder()).asIntBuffer().put(0,value);}
 private void putFloats(String name,float[] value){bytes.get(name).duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer().put(value);}
 public synchronized Recognition recognize(ParakeetMel.Features input)throws OrtException{
  if(closed)throw new IllegalStateException("ASR closed");if(input.values.length!=128*1600||input.length<2||input.length>1600)throw new IllegalArgumentException("Invalid features");
  long start=System.nanoTime();putFloats("features",input.values);
  if(length.getInfo().type==OnnxJavaType.INT64)bytes.get("length").duplicate().order(ByteOrder.nativeOrder()).asLongBuffer().put(0,input.length);
  else putInt("length",input.length);
  int frames;
  try(OrtSession.Result result=encoder.run(Map.of("audio_signal",features,"length",length),encoded)){
   OnnxTensor audio=out(encoded,"outputs",0);if(audio.getInfo().getNumElements()!=acoustic.length)throw new IllegalArgumentException("Encoder shape mismatch");audio.getFloatBuffer().get(acoustic);
   OnnxTensor len=out(encoded,"encoded_lengths",1);frames=len.getInfo().type==OnnxJavaType.INT64?Math.toIntExact(len.getLongBuffer().get(0)):len.getIntBuffer().get(0);
  }
  if(frames<1||frames>200)throw new IllegalArgumentException("Invalid encoded length");
  List<Integer> tokens=new ArrayList<>();int t=0,last=1024,stateSet=-1,iteration=0;boolean hitLimit=false;
  while(t<frames){
   int added=0,skip=0;
   do {
    if(tokens.size()>=512||iteration>=6000){hitLimit=true;break;}
    for(int i=0;i<1024;i++)currentFrame[i]=acoustic[i*200+t];putFloats("frame",currentFrame);putInt("label",last);
    // Do not overwrite the currently accepted state when a blank prediction is rejected.
    int nextSet=stateSet==0?1:0;Map<String,OnnxTensor> next=decoded.get(nextSet);
    Map<String,OnnxTensor> feed=new LinkedHashMap<>();feed.put("encoder_outputs",frame);feed.put("targets",label);feed.put("target_length",labelLength);
    feed.put("input_states_1",stateSet<0?zeroH:out(decoded.get(stateSet),"output_states_1",2));
    feed.put("input_states_2",stateSet<0?zeroC:out(decoded.get(stateSet),"output_states_2",3));
    try(OrtSession.Result result=decoder.run(feed,next)){
     out(next,"outputs",0).getFloatBuffer().get(logits);int token=argmax(logits,0,1025);skip=argmax(logits,1025,1030)-1025;
     if(token!=1024){tokens.add(token);last=token;stateSet=nextSet;}
    }
    added++;iteration++;t+=skip;
   }while(skip==0 && added<10 && t<frames);
   if(hitLimit)break;
   if(added==10)t++;
  }
  return new Recognition(tokens,hitLimit,start);
 }
 private static int argmax(float[] values,int from,int end){int best=from;for(int i=from;i<end;i++){if(!Float.isFinite(values[i]))throw new IllegalStateException("Nonfinite TDT logits");if(values[i]>values[best])best=i;}return best;}
 public synchronized void close(){if(closed)return;closed=true;for(OnnxTensor t:owned)t.close();owned.clear();try{encoder.close();}catch(OrtException ignored){}try{decoder.close();}catch(OrtException ignored){}}
}
