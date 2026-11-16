import com.voxbridge.qdcbench.Beam2;
import org.json.*;
import java.nio.file.*;
import java.util.*;
class BeamFixtureTest {
 public static void main(String[] args)throws Exception {
  JSONArray fixtures=new JSONArray(Files.readString(Path.of(args[0])));
  for(int f=0;f<fixtures.length();f++){
   JSONObject x=fixtures.getJSONObject(f);Beam2 b=new Beam2(x.getInt("start"),x.getInt("eos"),x.getInt("max"),x.getBoolean("early"),x.getInt("forced_eos"));
   JSONArray steps=x.getJSONArray("steps");
   for(int i=0;i<steps.length();i++){
    JSONObject step=steps.getJSONObject(i);JSONArray ids=step.getJSONArray("input_ids");
    if(!Arrays.equals(b.inputTokens(),new int[]{ids.getJSONArray(0).getInt(0),ids.getJSONArray(1).getInt(0)}))throw new AssertionError("input beams mismatch fixture="+f+" step="+i);
    JSONArray raw=step.getJSONArray("logits");int vocab=raw.getJSONArray(0).length();float[] logits=new float[2*vocab];
    for(int j=0;j<2;j++)for(int k=0;k<vocab;k++)logits[j*vocab+k]=(float)raw.getJSONArray(j).getDouble(k);
    b.advance(logits,vocab);
    if(b.done()!=(i==steps.length()-1))throw new AssertionError("stopping mismatch fixture="+f+" step="+i);
   }
   JSONArray expected=x.getJSONArray("expected");int[] want=new int[expected.length()];for(int i=0;i<want.length;i++)want[i]=expected.getInt(i);
   if(!Arrays.equals(b.best(),want))throw new AssertionError("final tokens fixture="+f+" got="+Arrays.toString(b.best())+" want="+Arrays.toString(want));
  }
  System.out.println("PASS: "+fixtures.length()+" original HF beam2 fixtures, per-step tokens, stopping, final sequence");
 }
}
