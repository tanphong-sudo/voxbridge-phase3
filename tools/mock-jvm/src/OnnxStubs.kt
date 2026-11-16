package ai.onnxruntime
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

enum class OnnxJavaType { INT64, INT32, FLOAT }

class TensorInfo(val shape: LongArray, val type: OnnxJavaType)

class OnnxTensor private constructor(private val payload: Any, private val ti: TensorInfo) {
    // Use-after-close detector: in real ORT a closed Result's tensors are freed memory.
    @Volatile var closed = false
    val floatBuffer: FloatBuffer get() = FloatBuffer.wrap(payload as FloatArray)
    val floats: FloatArray get() = payload as FloatArray
    val ints: IntArray get() = payload as IntArray
    val longs: LongArray get() = payload as LongArray
    val info: Any get() = ti
    fun close() { closed = true }
    companion object {
        private fun takeF(b: FloatBuffer): FloatArray { b.mark(); val a = FloatArray(b.remaining()); b.get(a); b.reset(); return a }
        private fun takeI(b: IntBuffer): IntArray { b.mark(); val a = IntArray(b.remaining()); b.get(a); b.reset(); return a }
        private fun takeL(b: LongBuffer): LongArray { b.mark(); val a = LongArray(b.remaining()); b.get(a); b.reset(); return a }
        @JvmStatic fun createTensor(env: OrtEnvironment, b: FloatBuffer, shape: LongArray): OnnxTensor =
            OnnxTensor(takeF(b), TensorInfo(shape, OnnxJavaType.FLOAT))
        @JvmStatic fun createTensor(env: OrtEnvironment, b: IntBuffer, shape: LongArray): OnnxTensor =
            OnnxTensor(takeI(b), TensorInfo(shape, OnnxJavaType.INT32))
        @JvmStatic fun createTensor(env: OrtEnvironment, b: LongBuffer, shape: LongArray): OnnxTensor =
            OnnxTensor(takeL(b), TensorInfo(shape, OnnxJavaType.INT64))
    }
}

class OrtEnvironment {
    fun createSession(path: String, opts: OrtSession.SessionOptions): OrtSession = OrtSession()
}

class OrtSession(
    val inputNames: MutableSet<String> = mutableSetOf(),
    val outputNames: MutableSet<String> = mutableSetOf(),
    var handler: ((Map<String, OnnxTensor>) -> Map<String, OnnxTensor>)? = null,
    val declaredInfo: Map<String, TensorInfo> = emptyMap(),
) {
    class SessionOptions { fun addQnn(opts: Map<String, String>) {} }
    class NodeInfo(val info: Any = TensorInfo(LongArray(0), OnnxJavaType.FLOAT))
    class Result(private val values: Map<String, OnnxTensor>) : Iterable<Map.Entry<String, OnnxTensor>>, AutoCloseable {
        override fun iterator(): Iterator<Map.Entry<String, OnnxTensor>> = values.entries.iterator()
        operator fun get(name: String): java.util.Optional<OnnxTensor> =
            java.util.Optional.ofNullable(values[name])
        override fun close() { values.values.forEach { it.closed = true } }
    }
    fun getInputInfo(): Map<String, NodeInfo> =
        inputNames.associateWith { NodeInfo(declaredInfo[it] ?: TensorInfo(LongArray(0), OnnxJavaType.FLOAT)) }
    fun run(inputs: Map<String, OnnxTensor>): Result = Result(handler?.invoke(inputs) ?: emptyMap())
    fun close() {}
}
