package com.voxbridge.runtime;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
/** NeMo Parakeet v2 deterministic 128-band frontend. No learned weights or audio gain. */
public final class ParakeetMel {
 public static final int BANDS=128,FFT=512,BINS=257,HOP=160,MAX_FRAMES=1600;
 private final float[] filters,window;
 private final int[] first=new int[BANDS],last=new int[BANDS],reverse=new int[FFT];
 public static final class Features{public final float[] values;public final int length;Features(float[] v,int n){values=v;length=n;}}
 public ParakeetMel(File filter,File hann)throws IOException{
  filters=read(filter,BANDS*BINS);float[] small=read(hann,400);window=new float[FFT];System.arraycopy(small,0,window,56,400);
  for(int m=0;m<BANDS;m++){first[m]=BINS;for(int f=0;f<BINS;f++)if(filters[m*BINS+f]!=0){first[m]=Math.min(first[m],f);last[m]=f+1;}}
  for(int n=0;n<FFT;n++)reverse[n]=Integer.reverse(n) >>> (32-9);
 }
 private static float[] read(File file,int count)throws IOException{byte[] b=Files.readAllBytes(file.toPath());if(b.length!=count*4)throw new IOException("Invalid frontend table "+file);FloatBuffer f=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();float[] out=new float[count];f.get(out);return out;}
 private static int reflected(int i,int n){if(n<=1)return 0;int period=2*(n-1);i=(i%period+period)%period;return i<n?i:period-i;}
 public synchronized Features compute(float[] audio){
  int frames=audio.length/HOP;if(frames<2||frames>MAX_FRAMES)throw new IllegalArgumentException("Audio outside Parakeet frontend budget");
  float[] signal=new float[audio.length];signal[0]=audio[0];for(int i=1;i<audio.length;i++)signal[i]=audio[i]-.97f*audio[i-1];
  float[] output=new float[BANDS*MAX_FRAMES],power=new float[BINS];double[] re=new double[FFT],im=new double[FFT];
  for(int t=0;t<frames;t++){
   int start=t*HOP-FFT/2;
   for(int i=0;i<FFT;i++){re[reverse[i]]=((start+i>=0 && start+i<signal.length)?signal[start+i]:0f)*window[i];im[reverse[i]]=0;}
   for(int size=2;size<=FFT;size*=2){int half=size/2;double angle=-2*Math.PI/size;
    for(int base=0;base<FFT;base+=size)for(int j=0;j<half;j++){
     double c=Math.cos(angle*j),s=Math.sin(angle*j),r=re[base+j+half]*c-im[base+j+half]*s,q=re[base+j+half]*s+im[base+j+half]*c;
     re[base+j+half]=re[base+j]-r;im[base+j+half]=im[base+j]-q;re[base+j]+=r;im[base+j]+=q;
    }
   }
   for(int f=0;f<BINS;f++){float r=(float)re[f],i=(float)im[f];float mag=(float)Math.sqrt(r*r+i*i);power[f]=mag*mag;}
   for(int m=0;m<BANDS;m++){float sum=0;for(int f=first[m];f<last[m];f++)sum+=filters[m*BINS+f]*power[f];output[m*MAX_FRAMES+t]=(float)Math.log(sum+0x1.0p-24f);}
  }
  for(int m=0;m<BANDS;m++){
   int base=m*MAX_FRAMES;double sum=0;for(int t=0;t<frames;t++)sum+=output[base+t];float mean=(float)(sum/frames);double sq=0;
   for(int t=0;t<frames;t++){float d=output[base+t]-mean;sq+=d*d;}float std=(float)Math.sqrt(sq/(frames-1))+1e-5f;
   for(int t=0;t<frames;t++)output[base+t]=(output[base+t]-mean)/std;
  }
  return new Features(output,frames);
 }
}
