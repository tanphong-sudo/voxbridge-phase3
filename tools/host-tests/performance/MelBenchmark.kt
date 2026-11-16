package com.voxbridge.runtime

import java.io.File
import kotlin.math.*
import kotlin.random.Random

/** Paired CPU-only comparison; no ASR/MT models or Android hardware. */
fun main(args: Array<String>) {
    val filters=File(args[0])
    val before=BaselineMelSpectrogram(filters)
    val after=MelSpectrogram(filters)
    val random=Random(721)
    val cases=listOf(0,1,1599,16000,48000,479999,480000,480201).map { size ->
        FloatArray(size) { i -> (0.2*sin(2*PI*437*i/16000)+0.01*(random.nextDouble()-.5)).toFloat() }
    }
    for (audio in cases) {
        val a=before.logMel(audio);val b=after.logMel(audio)
        check(a.size==b.size)
        for (i in a.indices) check(a[i].toRawBits()==b[i].toRawBits()) { "Non-identical mel size=${audio.size} index=$i before=${a[i]} after=${b[i]}" }
    }
    println("BITWISE_PARITY ${cases.size} signals, including empty/one-sample/padding/30s/trim")
    for (seconds in listOf(1,3)) {
        val audio=cases.first { it.size==seconds*16000 }
        repeat(10) { before.logMel(audio);after.logMel(audio) }
        val old=mutableListOf<Double>();val new=mutableListOf<Double>()
        fun time(block:()->FloatArray):Double { val start=System.nanoTime();check(block().size==240000);return (System.nanoTime()-start)/1e6 }
        repeat(21) { n ->
            if (n%2==0) { old+=time { before.logMel(audio) };new+=time { after.logMel(audio) } }
            else { new+=time { after.logMel(audio) };old+=time { before.logMel(audio) } }
        }
        fun median(v:List<Double>)=v.sorted()[v.size/2]
        println("MEL seconds=$seconds n=21 baseline_median_ms=${median(old)} candidate_median_ms=${median(new)} speedup=${median(old)/median(new)} baseline_p95_ms=${old.sorted()[19]} candidate_p95_ms=${new.sorted()[19]}")
    }
}
