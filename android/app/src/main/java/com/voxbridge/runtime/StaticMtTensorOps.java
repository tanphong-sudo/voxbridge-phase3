package com.voxbridge.runtime;
/** Exact gather on batch dimension, including duplicated beam rows. */
public final class StaticMtTensorOps {
 public static void gatherBytes(java.nio.ByteBuffer source,java.nio.ByteBuffer dest,int rows,int[] indices){
  if(source==dest || rows<1 || source.capacity()%rows!=0 || dest.capacity()!=source.capacity()/rows*indices.length)throw new IllegalArgumentException("Invalid gather buffers");
  int stride=source.capacity()/rows;java.nio.ByteBuffer output=dest.duplicate();output.clear();
  for(int index:indices){if(index<0 || index>=rows)throw new IllegalArgumentException("Invalid beam index");java.nio.ByteBuffer view=source.duplicate();view.position(index*stride);view.limit((index+1)*stride);output.put(view);}
 }
 public static void gatherInto(java.nio.FloatBuffer source,java.nio.FloatBuffer dest,int rows,int[] indices){
  if(source==dest || rows<1 || source.capacity()%rows!=0 || dest.capacity()!=source.capacity()/rows*indices.length)throw new IllegalArgumentException("Invalid gather buffers");
  int stride=source.capacity()/rows;java.nio.FloatBuffer output=dest.duplicate();output.clear();
  for(int index:indices){if(index<0 || index>=rows)throw new IllegalArgumentException("Invalid beam index");java.nio.FloatBuffer view=source.duplicate();view.position(index*stride);view.limit((index+1)*stride);output.put(view);}
 }
 public static float[] gather(float[] input,int rows,int[] indices) {
  if(rows<=0 || input.length%rows!=0)throw new IllegalArgumentException("Invalid batch shape");
  int stride=input.length/rows;float[] output=new float[Math.multiplyExact(stride,indices.length)];
  for(int i=0;i<indices.length;i++) {
   if(indices[i]<0 || indices[i]>=rows)throw new IllegalArgumentException("Invalid beam index");
   System.arraycopy(input,indices[i]*stride,output,i*stride,stride);
  }
  return output;
 }
}
