package com.example.myapplication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.sign

class DifferentialPrivacy(
    var epsilon: Double = 2.0,
    var delta: Double = 1e-5,
    var sensitivity: Double = 1.0,
    var candidateCount: Int = 30,
    var radius: Double = 0.5,
    seed: Long? = null,
    var noNoise: Boolean = false   //true 表示不加噪声
) {
    private val rng = if (seed != null) {
        java.util.Random(seed)
    } else {
        java.util.Random()  //使用系统时间作为种子
    }

    private fun l2Norm(x: FloatArray): Double {
        var s = 0.0
        for (v in x) s += v.toDouble() * v.toDouble()
        return kotlin.math.sqrt(s)
    }

    private fun subtract(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(a.size)
        for (i in a.indices) out[i] = a[i] - b[i]
        return out
    }

    private fun qualityFunc(center: FloatArray, candidate: FloatArray): Double {
        val diff = subtract(center, candidate)
        return -l2Norm(diff)
    }

    fun generateCandidates(center: FloatArray, numCandidates: Int = candidateCount, radiusParam: Double? = null): Array<FloatArray> {
        val r = radiusParam ?: this.radius
        val dims = center.size
        val out = Array(numCandidates) { FloatArray(dims) }
        for (i in 0 until numCandidates) {
            val cand = FloatArray(dims)
            for (d in 0 until dims) {
                val u = (rng.nextDouble() * 2.0 * r) - r
                cand[d] = (center[d] + u).toFloat()
            }
            out[i] = cand
        }
        return out
    }

    fun exponentialMechanism(embedding: FloatArray, candidates: Array<FloatArray>): FloatArray {
        if (noNoise) {
            return embedding.copyOf() // 不加噪声，直接返回原 embedding
        }
        val n = candidates.size
        val scores = DoubleArray(n)
        for (i in 0 until n) scores[i] = qualityFunc(embedding, candidates[i])
        val scale = epsilon / (2.0 * sensitivity)
        val scaled = DoubleArray(n)
        var maxScaled = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            scaled[i] = scores[i] * scale
            if (scaled[i] > maxScaled) maxScaled = scaled[i]
        }
        val expScores = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            val v = kotlin.math.exp(scaled[i] - maxScaled)
            expScores[i] = v
            sum += v
        }
        var r = rng.nextDouble() * sum
        for (i in 0 until n) {
            r -= expScores[i]
            if (r <= 0.0) return candidates[i]
        }
        return candidates.last()
    }

    private fun sampleLaplace(scale: Double): Double {
        val u = rng.nextDouble() - 0.5
        return -scale * sign(u) * ln(1.0 - 2.0 * kotlin.math.abs(u))
    }

    fun laplaceMechanism(embedding: FloatArray): FloatArray {
        if (noNoise) {
            return embedding.copyOf() // 不加噪声，直接返回原 embedding
        }
        val scale = sensitivity / epsilon
        val out = FloatArray(embedding.size)
        for (i in embedding.indices) {
            val noise = sampleLaplace(scale)
            out[i] = (embedding[i] + noise).toFloat()
        }
        return out
    }

    fun gaussianMechanism(embedding: FloatArray): FloatArray {
        if (noNoise) {
            return embedding.copyOf()
        }
        val sigma = kotlin.math.sqrt(2.0 * ln(1.25 / delta)) * sensitivity / epsilon
        val out = FloatArray(embedding.size)
        for (i in embedding.indices) {
            val noise = rng.nextGaussian() * sigma
            out[i] = (embedding[i] + noise).toFloat()
        }
        return out
    }

    fun processEmbedding(embedding: FloatArray): Triple<FloatArray, FloatArray, FloatArray> {
        val candidates = generateCandidates(embedding)
        val expEmb = exponentialMechanism(embedding, candidates)
        val lapEmb = laplaceMechanism(embedding)
        val gaussEmb = gaussianMechanism(embedding)
        return Triple(expEmb, lapEmb, gaussEmb)
    }

    suspend fun processEmbeddingAsync(embedding: FloatArray): Triple<FloatArray, FloatArray, FloatArray> {
        return withContext(Dispatchers.Default) {
            processEmbedding(embedding)
        }
    }

    fun saveEmbeddingToFile(file: File, embedding: FloatArray) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            val bb = ByteBuffer.allocate(4 * embedding.size).order(ByteOrder.LITTLE_ENDIAN)
            for (v in embedding) bb.putFloat(v)
            fos.write(bb.array())
        }
    }

    fun loadEmbeddingFromFile(file: File): FloatArray {
        val size = (file.length() / 4).toInt()
        val arr = FloatArray(size)
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(size * 4)
            var read = 0
            while (read < buffer.size) {
                val r = fis.read(buffer, read, buffer.size - read)
                if (r < 0) break
                read += r
            }
            val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until size) arr[i] = bb.getFloat(i * 4)
        }
        return arr
    }
}