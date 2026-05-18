package com.example.myapplication.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myapplication.YourTTSInference
import com.example.myapplication.utils.ConcurrencyManager
import com.example.myapplication.utils.ManifestIO
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader
import java.io.FileInputStream

class TTSWorker(appContext: Context, params: WorkerParameters)
    : CoroutineWorker(appContext, params) {
    companion object {
        private const val TAG = "TTSWorker"

        const val KEY_BATCH_DIR= "batchDir"
        const val KEY_ITEM_ID    = "itemId"
        const val KEY_SERVER_URL = "serverUrl"
        const val KEY_TTS_TEXT   = "KEY_TTS_TEXT"

        private const val CSV_PATH= "./metadata.csv"
        private const val DEFAULT_SERVER_URL = "http://10.0.2.2:5000/synthesize"
        private const val LANGUAGE           = "en"
        private const val DP_KEY             = "gauss"
    }

    override suspend fun doWork(): Result {

        val batchDirPath = inputData.getString(KEY_BATCH_DIR) ?: run {
            Log.e(TAG, "missing batchDir")
            return Result.failure()
        }
        val serverUrl = inputData.getString(KEY_SERVER_URL) ?: DEFAULT_SERVER_URL
        val itemId    = inputData.getString(KEY_ITEM_ID)

        val manifestFile = File(batchDirPath, "manifest.json")

        //读取manifest
        val manifest = try {
            ManifestIO.readManifestSuspend(manifestFile)
        } catch (e: Exception) {
            Log.e(TAG, "read manifest failed", e)
            return Result.failure()
        }

        //加载CSV
        val csvFile = File(CSV_PATH)
        val csvMap  = loadCsvTextMap(csvFile)
        Log.i(TAG, "Loaded CSV mapping size=${csvMap.size} from ${csvFile.absolutePath}")

        //单item模式
        if (!itemId.isNullOrEmpty()) {
            val origItem = manifest.items.find { it.id == itemId } ?: run {
                Log.e(TAG, "item not found $itemId")
                return Result.failure()
            }
            val acquired = try {
                ConcurrencyManager.acquireTts()
            } catch (e: Exception) {
                Log.e(TAG, "acquireTts exception", e)
                false
            }
            if (!acquired) {
                Log.w(TAG, "failed to acquire TTS permit")
                return Result.retry()
            }

            try {
                val latestManifest = try {
                    ManifestIO.readManifestSuspend(manifestFile)
                } catch (e: Exception) { null }
                val item = latestManifest?.items?.find { it.id == origItem.id } ?: origItem
                if (item.status == "tts_done") {
                    Log.i(TAG, "skip already tts_done for item ${item.id}")
                    return Result.success()
                }
                if (!item.ttsOutputPath.isNullOrEmpty()) {
                    Log.i(TAG, "skip item with existing ttsOutputPath ${item.id}")
                    return Result.success()
                }
                val dpKey = inputData.getString(DP_KEY) ?: "gauss"
                val dpPath = item.dpPaths[dpKey]
                if (dpPath.isNullOrEmpty()) {
                    Log.e(TAG, "missing dp embedding for item ${item.id}")
                    val updated = item.copy(
                        status    = "failed",
                        error     = "missing dp embedding",
                        updatedAt = System.currentTimeMillis()
                    )
                    try { ManifestIO.updateItemSuspend(manifestFile, updated) } catch (_: Exception) {}
                    return Result.failure()
                }

                val emb: FloatArray? = YourTTSInference.loadEmbFromFile(File(dpPath))
                if (emb == null) {
                    Log.e(TAG, "emb load failed for item ${item.id}")
                    val updated = item.copy(
                        status    = "failed",
                        error     = "embedding failed",
                        updatedAt = System.currentTimeMillis()
                    )
                    try { ManifestIO.updateItemSuspend(manifestFile, updated) } catch (_: Exception) {}
                    return Result.failure()
                }

                var localTtsText = inputData.getString(KEY_TTS_TEXT)
                if (localTtsText.isNullOrBlank()) {
                    localTtsText = csvMap[item.id]
                }
                if (localTtsText.isNullOrBlank()) {
                    Log.e(TAG, "no tts text for item ${item.id}")
                    val updated = item.copy(
                        status    = "failed",
                        error     = "no tts text available",
                        updatedAt = System.currentTimeMillis()
                    )
                    try { ManifestIO.updateItemSuspend(manifestFile, updated) } catch (_: Exception) {}
                    return Result.failure()
                }

                Log.i(TAG, "Synthesizing item ${item.id} text preview: ${localTtsText.take(200)}")

                val outDir  = File(batchDirPath, "tts/${item.id}").also { it.mkdirs() }
                val outFile = File(outDir, "${item.id}.wav")

                // 调用合成
                try {
                    val ok = YourTTSInference.synthesize(
                        embFloats  = emb,
                        text       = localTtsText,
                        serverUrl  = serverUrl,
                        outputFile = outFile,
                        language   = LANGUAGE
                    )
                    Log.i(TAG, "item=${item.id}, embeddingPath=${item.embeddingPath}, dpPaths=${item.dpPaths}")
                    if (!ok) {
                        Log.e(TAG, "synthesize returned false for ${item.id}")
                        val updated = item.copy(
                            status    = "failed",
                            error     = "synthesize failed",
                            updatedAt = System.currentTimeMillis()
                        )
                        try { ManifestIO.updateItemSuspend(manifestFile, updated) } catch (_: Exception) {}
                        return Result.failure()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "synthesize failed for ${item.id}", e)
                    val updated = item.copy(
                        status    = "failed",
                        error     = "synthesize failed: ${e.message}",
                        updatedAt = System.currentTimeMillis()
                    )
                    try { ManifestIO.updateItemSuspend(manifestFile, updated) } catch (_: Exception) {}
                    return Result.failure()
                }

                if (!outFile.exists()) {
                    Log.e(TAG, "tts output not found in ${outDir.absolutePath} for item ${item.id}")
                    return Result.failure()
                }

                // 更新manifest
                val updated = item.copy(
                    ttsOutputPath = outFile.absolutePath,
                    status        = "tts_done",
                    error         = null,
                    updatedAt     = System.currentTimeMillis()
                )
                try {
                    ManifestIO.updateItemSuspend(manifestFile, updated)
                } catch (e: Exception) {
                    Log.e(TAG, "failed to update manifest for tts result of ${item.id}", e)
                    return Result.failure()
                }

                return Result.success()

            } catch (e: Exception) {
                Log.e(TAG, "TTS fail for $itemId", e)
                val updated = origItem.copy(
                    status    = "failed",
                    error     = "tts error: ${e.message}",
                    updatedAt = System.currentTimeMillis()
                )
                try { ManifestIO.updateItemSuspend(manifestFile, updated) } catch (_: Exception) {}
                return Result.failure()
            } finally {
                try { ConcurrencyManager.releaseTts() } catch (e: Exception) {
                    Log.w(TAG, "releaseTts failed", e)
                }
            }
        }

        val pendingSnapshot = manifest.items.filter {
            it.status == "dp_done" && it.ttsOutputPath.isNullOrEmpty()
        }

        if (pendingSnapshot.isEmpty()) {
            Log.i(TAG, "no pending items for TTS batch")
            return Result.success()
        }

        Log.i(TAG, "batch TTS：共${pendingSnapshot.size}个item待合成")

        val acquired = try {
            ConcurrencyManager.acquireTts()
        } catch (e: Exception) {
            Log.e(TAG, "acquireTts exception (batch)", e)
            false
        }
        if (!acquired) {
            Log.w(TAG, "failed to acquire TTS permit (batch)")
            return Result.retry()
        }

        try {
            for (orig in pendingSnapshot) {
                val latestManifest = try {
                    ManifestIO.readManifestSuspend(manifestFile)
                } catch (e: Exception) { null }
                val item = latestManifest?.items?.find { it.id == orig.id } ?: orig

                if (item.status == "tts_done") {
                    Log.i(TAG, "skip already tts_done for item ${item.id}")
                    continue
                }
                if (!item.ttsOutputPath.isNullOrEmpty()) {
                    Log.i(TAG, "skip item with existing ttsOutputPath ${item.id}")
                    continue
                }

                try {
                    val dpKey = inputData.getString(DP_KEY) ?: "gauss"
                    val dpPath = item.dpPaths[dpKey]
                    if (dpPath.isNullOrEmpty()) {
                        Log.e(TAG, "missing dp embedding for item ${item.id}")
                        val updated = item.copy(
                            status    = "failed",
                            error     = "missing dp embedding",
                            updatedAt = System.currentTimeMillis()
                        )
                        try { ManifestIO.updateItemSuspend(manifestFile, updated) } catch (_: Exception) {}
                        continue
                    }

                    val emb: FloatArray? = YourTTSInference.loadEmbFromFile(File(dpPath))
                    if (emb == null) {
                        Log.e(TAG, "emb load failed for item ${item.id}")
                        val updated = item.copy(
                            status    = "failed",
                            error     = "embedding failed: load returned null",
                            updatedAt = System.currentTimeMillis()
                        )
                        try { ManifestIO.updateItemSuspend(manifestFile, updated) } catch (_: Exception) {}
                        continue
                    }

                    var localTtsText = csvMap[item.id]
                    if (localTtsText.isNullOrBlank()) {
                        Log.e(TAG, "no tts text for item ${item.id}")
                        val updated = item.copy(
                            status    = "failed",
                            error     = "no tts text available",
                            updatedAt = System.currentTimeMillis()
                        )
                        try { ManifestIO.updateItemSuspend(manifestFile, updated) } catch (_: Exception) {}
                        continue
                    }

                    Log.i(TAG, "Synthesizing item ${item.id} with text (preview): ${localTtsText.take(200)}")

                    val outDir  = File(batchDirPath, "tts/${item.id}").also { it.mkdirs() }
                    val outFile = File(outDir, "${item.id}.wav")

                    try {
                        val ok = YourTTSInference.synthesize(
                            embFloats  = emb,
                            text       = localTtsText,
                            serverUrl  = serverUrl,
                            outputFile = outFile,
                            language   = LANGUAGE
                        )
                        Log.i(TAG, "item=${item.id}, embeddingPath=${item.embeddingPath}, dpPaths=${item.dpPaths}")
                        if (!ok) {
                            Log.e(TAG, "synthesize returned false for ${item.id}")
                            val updated = item.copy(
                                status    = "failed",
                                error     = "synthesize failed",
                                updatedAt = System.currentTimeMillis()
                            )
                            try { ManifestIO.updateItemSuspend(manifestFile, updated) } catch (_: Exception) {}
                            continue
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "synthesize failed for ${item.id}", e)
                        val updated = item.copy(
                            status    = "failed",
                            error     = "synthesize failed: ${e.message}",
                            updatedAt = System.currentTimeMillis()
                        )
                        try { ManifestIO.updateItemSuspend(manifestFile, updated) } catch (_: Exception) {}
                        continue
                    }

                    if (!outFile.exists()) {
                        Log.e(TAG, "tts output not found in ${outDir.absolutePath} for item ${item.id}")
                        continue
                    }

                    val updated = item.copy(
                        ttsOutputPath = outFile.absolutePath,
                        status        = "tts_done",
                        error         = null,
                        updatedAt     = System.currentTimeMillis()
                    )
                    try {
                        ManifestIO.updateItemSuspend(manifestFile, updated)
                    } catch (e: Exception) {
                        Log.e(TAG, "failed to update manifest for tts result of ${item.id}", e)
                        continue
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "TTS fail for ${item.id} in batch", e)
                    val updated = item.copy(
                        status    = "failed",
                        error     = "tts error: ${e.message}",
                        updatedAt = System.currentTimeMillis()
                    )
                    try { ManifestIO.updateItemSuspend(manifestFile, updated) } catch (_: Exception) {}
                }
            }

            return Result.success()

        } finally {
            try { ConcurrencyManager.releaseTts() } catch (e: Exception) {
                Log.w(TAG, "releaseTts failed (batch)", e)
            }
        }
    }

    fun loadCsvTextMap(csvFile: File): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (!csvFile.exists() || !csvFile.isFile) {
            Log.w(TAG, "CSV file not found: ${csvFile.absolutePath}")
            return map
        }

        try {
            BufferedReader(InputStreamReader(FileInputStream(csvFile), Charsets.UTF_8)).use { br ->
                var line = br.readLine()?.removePrefix("\uFEFF")
                if (line != null && line.lowercase().contains("uttid") && line.lowercase().contains("text")) {
                    line = br.readLine()
                }

                var lineNum = 1
                while (line != null) {
                    val s = line.trim()
                    if (s.isEmpty()) {
                        line = br.readLine()
                        lineNum++
                        continue
                    }

                    try {
                        val parts = parseCsvLine(s)

                        if (parts.size >= 5) {
                            val uttid = parts[0].trim()
                            val path = parts[1].trim()
                            val text = parts[4].trim().removeSurrounding("\"")

                            if (text.isNotBlank()) {
                                val uttKey = uttid.lowercase()
                                map[uttKey] = text
                                map["${uttKey}.wav"] = text
                                val partsU = uttid.split("_")
                                if (partsU.size >= 2) {
                                    val twoLevel = "${partsU[0]}/${partsU[1]}/${uttid}".lowercase()
                                    map[twoLevel] = text
                                    map["$twoLevel.wav"] = text
                                }
                                val pathNormalized = path.replaceFirst(Regex("^[A-Za-z]:\\\\+"), "")
                                    .replace("\\", "/").lowercase()
                                val basename = try {
                                    File(pathNormalized).name.lowercase()
                                } catch (_: Exception) {
                                    pathNormalized
                                }
                                if (basename.isNotBlank()) {
                                    map[basename] = text
                                    map[basename.substringBeforeLast(".")] = text
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse CSV line $lineNum: ${e.message}")
                    }

                    line = br.readLine()
                    lineNum++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load CSV: ${e.message}", e)
        }

        Log.i(TAG, "CSV解析完成，共${map.size}条")
        return map
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false

        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        result.add(current.toString())
        return result
    }
}