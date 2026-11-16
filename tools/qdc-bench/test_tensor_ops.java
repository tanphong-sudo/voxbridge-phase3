import com.voxbridge.qdcbench.TensorOps;
import java.util.Arrays;
class test_tensor_ops {
 public static void main(String[] args) {
  if(!Arrays.equals(TensorOps.gather(new float[]{1,2,3,4},2,new int[]{1,1}),new float[]{3,4,3,4}))throw new AssertionError("beam duplicate");
  if(!Arrays.equals(TensorOps.gather(new float[]{1,2},1,new int[]{0,0}),new float[]{1,2,1,2}))throw new AssertionError("cross repeat");
  try {TensorOps.gather(new float[]{1,2},1,new int[]{1});throw new AssertionError("out of range accepted");}catch(IllegalArgumentException ok){}
  try {TensorOps.gather(new float[]{1,2,3},2,new int[]{0});throw new AssertionError("bad shape accepted");}catch(IllegalArgumentException ok){}
  System.out.println("PASS: beam reorder/duplicate, cross repeat, shape/index rejection");
 }
}
