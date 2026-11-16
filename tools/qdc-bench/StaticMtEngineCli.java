import com.voxbridge.runtime.*;
import ai.onnxruntime.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
public class StaticMtEngineCli {
 public static void main(String[] args)throws Exception {
  OrtEnvironment env=OrtEnvironment.getEnvironment();
  StaticMtTokenizer tok=new StaticMtTokenizer(new File(args[2],"tokenizer.json"));
  JSONObject generation=new JSONObject(Files.readString(Path.of(args[2],"generation_config.json")));
  int forced=generation.has("forced_eos_token_id")&&!generation.isNull("forced_eos_token_id")?generation.getInt("forced_eos_token_id"):-1;
  try(OrtSession.SessionOptions opts=new OrtSession.SessionOptions()){
   opts.setIntraOpNumThreads(3);opts.setInterOpNumThreads(1);
   try(StaticMtEngine engine=new StaticMtEngine(env,env.createSession(args[0],opts),env.createSession(args[1],opts))){
    BufferedReader reader=new BufferedReader(new InputStreamReader(System.in));String line;
    while((line=reader.readLine())!=null){JSONObject r=new JSONObject(line);JSONObject result=new JSONObject().put("id",r.optString("id"));
     try{String src=r.getString("source_lang"),target=src.equals("vi")?"en":"vi";
      StaticMtTokenizer.Encoded encoded=tok.encode(r.getString("source_text"),src,64);
      StaticMtEngine.Decoded d=engine.decode(encoded.ids,encoded.mask,tok.startId(target),tok.eosId(),!tok.isEnvit5(),forced);
      result.put("tokens",new JSONArray(d.tokens)).put("hypothesis",tok.decode(d.tokens)).put("token_cap",d.hitTokenLimit).put("elapsed_ms",d.deviceDecodeMs).put("encoder_ms",d.encoderMs).put("decoder_ms",d.decoderMs).put("reorder_ms",d.reorderMs);
     }catch(Exception e){result.put("failure",e.toString());}
     System.out.println(result);System.out.flush();
    }
   }
  }
 }
}
