package com.voxbridge.runtime;
import java.util.*;
/** Batch-one, deterministic beam2 matching Transformers4.51.3 (length penalty1). */
public final class StaticMtBeam2 {
 private static final float NEG=-1e9f;
 private final int eos,max,forced;private final boolean early;
 private int generated=0;private boolean done=false;
 private int[][] running;private float[] scores={0,NEG};private int[] parents={0,1};
 private final ArrayList<Candidate> finished=new ArrayList<>();
 private static final class Candidate {
  float score;int source,token;int[] sequence;boolean finished;
  Candidate(float score,int source,int token,int[] sequence,boolean finished){this.score=score;this.source=source;this.token=token;this.sequence=sequence;this.finished=finished;}
 }
 public StaticMtBeam2(int start,int eos,int max,boolean early,int forced){
  if(max<1 || eos<0)throw new IllegalArgumentException("Unsupported beam contract");
  this.eos=eos;this.max=max;this.early=early;this.forced=forced;running=new int[][]{{start},{start}};
  finished.add(new Candidate(NEG,0,0,new int[]{start},false));finished.add(new Candidate(NEG,0,0,new int[]{start},false));
 }
 public int[] inputTokens(){return new int[]{running[0][running[0].length-1],running[1][running[1].length-1]};}
 public int[] parents(){return parents.clone();}
 public boolean done(){return done;}
 /** Minimum repeated span (tokens) before a tail repetition counts as a decoder loop. */
 public static final int LOOP_MIN_SPAN=18;
 /** True when the best running beam ends in one unit repeated >=3 times (span >= LOOP_MIN_SPAN).
  *  Checking only the best beam: on QDC VE-8787 ("No, no, no, ...") the second beam kept varying
  *  (and a short hypothesis could already be finished), so a both-beams rule never fired and the
  *  search ran to the 192-token limit (2.6 s) before the same AskRepeat. */
 public boolean looping(){
  return !done && tailRepeats(running[0]);
 }
 static boolean tailRepeats(int[] seq){
  for(int period=1;period<=24;period++){
   int span=period*3;if(span<LOOP_MIN_SPAN)span=((LOOP_MIN_SPAN+period-1)/period)*period;
   if(span>seq.length-1)break;
   boolean same=true;int end=seq.length;
   for(int i=end-span;i<end-period && same;i++)same=seq[i]==seq[i+period];
   if(same)return true;
  }
  return false;
 }
 public int[] runningBest(){return running[0].clone();}
 public int[] best(){if(!done)throw new IllegalStateException("Beam search unfinished");return finished.get(0).sequence.clone();}
 private static int[] append(int[] input,int token){int[] a=Arrays.copyOf(input,input.length+1);a[input.length]=token;return a;}
 private static final Comparator<Candidate> DESC=(a,b)->Float.compare(b.score,a.score);
 public void advance(float[] logits,int vocab){
  if(done || logits.length!=2*vocab)throw new IllegalArgumentException("Invalid beam logits");
  ArrayList<Candidate> top=new ArrayList<>();
  for(int beam=0;beam<2;beam++){
   float highest=Float.NEGATIVE_INFINITY;for(int j=0;j<vocab;j++){float v=logits[beam*vocab+j];if(!Float.isFinite(v))throw new IllegalArgumentException("Nonfinite QNN logits");if(v>highest)highest=v;}
   double sum=0;for(int j=0;j<vocab;j++)sum+=Math.exp((double)logits[beam*vocab+j]-highest);
   double normal=highest+Math.log(sum);
   for(int j=0;j<vocab;j++){
    float lp=(float)(logits[beam*vocab+j]-normal);
    if(forced>=0 && generated==max-1)lp=j==forced?0f:Float.NEGATIVE_INFINITY;
    float score=lp+scores[beam];
    if(top.size()<4 || score>top.get(top.size()-1).score){
     top.add(new Candidate(score,beam,j,null,j==eos || generated==max-1));top.sort(DESC);if(top.size()>4)top.remove(4);
    }
   }
  }
  generated++;boolean allStop=true;
  for(Candidate c:top){c.sequence=append(running[c.source],c.token);allStop&=c.finished;}
  ArrayList<Candidate> active=new ArrayList<>();
  for(Candidate c:top)active.add(new Candidate(c.score+(c.finished?NEG:0),c.source,c.token,c.sequence,c.finished));
  active.sort(DESC);int[][] newRunning=new int[2][];float[] newScores=new float[2];
  for(int i=0;i<2;i++){Candidate c=active.get(i);newRunning[i]=c.sequence;newScores[i]=c.score;parents[i]=c.source;}
  boolean full=finished.get(0).finished && finished.get(1).finished;
  ArrayList<Candidate> merged=new ArrayList<>(finished);
  for(int i=0;i<top.size();i++){
   Candidate c=top.get(i);boolean eligible=i<2 && c.finished;
   float value=c.score/generated;if(full && early)value+=NEG;if(!eligible)value+=NEG;
   merged.add(new Candidate(value,c.source,c.token,c.sequence,eligible));
  }
  merged.sort(DESC);finished.clear();finished.add(merged.get(0));finished.add(merged.get(1));running=newRunning;scores=newScores;
  full=finished.get(0).finished && finished.get(1).finished;
  float worst=full?Math.min(finished.get(0).score,finished.get(1).score):NEG;
  done=!(scores[0]/generated>worst) || (full && early) || allStop || generated>=max;
 }
}
