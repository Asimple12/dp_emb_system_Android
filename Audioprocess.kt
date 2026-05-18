package com.example.myapplication

import android.content.Context
import android.util.Log
import be.tarsos.dsp.mfcc.MFCC
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.sqrt

class AudioProcess(private val context: Context) {

    companion object {
        private const val TAG = "AudioEmbeddingExtractor"
        private const val SAMPLE_RATE = 16000
        private const val N_MELS = 80
        private const val N_FFT = 1024
        private const val HOP_LENGTH = 256
        private const val WIN_LENGTH = 1024
    }

    data class EmbeddingMetadata(
        val sampleRate: Int,
        val hopLength: Int,
        val winLength: Int,
        val nMels: Int,
        val melFrameCount: Int,
        val embeddingDim: Int,
        val speakerId: String? = null,
        val inputText: String? = null,
        val modelInfo: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val model: Module by lazy {
        Module.load(assetFilePath("light_ecapa_for_andriod_new128.pt"))
    }

    //从wav文件读取音频，计算mel spectrogram，推理embedding并保存
    fun processAudio(wavFilePath: String, embeddingSavePath: String): String {
        Log.i(TAG, "开始读取wav文件...")
        val pcmData = readWavFileDirectly(wavFilePath) ?: throw IOException("读取wav失败: $wavFilePath")

        Log.i(TAG, "开始计算Mel谱...")
        val melSpectrogram = computeMelSpectrogram(pcmData)

        Log.i(TAG, "开始模型推理...")
        val embedding = inferEmbedding(melSpectrogram)

        val embeddingFile = File(embeddingSavePath)
        embeddingFile.parentFile?.mkdirs()

        try {
            saveEmbeddingFile(embedding, embeddingFile.absolutePath)
            Log.i(TAG, "Embedding 已保存至 ${embeddingFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "保存 embedding 失败", e)
            throw e
        }

        // 写 metadata
        val embeddingDir = embeddingFile.parentFile ?: File(context.filesDir, "embeddings")
        val metadata = EmbeddingMetadata(
            sampleRate = SAMPLE_RATE,
            hopLength = HOP_LENGTH,
            winLength = WIN_LENGTH,
            nMels = N_MELS,
            melFrameCount = melSpectrogram[0].size,
            embeddingDim = embedding.size,
            modelInfo = "light_ecapa_for_andriod.pt @ v1"
        )
        try {
            val metaFile = File(embeddingDir, embeddingFile.name + ".meta.json")
            metaFile.writeText(
                JSONObject().apply {
                    put("sampleRate", metadata.sampleRate)
                    put("hopLength", metadata.hopLength)
                    put("winLength", metadata.winLength)
                    put("nMels", metadata.nMels)
                    put("melFrameCount", metadata.melFrameCount)
                    put("embeddingDim", metadata.embeddingDim)
                    put("speakerId", metadata.speakerId)
                    put("inputText", metadata.inputText)
                    put("modelInfo", metadata.modelInfo)
                    put("timestamp", metadata.timestamp)
                }.toString(2)
            )
            Log.i(TAG, "Metadata 已保存至 ${metaFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "保存 metadata 失败", e)
        }

        return embeddingFile.absolutePath
    }

    fun readWavFileDirectly(wavPath: String): FloatArray? {
        val wavFile = File(wavPath)
        if (!wavFile.exists()) {
            Log.e(TAG, "WAV 文件不存在: $wavPath")
            return null
        }

        try {
            FileInputStream(wavFile).use { fis ->
                val header = ByteArray(12)
                if (fis.read(header) != 12) {
                    Log.e(TAG, "WAV 文件太短，无法读取RIFF头")
                    return null
                }
                if (!header.copyOfRange(0, 4).toString(Charsets.US_ASCII).equals("RIFF", true) ||
                    !header.copyOfRange(8, 12).toString(Charsets.US_ASCII).equals("WAVE", true)
                ) {
                    Log.e(TAG, "非标准 WAV 文件")
                    return null
                }

                var fmtChunkFound = false
                var dataChunkSize = 0
                var audioFormat = 0
                var channels = 1
                var bitsPerSample = 0

                while (true) {
                    val chunkHeader = ByteArray(8)
                    if (fis.read(chunkHeader) != 8) break
                    val chunkId = chunkHeader.copyOfRange(0, 4).toString(Charsets.US_ASCII)
                    val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int

                    when (chunkId) {
                        "fmt " -> {
                            fmtChunkFound = true
                            val fmtData = ByteArray(chunkSize)
                            if (fis.read(fmtData) != chunkSize) break
                            val fmtBuffer = ByteBuffer.wrap(fmtData).order(ByteOrder.LITTLE_ENDIAN)
                            audioFormat = fmtBuffer.short.toInt()
                            channels = fmtBuffer.short.toInt()
                            fmtBuffer.int
                            fmtBuffer.int
                            fmtBuffer.short
                            bitsPerSample = fmtBuffer.short.toInt()
                        }
                        "data" -> {
                            dataChunkSize = chunkSize
                            break
                        }
                        else -> {
                            fis.skip(chunkSize.toLong())
                        }
                    }
                }

                if (!fmtChunkFound || dataChunkSize == 0) {
                    Log.e(TAG, "WAV fmt 或 data chunk 不完整")
                    return null
                }

                if (audioFormat != 1 || bitsPerSample != 16) {
                    Log.e(TAG, "只支持 PCM 16-bit WAV，当前格式 audioFormat=$audioFormat bits=$bitsPerSample")
                    return null
                }

                val sampleCount = dataChunkSize / 2
                val floats = FloatArray(sampleCount)
                val buffer = ByteArray(2)

                for (i in 0 until sampleCount) {
                    if (fis.read(buffer) != 2) break
                    val sample = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).short
                    floats[i] = sample / 32768f
                }

                Log.i(TAG, "读取 WAV 成功，采样点数=${floats.size}, 声道=$channels")
                return floats
            }
        } catch (e: IOException) {
            Log.e(TAG, "读取 WAV 出错", e)
            return null
        }
    }

    private fun computeMelSpectrogram(waveform: FloatArray): Array<FloatArray> {
        Log.i(TAG, "computeMelSpectrogram frames loop start")
        val mfcc = MFCC(
            WIN_LENGTH,
            SAMPLE_RATE.toFloat(),
            N_MELS,
            80,
            0f,
            SAMPLE_RATE.toFloat() / 2f
        )
        mfcc.calculateFilterBanks()

        val frames = mutableListOf<FloatArray>()
        var frameStart = 0
        while (frameStart + WIN_LENGTH <= waveform.size) {
            val frame = FloatArray(WIN_LENGTH)
            System.arraycopy(waveform, frameStart, frame, 0, WIN_LENGTH)

            val spectrum = mfcc.magnitudeSpectrum(frame)
            val melOutput = mfcc.melFilter(spectrum, mfcc.getCenterFrequencies())
            val melLog = FloatArray(melOutput.size) { idx ->
                ln(melOutput[idx].coerceAtLeast(1e-8f).toDouble()).toFloat()
            }
            frames.add(melLog)

            frameStart += HOP_LENGTH
        }

        val nFrames = frames.size
        if (nFrames == 0) {
            throw IllegalArgumentException("输入音频太短，无法生成任何帧，请检查采样率/窗长/跳帧设置。")
        }

        val melSpectrogram = Array(N_MELS) { FloatArray(nFrames) }
        for (frameIndex in frames.indices) {
            val melFrame = frames[frameIndex]
            for (m in 0 until N_MELS) {
                melSpectrogram[m][frameIndex] = melFrame[m]
            }
        }
        return melSpectrogram
    }

    private fun inferEmbedding(melSpectrogram: Array<FloatArray>): FloatArray {
        val nFrames = melSpectrogram[0].size
        val inputBuffer = FloatArray(N_MELS * nFrames)
        for (m in 0 until N_MELS) {
            System.arraycopy(melSpectrogram[m], 0, inputBuffer, m * nFrames, nFrames)
        }
        val inputTensor = Tensor.fromBlob(inputBuffer, longArrayOf(1, N_MELS.toLong(), nFrames.toLong()))
        val outputTensor = model.forward(IValue.from(inputTensor)).toTensor()
        val embedding = outputTensor.dataAsFloatArray
        val norm = sqrt(embedding.map { it * it }.sum()) + 1e-9f
        return embedding.map { it / norm }.toFloatArray()
    }

    private fun saveEmbeddingFile(embedding: FloatArray, outPath: String) {
        try {
            val file = File(outPath)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { fos ->
                val buffer = ByteBuffer.allocate(embedding.size * Float.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                embedding.forEach { buffer.putFloat(it) }
                fos.write(buffer.array())
                fos.flush()
            }
            Log.i(TAG, "Embedding已保存至 $outPath")
        } catch (e: Exception) {
            Log.e(TAG, "保存embedding失败", e)
            throw e
        }
    }

    private fun assetFilePath(assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) {
            return file.absolutePath
        }
        context.assets.open(assetName).use { input ->
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
                output.flush()
                return file.absolutePath
            }
        }
    }

}