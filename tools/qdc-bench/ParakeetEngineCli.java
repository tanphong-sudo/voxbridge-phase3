import com.voxbridge.runtime.*;
import ai.onnxruntime.*;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
public class ParakeetEngineCli {
 public static void main(String[] args)throws Exception{
  OrtEnvironment env=OrtEnvironment.getEnvironment();File root=new File(args[2]);ParakeetMel mel=new ParakeetMel(new File(root,"mel_128_257.f32"),new File(root,"hann_400.f32"));
  JSONArray vocab=new JSONArray(Files.readString(new File(root,"tokens.json").toPath()));JSONArray rows=new JSONArray(Files.readString(new File(root,"manifest.json").toPath()));
  try(OrtSession.SessionOptions opts=new OrtSession.SessionOptions()){
   opts.setIntraOpNumThreads(3);opts.setInterOpNumThreads(1);
   try(ParakeetEngine engine=new ParakeetEngine(env,env.createSession(args[0],opts),env.createSession(args[1],opts))){
    int limit=args.length>3?Integer.parseInt(args[3]):rows.length();
    for(int i=0;i<Math.min(limit,rows.length());i++){
     String id=rows.getJSONObject(i).getString("id");byte[] raw=Files.readAllBytes(new File(root,id+".pcm.f32").toPath());FloatBuffer fb=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();float[] pcm=new float[fb.remaining()];fb.get(pcm);
     long start=System.nanoTime();ParakeetMel.Features f=mel.compute(pcm);double melMs=(System.nanoTime()-start)/1e6;ParakeetEngine.Recognition r=engine.recognize(f);
     StringBuilder s=new StringBuilder();for(int t:r.tokens)s.append(vocab.getString(t));
     System.out.println(new JSONObject().put("id",id).put("tokens",new JSONArray(r.tokens)).put("hypothesis",s.toString().replace('▁',' ').trim()).put("mel_ms",melMs).put("decode_ms",r.elapsedMs).put("token_cap",r.tokenLimit));System.out.flush();
    }
   }
  }
 }
}
