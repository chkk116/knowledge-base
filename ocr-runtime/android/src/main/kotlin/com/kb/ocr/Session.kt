package com.kb.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * 推理会话抽象：三个模型都是「单输入 float32 / 单输出 float32」。
 * 用包 ai.onnxruntime.*，桌面 JVM 版与 Android 版 API 完全一致，
 * 因此核心管线在两端零改动，只需替换 Gradle 依赖。
 */
interface OcrSession {
    /** @return (展平的 float 数据, 输出维度) */
    fun run(data: FloatArray, shape: LongArray): Pair<FloatArray, LongArray>
    fun close()
}

internal class OrtSessionAdapter(private val session: OrtSession) : OcrSession {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val inputName: String = session.inputNames.first()
    private val outputName: String = session.outputNames.first()

    override fun run(data: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
        tensor.use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                val out = res.get(outputName).orElseThrow { IllegalStateException("缺少输出 $outputName") }
                val value = out as? OnnxTensor
                    ?: throw IllegalStateException("输出不是 OnnxTensor: ${out.javaClass}")
                val dims = value.info.shape
                val buf = value.floatBuffer
                val arr = FloatArray(buf.remaining())
                buf.get(arr)
                return arr to dims
            }
        }
    }

    override fun close() = session.close()
}

object OrtSessions {
    /** 桌面 JVM：从文件路径加载。 */
    fun fromPath(path: String, threads: Int = 1): OcrSession {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return OrtSessionAdapter(OrtEnvironment.getEnvironment().createSession(path, opts))
    }

    /** Android / 任意环境：从模型字节加载（assets、文件、网络均可）。 */
    fun fromBytes(bytes: ByteArray, threads: Int = 1, useNnapi: Boolean = false): OcrSession {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (useNnapi) {
                // NNAPI 在部分设备上对动态 shape 兼容性差，默认关闭；由调用方按需开启
                @Suppress("SwallowedException")
                try { addNnapi() } catch (e: Throwable) { /* 设备不支持时回退 CPU */ }
            }
        }
        val direct = ByteBuffer.allocateDirect(bytes.size)
        direct.put(bytes); direct.flip()
        return OrtSessionAdapter(OrtEnvironment.getEnvironment().createSession(direct, opts))
    }
}
