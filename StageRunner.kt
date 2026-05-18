package com.example.myapplication

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkContinuation
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.myapplication.models.BatchManifest
import com.example.myapplication.utils.ManifestIO
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

object StageRunner {
    private const val TAG = "StageRunner"

    fun enqueueDPStage(context: Context, batchDir: File) {
        try {
            val manifestFile = File(batchDir, "manifest.json")
            if (!manifestFile.exists()) {
                Log.w(TAG, "enqueueDPStage: manifest not found ${manifestFile.absolutePath}")
                return
            }
            val manifest = ManifestIO.readManifest(manifestFile)
            val wm = WorkManager.getInstance(context)
            manifest.items.forEach { item ->
                if (item.status == "embedding_done" && item.dpPaths.values.all { it.isNullOrEmpty() }) {
                    val data = workDataOf(
                        "batchDir" to batchDir.absolutePath,
                        "itemId" to item.id
                    )
                    val req = OneTimeWorkRequestBuilder<com.example.myapplication.workers.DPWorker>()
                        .setInputData(data)
                        .build()
                    val name = "${batchDir.name}_dp_${item.id}"
                    Log.i(TAG, "enqueueDPStage enqueuing $name")
                    wm.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, req)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "enqueueDPStage failed: ${e.message}")
        }
    }

    fun enqueueFullPipelineStructured(
        context: Context,
        batchDir: File,
        dpKey: String = "gauss",
        ttsText: String? = null): UUID {
        val tag = "StageRunner"
        val batchTag = "${tag}_${batchDir.name}"
        val manifestFile = File(batchDir, "manifest.json")

        val manifest: BatchManifest? = try {
            if (manifestFile.exists()) ManifestIO.readManifest(manifestFile) else null
        } catch (e: Exception) {
            Log.w(tag, "readManifest failed: ${e.message}")
            null
        }

        val embedData = workDataOf(
            com.example.myapplication.workers.EmbeddingWorker.KEY_BATCH_DIR to batchDir.absolutePath,
            com.example.myapplication.workers.EmbeddingWorker.KEY_TRIGGER_DP_STAGE to false
        )
        val embedReq = OneTimeWorkRequestBuilder<com.example.myapplication.workers.EmbeddingWorker>()
            .setInputData(embedData)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag("${tag}_embed_${batchDir.name}")
            .addTag(batchTag)
            .build()

        if (manifest == null || manifest.items.isEmpty()) {
            try {
                val wm = WorkManager.getInstance(context)
                wm.beginUniqueWork(
                    "${batchDir.name}_${System.currentTimeMillis()}_fullpipeline",
                    ExistingWorkPolicy.REPLACE,
                    embedReq
                ).enqueue()
                Log.i(tag, "enqueueFullPipelineStructured: manifest missing or empty; enqueued embedding only for ${batchDir.absolutePath}")
            } catch (e: Exception) {
                Log.e(tag, "enqueueFullPipelineStructured failed to enqueue embedding-only: ${e.message}", e)
            }
            return embedReq.id
        }

        try {
            val wm = WorkManager.getInstance(context)
            var continuation: WorkContinuation = wm.beginUniqueWork(
                "${batchDir.name}_${System.currentTimeMillis()}_fullpipeline",
                ExistingWorkPolicy.REPLACE,
                embedReq
            )

            Log.i(tag, "enqueueFullPipelineStructured: manifest exists, totalItems=${manifest.items.size}")

            val dpReqs = mutableListOf<OneTimeWorkRequest>()
            var anyTtsNeeded = false

            manifest.items.forEach { item ->
                val id = item.id
                if (id.isNullOrBlank()) {
                    Log.w(tag, "skip item with missing id in manifest")
                    return@forEach
                }

                val terminalFailed = item.status == "failed" ||(item.status?.contains("_failed") == true) ||
                        (item.status?.contains("error", ignoreCase = true) == true)
                if (terminalFailed) {
                    Log.i(tag, "skip item $id: terminal status=${item.status}, will not enqueue DP/TTS")
                    return@forEach
                }

                val dpNeeded = item.dpPaths[dpKey].isNullOrEmpty()
                val ttsNeeded = item.ttsOutputPath.isNullOrEmpty()

                if (dpNeeded) {
                    val dpData = workDataOf(
                        "batchDir" to batchDir.absolutePath,
                        "itemId" to id,
                        "dpKey" to dpKey
                    )
                    val dpReq: OneTimeWorkRequest =
                        OneTimeWorkRequestBuilder<com.example.myapplication.workers.DPWorker>()
                            .setInputData(dpData)
                            .addTag("${tag}_dp_${id}")
                            .addTag(batchTag)
                            .build()
                    dpReqs += dpReq
                    Log.i(tag, "prepared DP request for item=$id")
                } else {
                    Log.i(tag, "dp already present for item=$id, skippingDP request")
                }

                if (ttsNeeded) anyTtsNeeded = true
            }

            if (dpReqs.isNotEmpty()) {
                continuation = continuation.then(dpReqs)
                Log.i(tag, "enqueueFullPipelineStructured: chained ${dpReqs.size} DP requests in parallel")
            } else {
                Log.i(tag, "enqueueFullPipelineStructured: no DP requests needed")
            }

            if (anyTtsNeeded) {
                val ttsBatchData = workDataOf(
                    "batchDir" to batchDir.absolutePath,
                    "KEY_TTS_TEXT" to "",
                    "KEY_DP_KEY" to dpKey
                )
                val ttsBatchReq =
                    OneTimeWorkRequestBuilder<com.example.myapplication.workers.TTSWorker>()
                        .setInputData(ttsBatchData)
                        .addTag("${tag}_tts_batch_${batchDir.name}")
                        .addTag(batchTag)
                        .build()
                continuation = continuation.then(ttsBatchReq)
                Log.i(tag, "enqueueFullPipelineStructured: chained batch TTS request")
            } else {
                Log.i(tag, "enqueueFullPipelineStructured: no TTS needed for any item")
            }

            continuation.enqueue()
            Log.i(tag, "enqueueFullPipelineStructured enqueued full pipeline for ${batchDir.absolutePath}, embedId=${embedReq.id}")} catch (e: Exception) {
            Log.e(tag, "enqueueFullPipelineStructured failed: ${e.message}", e)
        }

        return embedReq.id
    }
}