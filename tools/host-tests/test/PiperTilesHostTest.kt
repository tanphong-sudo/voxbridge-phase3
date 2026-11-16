package com.voxbridge.runtime

import org.junit.Assert.*
import org.junit.Test

class PiperTilesHostTest {
    @Test fun tiledTransportPreservesEveryFrameAndChannelAcrossBoundaries() {
        for(frames in listOf(1,8,63,64,65,96,163,211)) {
            val latent=FloatArray(192*frames){(it/frames)*1000f+(it%frames)}
            val result=PiperTiles.render(latent){tile,valid->
                assertTrue(valid in 1..64)
                for(channel in 1 until 192) for(frame in 0 until valid)
                    assertEquals(channel*1000f+tile[frame],tile[channel*64+frame],0f)
                FloatArray(64*256){tile[it/256]}
            }
            assertEquals(frames*256,result.size)
            result.forEachIndexed{index,value->assertEquals((index/256).toFloat(),value,0f)}
        }
    }
    @Test fun invalidLatentOrNonfiniteWaveformFailsClosed() {
        assertThrows(IllegalArgumentException::class.java){PiperTiles.render(FloatArray(193)){_,_->FloatArray(16384)}}
        assertThrows(IllegalStateException::class.java){PiperTiles.render(FloatArray(192)){_,_->FloatArray(16384){Float.NaN}}}
    }
    @Test fun silenceScalingKeepsSpeechAndShortPausesUnchanged() {
        val speech=FloatArray(100){.1f}+FloatArray(10)+FloatArray(100){-.1f}
        assertArrayEquals(speech,PiperTiles.scaleSilence(speech,1000),0f)
        val longer=FloatArray(100){.1f}+FloatArray(300)+FloatArray(100){-.1f}
        val trimmed=PiperTiles.scaleSilence(longer,1000)
        assertEquals(260,trimmed.size)
        assertArrayEquals(FloatArray(100){.1f},trimmed.copyOfRange(0,100),0f)
        assertArrayEquals(FloatArray(100){-.1f},trimmed.copyOfRange(160,260),0f)
    }
}
