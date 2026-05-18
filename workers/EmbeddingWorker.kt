package com.example.myapplication.workers

import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.example.myapplication.AudioProcess
import com.example.myapplication.models.BatchItem
import com.example.myapplication.models.BatchManifest
import com.example.myapplication.utils.ManifestIO
import com.example.myapplication.StageRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class EmbeddingWorker(appContext: android.content.Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        private const val TAG = "EmbeddingWorker"
        const val KEY_BATCH_DIR = "batchDir"
        const val KEY_WAV_PATH = "wavPath"
        const val KEY_MANIFEST_PATH = "manifestFilePath"
        const val KEY_EMBEDDING_DIR = "embeddingDir"
        const val KEY_TRIGGER_DP_STAGE = "triggerDPStage"
    }
private val audioProcess by lazy { AudioProcess(applicationContext) }

private fun appendDebug(context: android.content.Context, text: String) {
    try {
        val f = File(context.filesDir, "embedding_debug.txt")
        f.appendText("${System.currentTimeMillis()}: $text\n")
    } catch (e: Exception) {
        Log.w(TAG, "appendDebug failed", e)
    }
}

override suspend fun doWork(): Result {
    // 初始化 ManifestIO
    try {
        ManifestIO.init(applicationContext)
    } catch (e: Exception) {
        Log.w(TAG, "ManifestIO.init warning: ${e.message}")
    }

    val batchDirPath = try { inputData.getString(KEY_BATCH_DIR) } catch (_: Exception) { null }
    val wavPath = try { inputData.getString(KEY_WAV_PATH) } catch (_: Exception) { null }
    val manifestPath = try { inputData.getString(KEY_MANIFEST_PATH) } catch (_: Exception) { null }
    val embeddingDirPath = try { inputData.getString(KEY_EMBEDDING_DIR) ?: "" } catch (_: Exception) { "" }
    val triggerDp = try { inputData.getBoolean(KEY_TRIGGER_DP_STAGE, false) } catch (_: Exception) { false }

    val inputsLog = "doWork inputs: batchDir=$batchDirPath wavPath=$wavPath manifest=$manifestPath embeddingDir=$embeddingDirPath triggerDp=$triggerDp"
    Log.i(TAG, inputsLog)
    appendDebug(applicationContext, inputsLog)

    return if (!wavPath.isNullOrEmpty() && !manifestPath.isNullOrEmpty()) {
        if (!batchDirPath.isNullOrEmpty()) {
            val warn = "WARN: single-file request also has batchDir set (will ignore batchDir) batchDir=$batchDirPath wavPath=$wavPath"
            Log.w(TAG, warn)
            appendDebug(applicationContext, warn)
        }

        try {
        } catch (e: Exception) {
            Log.w(TAG, "ensure model load failed: ${e.message}")
            appendDebug(applicationContext, "ensure model load failed: ${e.message}")
        }

        processSingleFile(wavPath, manifestPath, embeddingDirPath)
    } else if (!batchDirPath.isNullOrEmpty()) {
        processBatch(batchDirPath)
    } else {
        val err = "缺少输入参数：既没有 wavPath/manifest 也没有 batchDir"
        Log.e(TAG, err)
        appendDebug(applicationContext, err)
        Result.failure()
    }
}

