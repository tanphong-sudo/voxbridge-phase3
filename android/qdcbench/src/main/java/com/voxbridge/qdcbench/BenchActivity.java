package com.voxbridge.qdcbench;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.PowerManager;
import android.view.WindowManager;
import android.widget.TextView;
import android.util.Base64;
import ai.onnxruntime.*;
import com.k2fsa.sherpa.onnx.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;

/** Loopback-only diagnostic bridge. Models/KV caches execute and remain on the phone. */
public final class BenchActivity extends Activity {
 private QnnBridge bridge;
 private PowerManager.WakeLock wake;
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  TextView status=new TextView(this);status.setTextSize(20);status.setPadding(24,24,24,24);setContentView(status);
  String token=getIntent().getStringExtra("token");
  if(token==null || token.length()<24){status.setText("Launch with the QDC benchmark host script (authentication token required).");return;}
  wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"1Voice:QnnBench");wake.acquire(4*60*60*1000L);
  new Thread(()->{
   try {android.system.Os.setenv("ADSP_LIBRARY_PATH",getApplicationInfo().nativeLibraryDir+";/vendor/lib/rfsa/adsp;/vendor/dsp;/system/lib/rfsa/adsp;/dsp",true);bridge=new QnnBridge(getExternalFilesDir(null),getApplicationInfo().nativeLibraryDir,token);
    runOnUiThread(()->status.setText("QNN benchmark ready.\nKeep this screen open.\nModels run on this phone; results go to the host."));bridge.serve();
   }catch(Exception e){runOnUiThread(()->status.setText("Benchmark error: "+e));}
   finally{if(wake!=null && wake.isHeld())wake.release();}
  },"qnn-benchmark").start();
 }
 @Override public void onDestroy(){if(bridge!=null)bridge.stop();if(wake!=null && wake.isHeld())wake.release();super.onDestroy();}

 static final class QnnBridge {
  final OrtEnvironment env=OrtEnvironment.getEnvironment();
  final File root;final String libs,token;
  final Map<String,OrtSession> sessions=new LinkedHashMap<>();
  final Map<String,OnnxTensor> tensors=new HashMap<>();
  OfflineTts tts;String ttsLanguage="";int ttsThreads=0;
  ServerSocket server;Socket peer;long next=0;volatile boolean stopping=false;
  QnnBridge(File root,String libs,String token){this.root=root;this.libs=libs;this.token=token;}
  void stop(){stopping=true;try{if(peer!=null)peer.close();if(server!=null)server.close();}catch(IOException ignored){}}
  void serve() throws Exception {
   server=new ServerSocket(8765,1,InetAddress.getByName("127.0.0.1"));
   try {while(!stopping){try(Socket socket=server.accept()){
    peer=socket;socket.setSoTimeout(600000);socket.setTcpNoDelay(true);
    DataInputStream in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    DataOutputStream out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    while(!stopping){int size;try{size=in.readInt();}catch(EOFException end){break;}
     if(size<=0 || size>32*1024*1024)throw new IOException("Invalid frame size");
     byte[] data=new byte[size];in.readFully(data);JSONObject response;
     try{JSONObject request=new JSONObject(new String(data,java.nio.charset.StandardCharsets.UTF_8));
      if(!token.equals(request.optString("token")))throw new SecurityException("Invalid session token");
      response=dispatch(request).put("ok",true);
     }catch(Exception e){response=new JSONObject().put("ok",false).put("error",e.getClass().getSimpleName()+": "+e.getMessage());}
     byte[] bytes=response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);out.flush();
    }
   }catch(IOException e){if(stopping)break;}finally{clear();peer=null;}}
   }finally{clear();closeSessions();server.close();}
  }
  void clear(){for(OnnxTensor t:tensors.values())t.close();tensors.clear();}
  void closeSessions() throws OrtException {for(OrtSession s:sessions.values())s.close();sessions.clear();}
  JSONObject info(Map<String,NodeInfo> nodes) throws Exception {
   JSONObject all=new JSONObject();for(Map.Entry<String,NodeInfo> entry:nodes.entrySet()) {
    TensorInfo t=(TensorInfo)entry.getValue().getInfo();all.put(entry.getKey(),new JSONObject().put("shape",new JSONArray(t.getShape())).put("dtype",t.type.toString()));
   }return all;
  }
  String save(OnnxTensor t){String id="t"+(++next);tensors.put(id,t);return id;}
  OnnxTensor make(JSONObject item) throws Exception {
   JSONArray dims=item.getJSONArray("shape");long[] shape=new long[dims.length()];long count=1;
   for(int i=0;i<shape.length;i++){shape[i]=dims.getLong(i);if(shape[i]<0)throw new IllegalArgumentException("Negative dimension");count=Math.multiplyExact(count,shape[i]);}
   if(count>64*1024*1024)throw new IllegalArgumentException("Tensor too large");
   String dtype=item.getString("dtype");OnnxJavaType type;
   if(dtype.equals("float32"))type=OnnxJavaType.FLOAT;else if(dtype.equals("int32"))type=OnnxJavaType.INT32;else throw new IllegalArgumentException("Unsupported tensor dtype "+dtype);
   byte[] data=item.has("data")?Base64.decode(item.getString("data"),Base64.DEFAULT):new byte[Math.toIntExact(count*4)];
   if(data.length!=count*4)throw new IllegalArgumentException("Tensor byte count mismatch");
   ByteBuffer buffer=ByteBuffer.allocateDirect(data.length).order(ByteOrder.nativeOrder());buffer.put(data).rewind();
   return OnnxTensor.createTensor(env,buffer,shape,type);
  }
  JSONObject serialize(OnnxTensor t) throws Exception {
   TensorInfo info=t.getInfo();ByteBuffer raw=t.getByteBuffer();byte[] bytes=new byte[raw.remaining()];raw.get(bytes);
   String dtype=info.type==OnnxJavaType.FLOAT?"float32":info.type==OnnxJavaType.INT32?"int32":"unsupported";
   return new JSONObject().put("shape",new JSONArray(info.getShape())).put("dtype",dtype).put("data",Base64.encodeToString(bytes,Base64.NO_WRAP));
  }
  OnnxTensor copy(OnnxTensor t) throws Exception {
   ByteBuffer raw=t.getByteBuffer();ByteBuffer data=ByteBuffer.allocateDirect(raw.remaining()).order(ByteOrder.nativeOrder());data.put(raw).rewind();
   return OnnxTensor.createTensor(env,data,t.getInfo().getShape(),t.getInfo().type);
  }
  static final class FirstAudioCallback implements kotlin.jvm.functions.Function1<float[],Integer> {
   long first=0;public Integer invoke(float[] samples){if(first==0 && samples.length>0)first=System.nanoTime();return 1;}
  }
  JSONObject tts(JSONObject request) throws Exception {
   String lang=request.getString("lang");if(!lang.equals("vi") && !lang.equals("en"))throw new IllegalArgumentException("Unsupported TTS language");
   int threads=request.optInt("threads",3);if(threads<1 || threads>4)throw new IllegalArgumentException("Unsupported TTS thread count");
   String text=request.getString("text");if(text.length()>2000)throw new IllegalArgumentException("Text too long");long load=0;
   if(tts==null || !ttsLanguage.equals(lang) || threads!=ttsThreads){
    if(tts!=null)tts.free();tts=null;long started=System.nanoTime();
    File voice=new File(root,"tts/vits-piper-"+(lang.equals("vi")?"vi_VN-vais1000-medium":"en_US-lessac-medium"));File[] graphs=voice.listFiles((d,n)->n.endsWith(".onnx"));
    if(graphs==null || graphs.length!=1)throw new IllegalArgumentException("TTS voice missing "+voice);
    OfflineTtsVitsModelConfig vits=new OfflineTtsVitsModelConfig();vits.setModel(graphs[0].getPath());vits.setTokens(new File(voice,"tokens.txt").getPath());vits.setDataDir(new File(voice,"espeak-ng-data").getPath());vits.setNoiseScale(0);vits.setNoiseScaleW(0);vits.setLengthScale(1);
    OfflineTtsModelConfig model=new OfflineTtsModelConfig();model.setVits(vits);model.setNumThreads(threads);model.setProvider("cpu");OfflineTtsConfig config=new OfflineTtsConfig();config.setModel(model);
    tts=new OfflineTts(null,config);ttsLanguage=lang;ttsThreads=threads;load=System.nanoTime()-started;
   }
   long start=System.nanoTime();FirstAudioCallback callback=new FirstAudioCallback();
   GeneratedAudio audio=tts.generateWithCallback(text,0,1f,callback);
   double generate=(System.nanoTime()-start)/1e6;float[] samples=audio.getSamples();double energy=0;for(float value:samples){if(!Float.isFinite(value))throw new IllegalStateException("Invalid audio");energy+=value*value;}
   ByteBuffer pcm=ByteBuffer.allocate(samples.length*2).order(ByteOrder.LITTLE_ENDIAN);for(float v:samples)pcm.putShort((short)(Math.max(-1,Math.min(1,v))*32767));
   StringBuilder hex=new StringBuilder();for(byte value:java.security.MessageDigest.getInstance("SHA-256").digest(pcm.array()))hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));String hash=hex.toString();
   return new JSONObject().put("generate_ms",generate).put("first_callback_ms",callback.first==0?JSONObject.NULL:(callback.first-start)/1e6).put("load_ms",load/1e6).put("samples",samples.length).put("sample_rate",audio.getSampleRate()).put("rms",Math.sqrt(energy/Math.max(1,samples.length))).put("pcm_sha256",hash).put("threads",threads).put("lang",lang);
  }
  JSONObject mt(JSONObject request) throws Exception {
   long started=System.nanoTime();clear();OrtSession enc=Objects.requireNonNull(sessions.get("encoder")),dec=Objects.requireNonNull(sessions.get("decoder"));
   Map<String,OnnxTensor> cross=new LinkedHashMap<>(),cache=new LinkedHashMap<>();List<OnnxTensor> initial=new ArrayList<>();
   long elapsed=0,reorderNs=0,feedNs=0,beamNs=0;int calls=0;OrtSession.Result previous=null;
   boolean reuse=request.optBoolean("reuse_buffers",true);
   Map<String,OnnxTensor> scratch=new LinkedHashMap<>();Map<String,ByteBuffer> scratchBuffers=new LinkedHashMap<>();
   List<Map<String,OnnxTensor>> pinned=new ArrayList<>();
   if(reuse){for(int set=0;set<2;set++){
    Map<String,OnnxTensor> outputs=new LinkedHashMap<>();pinned.add(outputs);
    for(Map.Entry<String,NodeInfo> entry:dec.getOutputInfo().entrySet()){
     long[] shape=((TensorInfo)entry.getValue().getInfo()).getShape();int count=1;for(long n:shape)count=Math.multiplyExact(count,Math.toIntExact(n));
     ByteBuffer data=ByteBuffer.allocateDirect(count*4).order(ByteOrder.nativeOrder());outputs.put(entry.getKey(),OnnxTensor.createTensor(env,data,shape,OnnxJavaType.FLOAT));
    }
   }}
   try(OnnxTensor input=make(request.getJSONObject("input_ids"));OnnxTensor attention=make(request.getJSONObject("attention_mask"))){
    long start=System.nanoTime();OrtSession.Result encResult=enc.run(Map.of("input_ids",input,"attention_mask",attention));elapsed+=System.nanoTime()-start;calls++;
    try(OrtSession.Result encoded=encResult){
     for(Map.Entry<String,OnnxValue> entry:encoded)if(entry.getKey().startsWith("cross_")){
      String[] parts=entry.getKey().split("_");OnnxTensor src=(OnnxTensor)entry.getValue();long[] shape=src.getInfo().getShape();
      FloatBuffer buffer=src.getFloatBuffer();float[] data=new float[buffer.remaining()];buffer.get(data);float[] repeated=TensorOps.gather(data,1,new int[]{0,0});shape[0]=2;
      cross.put("cache_"+parts[1]+"_cross_"+parts[2],OnnxTensor.createTensor(env,FloatBuffer.wrap(repeated),shape));
     }
    }
    Map<String,NodeInfo> specs=dec.getInputInfo();
    for(Map.Entry<String,NodeInfo> entry:specs.entrySet())if(entry.getKey().contains("_self_")){
     long[] shape=((TensorInfo)entry.getValue().getInfo()).getShape();int count=1;for(long n:shape)count=Math.multiplyExact(count,Math.toIntExact(n));
     OnnxTensor z=OnnxTensor.createTensor(env,FloatBuffer.wrap(new float[count]),shape);cache.put(entry.getKey(),z);initial.add(z);
    }
    IntBuffer maskValues=attention.getIntBuffer();int[] sourceMask=new int[maskValues.remaining()];maskValues.get(sourceMask);float[] crossMask=new float[2*sourceMask.length];
    for(int i=0;i<crossMask.length;i++)crossMask[i]=sourceMask[i%sourceMask.length]==1?0f:-10000f;
    Beam2 beam=new Beam2(request.getInt("start"),request.getInt("eos"),192,request.getBoolean("early"),request.getInt("forced_eos"));
    try(OnnxTensor crossAttention=OnnxTensor.createTensor(env,FloatBuffer.wrap(crossMask),new long[]{2,1,1,sourceMask.length})){
     for(int pos=0;pos<192;pos++){
      List<OnnxTensor> temporary=new ArrayList<>();Map<String,OnnxTensor> feeds=new LinkedHashMap<>(cross);
      try{
       long stage=System.nanoTime();int[] parents=beam.parents();
       for(Map.Entry<String,OnnxTensor> entry:cache.entrySet()){
        OnnxTensor t=entry.getValue();
        if(pos>0 && (parents[0]!=0 || parents[1]!=1)){
         if(reuse){
          String name=entry.getKey();if(!scratch.containsKey(name)){
           int count=Math.toIntExact(t.getInfo().getNumElements());ByteBuffer data=ByteBuffer.allocateDirect(count*4).order(ByteOrder.nativeOrder());
           scratchBuffers.put(name,data);scratch.put(name,OnnxTensor.createTensor(env,data,t.getInfo().getShape(),OnnxJavaType.FLOAT));
          }
          ByteBuffer buffer=t.getBufferRef().isPresent()?(ByteBuffer)t.getBufferRef().get():t.getByteBuffer();
          TensorOps.gatherBytes(buffer,scratchBuffers.get(name),2,parents);t=scratch.get(name);
         }else{
          FloatBuffer buffer=t.getFloatBuffer();float[] data=new float[buffer.remaining()];buffer.get(data);
          t=OnnxTensor.createTensor(env,FloatBuffer.wrap(TensorOps.gather(data,2,parents)),t.getInfo().getShape());temporary.add(t);
         }
        }
        feeds.put(entry.getKey(),t);
       }
       reorderNs+=System.nanoTime()-stage;stage=System.nanoTime();
       float[] selfMask=new float[2*192];Arrays.fill(selfMask,-10000f);for(int b=0;b<2;b++)for(int n=191-pos;n<192;n++)selfMask[b*192+n]=0f;
       OnnxTensor ids=OnnxTensor.createTensor(env,IntBuffer.wrap(beam.inputTokens()),new long[]{2,1});temporary.add(ids);feeds.put("input_ids",ids);
       OnnxTensor self=OnnxTensor.createTensor(env,FloatBuffer.wrap(selfMask),new long[]{2,1,1,192});temporary.add(self);feeds.put("self_mask",self);feeds.put("cross_mask",crossAttention);
       if(specs.containsKey("position_ids")){OnnxTensor position=OnnxTensor.createTensor(env,IntBuffer.wrap(new int[]{pos,pos}),new long[]{2,1});temporary.add(position);feeds.put("position_ids",position);}
       feeds.keySet().retainAll(specs.keySet());feedNs+=System.nanoTime()-stage;start=System.nanoTime();OrtSession.Result output=reuse?dec.run(feeds,pinned.get(pos%2)):dec.run(feeds);elapsed+=System.nanoTime()-start;calls++;stage=System.nanoTime();
       if(previous!=null)previous.close();previous=output;if(pos==0){for(OnnxTensor z:initial)z.close();initial.clear();}
       FloatBuffer buffer=((OnnxTensor)output.get("logits").orElseThrow()).getFloatBuffer();float[] logits=new float[buffer.remaining()];buffer.get(logits);beam.advance(logits,logits.length/2);
       Map<String,OnnxTensor> next=new LinkedHashMap<>();for(String name:cache.keySet()){
        String[] parts=name.split("_");next.put(name,(OnnxTensor)output.get("present_"+parts[1]+"_"+parts[3]).orElseThrow());
       }cache=next;beamNs+=System.nanoTime()-stage;if(beam.done())break;
      }finally{for(OnnxTensor t:temporary)t.close();}
     }
    }
    int[] result=beam.best();return new JSONObject().put("tokens",new JSONArray(result)).put("token_cap",result.length-1>=192)
     .put("run_ms",elapsed/1e6).put("reorder_ms",reorderNs/1e6).put("feed_ms",feedNs/1e6).put("read_select_bind_ms",beamNs/1e6).put("reuse_buffers",reuse).put("device_decode_ms",(System.nanoTime()-started)/1e6).put("graph_calls",calls).put("output_copy_ms",0.0);
   }finally{if(previous!=null)previous.close();for(OnnxTensor z:initial)z.close();for(OnnxTensor t:cross.values())t.close();for(OnnxTensor t:scratch.values())t.close();for(Map<String,OnnxTensor> set:pinned)for(OnnxTensor t:set.values())t.close();}
  }
  JSONObject asr(JSONObject request) throws Exception {
   long decodeStarted=System.nanoTime();clear();OrtSession enc=Objects.requireNonNull(sessions.get("encoder")),dec=Objects.requireNonNull(sessions.get("decoder"));
   int[] forced={50258,50278,50359,50363};JSONArray tokens=new JSONArray();long elapsed=0;int calls=0;boolean cap=true;
   try(OnnxTensor mel=make(request.getJSONObject("mel"))){
    long start=System.nanoTime();OrtSession.Result encResult=enc.run(Map.of("input_features",mel));elapsed+=System.nanoTime()-start;calls++;
    try(OrtSession.Result encoded=encResult){
     Map<String,OnnxTensor> cross=new LinkedHashMap<>();
     for(Map.Entry<String,OnnxValue> e:encoded)cross.put(e.getKey(),(OnnxTensor)e.getValue());
     Map<String,OnnxTensor> cache=new LinkedHashMap<>();List<OnnxTensor> zeros=new ArrayList<>();
     for(Map.Entry<String,NodeInfo> e:dec.getInputInfo().entrySet())if(e.getKey().contains("_cache_self_") && e.getKey().endsWith("_in")){
      TensorInfo spec=(TensorInfo)e.getValue().getInfo();long size=1;for(long n:spec.getShape())size*=n;
      OnnxTensor t=OnnxTensor.createTensor(env,FloatBuffer.wrap(new float[Math.toIntExact(size)]),spec.getShape());cache.put(e.getKey(),t);zeros.add(t);
     }
     OrtSession.Result previous=null;int next=forced[0];
     try{for(int pos=0;pos<199;pos++){
      float[] mask=new float[200];Arrays.fill(mask,-100f);for(int i=199-pos;i<200;i++)mask[i]=0f;
      try(OnnxTensor ids=OnnxTensor.createTensor(env,IntBuffer.wrap(new int[]{pos<4?forced[pos]:next}),new long[]{1,1});
          OnnxTensor attention=OnnxTensor.createTensor(env,FloatBuffer.wrap(mask),new long[]{1,1,1,200});
          OnnxTensor position=OnnxTensor.createTensor(env,IntBuffer.wrap(new int[]{pos}),new long[]{1})){
       Map<String,OnnxTensor> feeds=new LinkedHashMap<>(cross);feeds.putAll(cache);feeds.put("input_ids",ids);feeds.put("attention_mask",attention);feeds.put("position_ids",position);
       start=System.nanoTime();OrtSession.Result output=dec.run(feeds);elapsed+=System.nanoTime()-start;calls++;
       if(previous!=null)previous.close();previous=output;if(pos==0){for(OnnxTensor z:zeros)z.close();zeros.clear();}
       FloatBuffer logits=((OnnxTensor)output.get("logits").orElseThrow()).getFloatBuffer();float best=Float.NEGATIVE_INFINITY;int index=0;next=0;
       while(logits.hasRemaining()){float value=logits.get();if(!Float.isFinite(value))throw new IllegalStateException("Nonfinite QNN logits");if(value>best){best=value;next=index;}index++;}
       Map<String,OnnxTensor> updated=new LinkedHashMap<>();for(String name:cache.keySet())updated.put(name,(OnnxTensor)output.get(name.substring(0,name.length()-3)+"_out").orElseThrow());cache=updated;
       if(pos>=3){if(next==50257){cap=false;break;}tokens.put(next);}
      }
     }}finally{if(previous!=null)previous.close();for(OnnxTensor z:zeros)z.close();}
    }
   }
   return new JSONObject().put("device_decode_ms",(System.nanoTime()-decodeStarted)/1e6).put("tokens",tokens).put("token_cap",cap).put("run_ms",elapsed/1e6).put("graph_calls",calls).put("output_copy_ms",0.0);
  }
  List<String> keys(JSONObject object){List<String> result=new ArrayList<>();Iterator<String> it=object.keys();while(it.hasNext())result.add(it.next());return result;}
  JSONObject dispatch(JSONObject r) throws Exception {
   String op=r.getString("op");JSONObject answer=new JSONObject();
   switch(op){
    case "tts":return tts(r);
    case "asr":return asr(r);
    case "mt":return mt(r);
    case "hello":return answer.put("benchmark_revision","native-mt-bytebuffers-v3").put("ort",env.getVersion()).put("model",Build.MODEL).put("soc",(Build.VERSION.SDK_INT>=31?Build.SOC_MODEL:Build.HARDWARE)).put("android",Build.VERSION.RELEASE).put("backend","QNN HTP; CPU fallback disabled");
    case "load":{
     clear();closeSessions();JSONObject models=r.getJSONObject("models");long start=System.nanoTime();
     try{for(String key:keys(models)) {
      File file=new File(root,models.getString(key)).getCanonicalFile();
      if(!file.toPath().startsWith(root.getCanonicalFile().toPath()) || !file.isFile())throw new IllegalArgumentException("Model missing/outside files root: "+file);
      try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
       options.setIntraOpNumThreads(3);options.addConfigEntry("session.disable_cpu_ep_fallback","1");
       options.addQnn(Map.of("backend_path",libs+"/libQnnHtp.so","htp_performance_mode","burst"));
       options.enableProfiling(new File(root,"profile-"+key).getPath());
       OrtSession s=env.createSession(file.getPath(),options);sessions.put(key,s);
       answer.put(key,new JSONObject().put("inputs",info(s.getInputInfo())).put("outputs",info(s.getOutputInfo())));
      }
     }}catch(Exception e){closeSessions();throw e;}
     return answer.put("load_ms",(System.nanoTime()-start)/1e6);
    }
    case "reset":clear();return answer;
    case "put":return answer.put("handle",save(make(r.getJSONObject("tensor"))));
    case "get":return answer.put("tensor",serialize(Objects.requireNonNull(tensors.get(r.getString("handle")))));
    case "drop":{
     JSONArray ids=r.getJSONArray("handles");for(int i=0;i<ids.length();i++){OnnxTensor t=tensors.remove(ids.getString(i));if(t!=null)t.close();}return answer;
    }
    case "gather":{
     OnnxTensor src=Objects.requireNonNull(tensors.get(r.getString("handle")));long[] shape=src.getInfo().getShape();
     if(src.getInfo().type!=OnnxJavaType.FLOAT)throw new IllegalArgumentException("Gather supports float cache only");
     JSONArray ids=r.getJSONArray("indices");int[] indices=new int[ids.length()];for(int i=0;i<indices.length;i++)indices[i]=ids.getInt(i);
     FloatBuffer buffer=src.getFloatBuffer();float[] floats=new float[buffer.remaining()];buffer.get(floats);
     float[] gathered=TensorOps.gather(floats,Math.toIntExact(shape[0]),indices);shape[0]=indices.length;
     return answer.put("handle",save(OnnxTensor.createTensor(env,FloatBuffer.wrap(gathered),shape)));
    }
    case "run":{
     JSONArray pending=r.optJSONArray("pending");
     if(pending!=null)for(int i=0;i<pending.length();i++){
      JSONObject command=pending.getJSONObject(i);String commandOp=command.getString("op");
      if(!commandOp.equals("put") && !commandOp.equals("gather"))throw new IllegalArgumentException("Invalid pending op");
      String id=command.getString("id");if(!id.startsWith("c") || tensors.containsKey(id))throw new IllegalArgumentException("Invalid pending tensor ID");
      String generated=dispatch(command).getString("handle");tensors.put(id,tensors.remove(generated));
     }
     JSONArray drops=r.optJSONArray("drop");if(drops!=null)for(int i=0;i<drops.length();i++){OnnxTensor t=tensors.remove(drops.getString(i));if(t!=null)t.close();}
     OrtSession session=Objects.requireNonNull(sessions.get(r.getString("session")));JSONObject inputs=r.getJSONObject("inputs");
     Map<String,OnnxTensor> feeds=new LinkedHashMap<>();List<OnnxTensor> temporary=new ArrayList<>();
     try{for(String name:keys(inputs)){
      Object value=inputs.get(name);OnnxTensor tensor;
      if(value instanceof String)tensor=Objects.requireNonNull(tensors.get((String)value));
      else{tensor=make((JSONObject)value);temporary.add(tensor);}feeds.put(name,tensor);
     }
     long start=System.nanoTime();try(OrtSession.Result result=session.run(feeds)){
      double runMs=(System.nanoTime()-start)/1e6;JSONObject outputs=new JSONObject();
      long copying=System.nanoTime();for(Map.Entry<String,OnnxValue> entry:result)outputs.put(entry.getKey(),save(copy((OnnxTensor)entry.getValue())));
      JSONObject fetched=new JSONObject();
      if(outputs.has("logits")){String handle=outputs.getString("logits");fetched.put(handle,serialize(tensors.get(handle)));}
      return answer.put("fetched",fetched).put("outputs",outputs).put("run_ms",runMs).put("output_copy_ms",(System.nanoTime()-copying)/1e6);
     }}finally{for(OnnxTensor t:temporary)t.close();}
    }
    case "unload":{
     clear();JSONObject profiles=new JSONObject();for(Map.Entry<String,OrtSession> e:sessions.entrySet())profiles.put(e.getKey(),e.getValue().endProfiling());closeSessions();return answer.put("profiles",profiles);
    }
    default:throw new IllegalArgumentException("Unknown operation "+op);
   }
  }
 }
}
