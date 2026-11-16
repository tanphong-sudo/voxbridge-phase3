package voxbridge.e2e

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

/** Real WAV/child-process boundary tests; the Python child implements protocol only, no ASR. */
class PipelineBridgeTest {
    @TempDir lateinit var directory: Path

    @Test fun `PCM16 extrema and representative samples round trip bit exactly`() {
        val original = shortArrayOf(-32768,-32767,-16384,-257,-1,0,1,255,256,16384,32766,32767)
        val samples = FloatArray(original.size) { original[it] / 32768f }
        val wav = directory.resolve("roundtrip.wav").toFile()
        writePcm16Wav(wav,samples)
        val bytes = wav.readBytes()
        assertEquals("RIFF",bytes.copyOfRange(0,4).toString(Charsets.US_ASCII))
        assertEquals("WAVE",bytes.copyOfRange(8,12).toString(Charsets.US_ASCII))
        val payload = ByteBuffer.wrap(bytes,44,bytes.size-44).order(ByteOrder.LITTLE_ENDIAN)
        val actual = ShortArray(original.size) { payload.short }
        assertArrayEquals(original,actual)
        assertArrayEquals(samples,readMono16kPcm16Wav(wav),0f)
        assertEquals(44+2*original.size,bytes.size)
        assertEquals(64,sha256(wav).length)
    }

    @Test fun `WAV writer rejects nonfinite out of range empty and oversized inputs`() {
        val invalid = listOf(floatArrayOf(Float.NaN),floatArrayOf(Float.POSITIVE_INFINITY),
            floatArrayOf(Float.NEGATIVE_INFINITY),floatArrayOf(-1.01f),floatArrayOf(1.01f),
            floatArrayOf(),FloatArray(480001))
        invalid.forEachIndexed { index,samples ->
            val file = directory.resolve("invalid-$index.wav").toFile()
            assertThrows(IllegalArgumentException::class.java,{ writePcm16Wav(file,samples) },"input $index")
            assertFalse(file.exists(),"Invalid input must be rejected before file creation: $index")
        }
    }

    @Test fun `READY false constructor terminates its child before failing`() {
        val pid = directory.resolve("rejected.pid").toFile()
        val child = script("reject.py", """
            import json, os, pathlib, time
            pathlib.Path(${JSONObject.quote(pid.absolutePath)}).write_text(str(os.getpid()))
            print(json.dumps({'ready': False}), flush=True)
            time.sleep(60)
        """.trimIndent())
        try {
            assertThrows(IllegalStateException::class.java) {
                LocalLineWorker(listOf("python3","-u",child.absolutePath),directory.toFile(),"reject")
            }
            assertTrue(pid.isFile,"Child wrote its PID before the readiness line")
            assertFalse(alive(pid),"Rejected worker process leaked from constructor")
        } finally { terminateIfAlive(pid) }
    }

    @Test fun `JSONL request returns actual child response and close terminates child`() {
        val pid = directory.resolve("success.pid").toFile()
        val child = script("echo.py", """
            import json, os, pathlib, sys
            pathlib.Path(${JSONObject.quote(pid.absolutePath)}).write_text(str(os.getpid()))
            print('protocol boundary diagnostic', flush=True)
            print(json.dumps({'ready': True, 'boundary_only': True}), flush=True)
            for line in sys.stdin:
                request = json.loads(line)
                print(json.dumps({'ok': True, 'id': request['id'], 'value': request['value']}), flush=True)
        """.trimIndent())
        val worker = LocalLineWorker(listOf("python3","-u",child.absolutePath),directory.toFile(),"echo")
        try {
            assertTrue(worker.ready.getBoolean("boundary_only"))
            assertTrue(alive(pid))
            val response = worker.request(JSONObject().put("id","boundary-case").put("value","Tiếng Việt\nquoted \"text\""))
            assertEquals("boundary-case",response.getString("id"))
            assertEquals("Tiếng Việt\nquoted \"text\"",response.getString("value"))
            assertEquals("protocol boundary diagnostic\n",directory.resolve("echo-worker.stdout.log").toFile().readText())
        } finally {
            worker.close()
            try { assertFalse(alive(pid),"Successful worker remained alive after close") }
            finally { terminateIfAlive(pid) }
        }
    }

    private fun script(name:String,body:String):File = directory.resolve(name).toFile().apply { writeText(body+"\n") }
    private fun alive(pid:File):Boolean = pid.isFile && ProcessHandle.of(pid.readText().trim().toLong()).map { it.isAlive }.orElse(false)
    private fun terminateIfAlive(pid:File) {
        if(pid.isFile) ProcessHandle.of(pid.readText().trim().toLong()).ifPresent { if(it.isAlive) it.destroyForcibly() }
    }
}