private suspend fun processBatch(batchDirPath: String): Result {
    val batchDir = File(batchDirPath)
    val manifestFile = File(batchDir, "manifest.json")
    Log.i(TAG, "processBatch manifest.exists=${manifestFile.exists()} path=${manifestFile.absolutePath}")
    appendDebug(applicationContext, "processBatch manifest.exists=${manifestFile.exists()} path=${manifestFile.absolutePath}")
    if (!manifestFile.exists()) {
        Log.e(TAG, "批次 manifest 不存在: ${manifestFile.absolutePath}")
        appendDebug(applicationContext, "manifest not found: ${manifestFile.absolutePath}")
        return Result.failure()
    }

    Log.i(TAG, "批量 EmbeddingWorker 启动，batchDir: $batchDirPath")
    appendDebug(applicationContext, "processBatch start for $batchDirPath")

    val manifest: BatchManifest = try {
        ManifestIO.readManifestSuspend(manifestFile)
    } catch (e: Exception) {
        Log.e(TAG, "读取 manifest.json 失败", e)
        appendDebug(applicationContext, "readManifest failed: ${e.message}")
        return Result.failure()
    }

    var hadError = false

    for (orig in manifest.items) {
        var item = orig
        val latestManifest = try { ManifestIO.readManifestSuspend(manifestFile) } catch (e: Exception) {
            Log.w(TAG, "读取最新 manifest 失败，使用原始快照继续: ${e.message}")
            null
        }
        if (latestManifest != null) {
            val refreshed = latestManifest.items.find { it.id == item.id }
            if (refreshed != null) item = refreshed
        }

        if (item.status == "embedding_done") {
            Log.i(TAG, "跳过已完成 embedding 项，wavPath=${item.wavPath}, id=${item.id}")
            continue
        }

        if (item.id.isNullOrEmpty()) {
            val genId = UUID.randomUUID().toString()
            item = item.copy(id = genId)
            try {
                ManifestIO.updateItemSuspend(manifestFile, item)
            } catch (e: Exception) {
                Log.w(TAG, "写入生成 id 失败: ${e.message}")
                appendDebug(applicationContext, "updateItem set id failed: ${e.message}")
            }
        }

        val latestAfterId = try { ManifestIO.readManifestSuspend(manifestFile) } catch (e: Exception) { null }
        if (latestAfterId != null) {
            val refreshed2 = latestAfterId.items.find { it.id == item.id }
            if (refreshed2 != null) item = refreshed2
        }

        val processing = item.copy(status = "processing", error = null, updatedAt = System.currentTimeMillis())
        try { ManifestIO.updateItemSuspend(manifestFile, processing) } catch (ex: Exception) {
            Log.w(TAG, "更新状态 processing 失败，wavPath=${item.wavPath}: ${ex.message}")
        }

        if (item.wavPath.isNullOrEmpty()) {
            Log.e(TAG, "item.wavPath 为空，跳过 id=${item.id}")
            safeUpdateItemStatus(manifestFile, item, "embedding_failed", "wavPath empty")
            hadError = true
            continue
        }

        val wavFile = File(item.wavPath)
        if (!wavFile.exists()) {
            Log.e(TAG, "WAV 文件不存在，跳过：${item.wavPath}")
            safeUpdateItemStatus(manifestFile, item, "embedding_failed", "文件不存在")
            hadError = true
            continue
        }

        val embeddingDir = File(batchDir, "embeddings")
        if (!embeddingDir.exists()) embeddingDir.mkdirs()
        val embOut = File(embeddingDir, "${item.id}.bin")

        try {
            val latestPreProc = try { ManifestIO.readManifestSuspend(manifestFile) } catch (_: Exception) { null }
            if (latestPreProc != null) {
                val current = latestPreProc.items.find { it.id == item.id }
                if (current != null) {
                    if (current.status == "embedding_done") {
                        Log.i(TAG, "在开始处理前该项已被其他 worker 完成，跳过: id=${item.id}")
                        continue
                    }
                    if (!current.embeddingPath.isNullOrEmpty()) {
                        Log.i(TAG, "在开始处理前该项已有 embeddingPath，跳过: id=${item.id}")
                        continue
                    }
                }
            }

            val resultPath = withContext(Dispatchers.IO) {
                audioProcess.processAudio(wavFile.absolutePath, embOut.absolutePath)
            }

            if (!File(resultPath).exists()) {
                throw IOException("生成的 embedding 文件不存在：$resultPath")
            }

            val done = item.copy(
                embeddingPath = resultPath,
                status = "embedding_done",
                error = null,
                updatedAt = System.currentTimeMillis()
            )
            try {
                ManifestIO.updateItemSuspend(manifestFile, done)
            } catch (e: Exception) {
                Log.e(TAG, "写入 embedding_done 失败，id=${item.id}", e)
                appendDebug(applicationContext, "updateItem embedding_done failed: ${e.message}")
                return Result.failure()
            }

            Log.i(TAG, "成功生成 embedding，路径：$resultPath, id=${item.id}")
            appendDebug(applicationContext, "embedding success id=${item.id} path=$resultPath")
        } catch (e: Exception) {
            Log.e(TAG, "生成 embedding 失败，wavPath=${item.wavPath}", e)
            appendDebug(applicationContext, "embedding failed for ${item.wavPath}: ${e.message}")

            if (isNonRecoverable(e)) {
                safeUpdateItemStatus(manifestFile, item, "embedding_failed", e.message)
                Log.i(TAG, "不可恢复错误，标记该项为 failed 并继续下一个: ${item.wavPath}")
                appendDebug(applicationContext, "non-recoverable error for ${item.wavPath}: ${e.message}")
                hadError = true
                continue
            }

            if (isTransient(e)) {
                safeUpdateItemStatus(manifestFile, item, "embedding_failed", e.message)
                Log.i(TAG, "遇到临时性错误（IO），请求重试: ${e.message}")
                appendDebug(applicationContext, "transient error, retry requested for ${item.wavPath}: ${e.message}")
                return Result.retry()
            } else {
                safeUpdateItemStatus(manifestFile, item, "embedding_failed", e.message)
                hadError = true
            }
        }
    }

    val triggerDp = try { inputData.getBoolean(KEY_TRIGGER_DP_STAGE, false) } catch (e: Exception) {
        Log.w(TAG, "读取 KEY_TRIGGER_DP_STAGE 失败，使用默认 false: ${e.message}")
        false
    }

    if (triggerDp) {
        try {
            StageRunner.enqueueDPStage(applicationContext, batchDir)
            appendDebug(applicationContext, "enqueueDPStage called for ${batchDir.absolutePath}")
            Log.i(TAG, "enqueueDPStage called for ${batchDir.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "enqueueDPStage invocation failed: ${e.message}")
            appendDebug(applicationContext, "enqueueDPStage failed: ${e.message}")
        }
    } else {
        Log.i(TAG, "跳过 enqueueDPStage，因为 KEY_TRIGGER_DP_STAGE=false")
        appendDebug(applicationContext, "skip enqueueDPStage for ${batchDir.absolutePath}")
    }

    if (hadError) {
        Log.w(TAG, "部分项 embedding 失败，已标记。返回 success 以允许后续 DP/TTS 继续执行。")
        appendDebug(applicationContext, "processBatch finished with partial errors")
        return Result.success()
    } else {
        appendDebug(applicationContext, "processBatch finished success")
        return Result.success()
    }
}

private suspend fun processSingleFile(wavPath: String, manifestPath: String, embeddingDirPath: String): Result {
    Log.i(TAG, "单文件 EmbeddingWorker 启动，wavPath=$wavPath, manifestFile=$manifestPath, embeddingDir=$embeddingDirPath")
    appendDebug(applicationContext, "processSingleFile start wav=$wavPath manifest=$manifestPath")
    val manifestFile = File(manifestPath)

    var item = BatchItem(
        id = UUID.randomUUID().toString(),
        wavPath = wavPath,
        embeddingPath = null,
        status = "processing",
        error = null,
        updatedAt = System.currentTimeMillis(),
        dpPaths = mutableMapOf(),
        ttsOutputPath = null
    )

    try {
        ManifestIO.updateItemSuspend(manifestFile, item)
    } catch (e: Exception) {
        Log.w(TAG, "更新 manifest 状态为 processing 失败: ${e.message}")
        appendDebug(applicationContext, "updateItem processing failed: ${e.message}")
    }

    try {
        if (wavPath.isNullOrEmpty()) {
            Log.e(TAG, "传入 wavPath 为空")
            appendDebug(applicationContext, "wavPath empty in single-file")
            safeUpdateItemStatus(manifestFile, item, "embedding_failed", "wavPath empty")
            return Result.failure()
        }

        val wavFile = File(wavPath)
        if (!wavFile.exists()) {
            Log.e(TAG, "WAV 文件不存在: $wavPath")
            safeUpdateItemStatus(manifestFile, item, "embedding_failed", "WAV 文件不存在")
            return Result.failure()
        }

        val embedDir = if (embeddingDirPath.isNotEmpty()) File(embeddingDirPath) else File(wavFile.parentFile ?: applicationContext.filesDir, "embeddings")
        if (!embedDir.exists()) embedDir.mkdirs()
        val outFile = File(embedDir, "${item.id}.bin")

        val outPath = try {
            withContext(Dispatchers.IO) {
                audioProcess.processAudio(wavFile.absolutePath, outFile.absolutePath)
            }
        } catch (e: Exception) {
            val msg = "processAudio threw: ${e::class.java.simpleName}: ${e.message}"
            Log.e(TAG, msg, e)
            appendDebug(applicationContext, msg)
            appendDebug(applicationContext, "stack: ${android.util.Log.getStackTraceString(e)}")
            if (isTransient(e)) {
                safeUpdateItemStatus(manifestFile, item, "embedding_failed", e.message)
                appendDebug(applicationContext, "processAudio transient -> retry")
                return Result.retry()
            } else {
                safeUpdateItemStatus(manifestFile, item, "embedding_failed", e.message)
                appendDebug(applicationContext, "processAudio non-recoverable -> fail")
                return Result.failure()
            }
        }

        if (!File(outPath).exists()) {
            throw IOException("生成的 embedding 文件不存在，路径：$outPath")
        }

        item = item.copy(embeddingPath = outPath, status = "embedding_done", updatedAt = System.currentTimeMillis())
        try {
            ManifestIO.updateItemSuspend(manifestFile, item)
        } catch (e: Exception) {
            Log.e(TAG, "写入单文件 embedding_done 失败: ${e.message}", e)
            appendDebug(applicationContext, "updateItem embedding_done failed: ${e.message}")
            return Result.failure()
        }

        Log.i(TAG, "单文件 embedding 成功: $outPath")
        appendDebug(applicationContext, "single embedding success id=${item.id} path=$outPath")

        val batchDir = manifestFile.parentFile
        val triggerDp = try { inputData.getBoolean(KEY_TRIGGER_DP_STAGE, false) } catch (e: Exception) {
            Log.w(TAG, "读取 KEY_TRIGGER_DP_STAGE 失败，使用默认 false: ${e.message}")
            false
        }

        if (batchDir != null && batchDir.exists() && triggerDp) {
            try {
                StageRunner.enqueueDPStage(applicationContext, batchDir)
                appendDebug(applicationContext, "enqueueDPStage called for single-file batch ${batchDir.absolutePath}")
            } catch (e: Exception) {
                appendDebug(applicationContext, "enqueueDPStage failed for single-file: ${e.message}")
            }
        } else {
            appendDebug(applicationContext, "skip enqueueDPStage for single-file batch ${batchDir?.absolutePath}")
            Log.i(TAG, "跳过 enqueueDPStage (single-file) 因为 KEY_TRIGGER_DP_STAGE=false 或 batchDir 不存在")
        }

        return Result.success()
    } catch (e: Exception) {
        Log.e(TAG, "单文件生成 embedding 失败：$wavPath", e)
        appendDebug(applicationContext, "processSingleFile failed: ${e.message}")

        if (isNonRecoverable(e)) {
            safeUpdateItemStatus(manifestFile, item, "embedding_failed", e.message)
            appendDebug(applicationContext, "single-file non-recoverable error, failing: ${e.message}")
            return Result.failure()
        }

        if (isTransient(e)) {
            safeUpdateItemStatus(manifestFile, item, "embedding_failed", e.message)
            return Result.retry()
        } else {
            safeUpdateItemStatus(manifestFile, item, "embedding_failed", e.message)
            return Result.failure()
        }
    }
}

private suspend fun safeUpdateItemStatus(manifestFile: File, item: BatchItem, status: String, error: String?) {
    try {
        val updated = item.copy(status = status, error = error, updatedAt = System.currentTimeMillis())
        ManifestIO.updateItemSuspend(manifestFile, updated)
        Log.i(TAG, "更新 manifest 状态: wavPath=${item.wavPath}, status=$status, error=$error")
        appendDebug(applicationContext, "updateItem status=$status id=${item.id} error=$error")
    } catch (ex: Exception) {
        Log.e(TAG, "更新 manifest 状态失败: ${ex.message}")
        appendDebug(applicationContext, "updateItem failed: ${ex.message}")
    }
}

private fun isTransient(e: Exception): Boolean {
    if (isNonRecoverable(e)) return false
    return when (e) {
        is IOException -> true
        else -> false
    }
}

private fun isNonRecoverable(e: Exception): Boolean {
    val msg = (e.message ?: "").lowercase()
    if (msg.isEmpty()) return false
    val nonRecoverableKeywords = listOf(
        "非标准", "不标准", "非标准 wav", "格式不支持", "unsupported format",
        "invalid wav", "invalid format", "格式不兼容", "无法解析", "解析失败",
        "格式错误", "读取wav失败", "read wav failed", "not a wav", "not wav"
    )
    for (k in nonRecoverableKeywords) {
        if (msg.contains(k)) return true
    }
    return false
}
}