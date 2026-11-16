import com.voxbridge.runtime.StaticMtTokenizer;
import org.json.*;
import java.io.*;
public class TokenizerParityCli {
 public static void main(String[] a)throws Exception {
  StaticMtTokenizer t=new StaticMtTokenizer(new File(a[0]));BufferedReader in=new BufferedReader(new InputStreamReader(System.in));String line;
  while((line=in.readLine())!=null){JSONObject r=new JSONObject(line);JSONObject o=new JSONObject();
   try {if(r.has("text")){StaticMtTokenizer.Encoded e=t.encode(r.getString("text"),r.getString("source"),64);o.put("ids",new JSONArray(e.ids)).put("mask",new JSONArray(e.mask));}
    if(r.has("decode")){JSONArray j=r.getJSONArray("decode");int[] ids=new int[j.length()];for(int i=0;i<ids.length;i++)ids[i]=j.getInt(i);o.put("decoded",t.decode(ids));}}
   catch(Exception e){o.put("error",e.toString());}
   System.out.println(o);
  }
 }
}
