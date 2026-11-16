import com.voxbridge.qdcbench.TensorOps;
import java.nio.*;
import java.util.*;
class BufferGatherTest {
 public static void main(String[] args) {
  FloatBuffer src=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer();src.put(new float[]{1,2,3,4}).rewind();
  FloatBuffer dest=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer();
  TensorOps.gatherInto(src,dest,2,new int[]{1,1});float[] got=new float[4];dest.duplicate().get(got);
  if(!Arrays.equals(got,new float[]{3,4,3,4}))throw new AssertionError("duplicate rows");
  TensorOps.gatherInto(src,dest,2,new int[]{1,0});dest.duplicate().get(got);
  if(!Arrays.equals(got,new float[]{3,4,1,2}))throw new AssertionError("swapped rows");
  if(src.position()!=0 || dest.position()!=0)throw new AssertionError("buffer cursor changed");
  try{TensorOps.gatherInto(src,src,2,new int[]{1,0});throw new AssertionError("alias accepted");}catch(IllegalArgumentException ok){}
  ByteBuffer bytes=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());bytes.asFloatBuffer().put(new float[]{1,2,3,4});
  ByteBuffer target=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());TensorOps.gatherBytes(bytes,target,2,new int[]{1,0});target.asFloatBuffer().get(got);
  if(!Arrays.equals(got,new float[]{3,4,1,2}))throw new AssertionError("byte-buffer swap");
  System.out.println("PASS direct-buffer gather swap, duplicate, cursor preservation, alias rejection");
 }
}
