import com.voxbridge.runtime.ParakeetMel;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
public class ParakeetMelCli {
 public static void main(String[] args)throws Exception{
  byte[] raw=Files.readAllBytes(Path.of(args[2]));FloatBuffer input=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();float[] pcm=new float[input.remaining()];input.get(pcm);
  ParakeetMel mel=new ParakeetMel(new File(args[0]),new File(args[1]));long begin=System.nanoTime();ParakeetMel.Features f=mel.compute(pcm);
  ByteBuffer out=ByteBuffer.allocate(f.values.length*4).order(ByteOrder.LITTLE_ENDIAN);out.asFloatBuffer().put(f.values);Files.write(Path.of(args[3]),out.array());System.out.println("frames="+f.length+" ms="+(System.nanoTime()-begin)/1e6);
 }
}
