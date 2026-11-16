import com.voxbridge.qdcbench.Beam2;
import java.io.*;
class BeamCli {
 public static void main(String[] args)throws Exception {
  DataInputStream in=new DataInputStream(new BufferedInputStream(System.in));DataOutputStream out=new DataOutputStream(new BufferedOutputStream(System.out));
  Beam2 beam=new Beam2(Integer.parseInt(args[0]),Integer.parseInt(args[1]),Integer.parseInt(args[2]),Boolean.parseBoolean(args[3]),Integer.parseInt(args[4]));
  while(true){int[] tokens=beam.inputTokens(),parents=beam.parents();for(int n:tokens)out.writeInt(n);for(int n:parents)out.writeInt(n);out.flush();
   int size=in.readInt();float[] logits=new float[size];for(int i=0;i<size;i++)logits[i]=in.readFloat();beam.advance(logits,size/2);
   out.writeBoolean(beam.done());out.flush();if(beam.done()){int[] best=beam.best();out.writeInt(best.length);for(int n:best)out.writeInt(n);out.flush();break;}
  }
 }
}
