package com.example.myapplication

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

object YourTTSInference {

    private const val TAG = "YourTTSInference"

    //HTTP客户端（复用单例）
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    //合成：发送emb+text到服务器，返回wav
    fun synthesize(
        embFloats: FloatArray,
        text: String,
        serverUrl: String,
        outputFile: File,
        language: String = "en"
    ): Boolean {
        require(embFloats.size == 128) {
            "emb维度错误：期望128，实际${embFloats.size}"
        }
        val byteBuffer = ByteBuffer
            .allocate(embFloats.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        embFloats.forEach { f: Float -> byteBuffer.putFloat(f) }
        val embBytes: ByteArray = byteBuffer.array()
        val mediaTypeBin= "application/octet-stream".toMediaType()
        val mediaTypeText = "text/plain".toMediaType()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "emb",
                "emb.bin",
                embBytes.toRequestBody(mediaTypeBin)
            )
            .addFormDataPart("text", text)
            .addFormDataPart("language", language)
            .build()
        val request = Request.Builder()
            .url(serverUrl)
            .post(requestBody)
            .build()
        //执行请求，保存wav
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "服务器错误：${response.code} - ${response.body?.string()}")
                    return@use false
                }
                val bodyBytes: ByteArray = response.body?.bytes()?: run {
                    Log.e(TAG, "响应体为空")
                    return@use false
                }
                outputFile.parentFile?.mkdirs()
                FileOutputStream(outputFile).use { fos ->
                    fos.write(bodyBytes)
                }
                Log.i(TAG, "合成成功，保存到：${outputFile.absolutePath}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP请求失败：${e.message}", e)
            false
        }
    }

    fun loadEmbFromFile(embFile: File): FloatArray? {
        return try {
            if (!embFile.exists() || !embFile.canRead()) {
                Log.e(TAG, "emb文件不存在或不可读：${embFile.absolutePath}")
                return null
            }

            val bytes = embFile.readBytes()
            if (bytes.size < 4 || bytes.size % 4 != 0) {
                Log.e(TAG, "emb文件大小非法：${embFile.absolutePath}, size=${bytes.size}")
                return null
            }

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(bytes.size / 4) { buf.getFloat() }
        } catch (e: Exception) {
            Log.e(TAG, "读取emb文件失败：${embFile.absolutePath}, ${e.message}", e)
            null
        }
    }
}