package com.example.myapplication.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.example.myapplication.DifferentialPrivacy
import com.example.myapplication.utils.ConcurrencyManager
import com.example.myapplication.utils.ManifestIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DPWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        private const val TAG = "DPWorker"
    }

    override suspend fun doWork(): Result {
        val batchDirPath = inputData.getString("batchDir")
            ?: inputData.getString("KEY_BATCH_DIR")
            ?: run {
                Log.e(TAG, "missing batchDir input")
                return Result.failure()
            }

        val itemId = inputData.getString("itemId")
            ?: inputData.getString("KEY_ITEM_ID")
            ?: run {
                Log.e(TAG, "missing itemId input")
                return Result.failure()
            }

        val dpKey = inputData.getString("dpKey") ?: "gauss"
        val manifestFile = File(batchDirPath, "manifest.json")
        Log.i(TAG, "RUNNING for item=$itemId in batch=$batchDirPath dpKey=$dpKey")

        val manifest = try {
            ManifestIO.readManifestSuspend(manifestFile)
        } catch (e: Exception) {
            Log.e(TAG, "readManifestSuspend failed", e)
            return Result.failure()
        }

        val item = manifest.items.find { it.id == itemId } ?: run {
            Log.e(TAG, "item not found $itemId in manifest ${manifestFile.absolutePath}")
            return Result.failure()
        }

        Log.i(TAG, "initial item state: id=${item.id} status=${item.status} embeddingPath=${item.embeddingPath} dpKeys=${item.dpPaths.keys}")

        // 如果已经完成 DP
        if (item.status == "dp_done" || !item.dpPaths.getOrDefault(dpKey, null).isNullOrEmpty()) {
            Log.i(TAG, "skip already dp_done or dpKey present for item ${item.id}")
            return Result.success()
        }

        // 若 embeddingPath 为空
        if (item.embeddingPath.isNullOrEmpty()) {
            if (item.status?.contains("_failed") == true || item.status == "failed") {
                Log.i(TAG, "embedding already failed for ${item.id} (status=${item.status}), skipping DP")
                return Result.success()
            }
            val transientStatuses = setOf("pending", "embedding_in_progress", "embedding_pending")
            if (item.status in transientStatuses) {
                Log.w(TAG, "embedding not ready yet for ${item.id}; status=${item.status}; will retry")
                return Result.retry()
            }
            val updated = item.copy(status = "failed", error = "missing embedding", updatedAt = System.currentTimeMillis())
            try { ManifestIO.updateItemSuspend(manifestFile, updated) } catch (e: Exception) { Log.w(TAG, "persist missing-embedding failed", e) }
            return Result.failure()
        }

        val acquired = try {
            ConcurrencyManager.acquireDp()
        } catch (e: Exception) {
            Log.e(TAG, "acquireDp exception", e)
            false
        }
        if (!acquired) {
            Log.w(TAG, "failed to acquire DP permit for item $itemId, will retry")
            return Result.retry()
        }

        try {
            val latestManifest = try {
                ManifestIO.readManifestSuspend(manifestFile)
            } catch (e: Exception) {
                Log.e(TAG, "readManifestSuspend after acquire failed", e)
                return Result.failure()
            }

            val latestItem = latestManifest.items.find { it.id == itemId } ?: run {
                Log.e(TAG, "item disappeared from manifest after acquire: $itemId")
                return Result.failure()
            }

            if (latestItem.status == "dp_done") {
                Log.i(TAG, "another worker finished dp for item $itemId - skipping")
                return Result.success()
            }

            if (!latestItem.dpPaths.getOrDefault(dpKey, null).isNullOrEmpty()) {
                Log.i(TAG, "dp result already present for item $itemId and key=$dpKey - skipping")
                return Result.success()
            }

            val embPath = latestItem.embeddingPath
            if (embPath.isNullOrEmpty()) {
                val updated = latestItem.copy(status = "failed", error = "missing embedding (after lock)", updatedAt = System.currentTimeMillis())
                try { ManifestIO.updateItemSuspend(manifestFile, updated) } catch (e: Exception) { Log.w(TAG, "persist missing-embedding (after lock) failed", e) }
                return Result.failure()
            }

            val embFile = File(embPath)
            val embExists = withContext(Dispatchers.IO) { embFile.exists() && embFile.canRead() }
            val embLen = embFile.length()
            Log.i(TAG, "embFile.exists=$embExists embLen=$embLen for item=${item.id}")

            if (!embExists) {
                Log.w(TAG, "embedding file not found or unreadable for ${item.id}; will retry")
                return Result.retry()
            }
            if (embLen < 4 || (embLen % 4L) != 0L) {
                Log.e(TAG, "embedding file size invalid (len=$embLen) for ${item.id}")
                val failedItem = latestItem.copy(status = "failed", error = "embedding file size invalid: $embLen", updatedAt = System.currentTimeMillis())
                try { ManifestIO.updateItemSuspend(manifestFile, failedItem) } catch (e: Exception) { Log.w(TAG, "persist invalid-emb failed", e) }
                return Result.failure()
            }

            val dp = try {
                DifferentialPrivacy(seed = System.nanoTime() xor itemId.hashCode().toLong())
            } catch (e: Exception) {
                Log.e(TAG, "failed to init DifferentialPrivacy", e)
                val failedItem = latestItem.copy(status = "failed", error = "dp init error: ${e.message}", updatedAt = System.currentTimeMillis())
                try { ManifestIO.updateItemSuspend(manifestFile, failedItem) } catch (_: Exception) { }
                return Result.failure()
            }

            val emb = try {
                dp.loadEmbeddingFromFile(embFile)
            } catch (e: Exception) {
                Log.e(TAG, "failed to load embedding from file ${embFile.absolutePath}", e)
                val failedItem = latestItem.copy(status = "failed", error = "load embedding error: ${e.message}", updatedAt = System.currentTimeMillis())
                try { ManifestIO.updateItemSuspend(manifestFile, failedItem) } catch (_: Exception) { }
                return Result.failure()
            }

            val (eEmb, lEmb, gEmb) = try {
                dp.processEmbeddingAsync(emb)
            } catch (e: Exception) {
                Log.e(TAG, "dp.processEmbeddingAsync failed for $itemId", e)
                val failedItem = latestItem.copy(status = "failed", error = "dp process error: ${e.message}", updatedAt = System.currentTimeMillis())
                try { ManifestIO.updateItemSuspend(manifestFile, failedItem) } catch (_: Exception) { }
                return Result.failure()
            }

            val base = File(batchDirPath, "dp/${itemId}/${dpKey}")
            try {
                withContext(Dispatchers.IO) { base.mkdirs() }
            } catch (e: Exception) {
                Log.e(TAG, "failed to create dp output dir $base", e)
                val failedItem = latestItem.copy(status = "failed", error = "dp output dir create error: ${e.message}", updatedAt = System.currentTimeMillis())
                try { ManifestIO.updateItemSuspend(manifestFile, failedItem) } catch (_: Exception) { }
                return Result.failure()
            }

            val expF = File(base, "dp_exp.bin")
            val lapF = File(base, "dp_lap.bin")
            val gauF = File(base, "dp_gauss.bin")

            try {
                withContext(Dispatchers.IO) {
                    dp.saveEmbeddingToFile(expF, eEmb)
                    dp.saveEmbeddingToFile(lapF, lEmb)
                    dp.saveEmbeddingToFile(gauF, gEmb)
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to save dp files for $itemId", e)
                val failedItem = latestItem.copy(status = "failed", error = "dp save files error: ${e.message}", updatedAt = System.currentTimeMillis())
                try { ManifestIO.updateItemSuspend(manifestFile, failedItem) } catch (_: Exception) { }
                return Result.failure()
            }

            val updatedDpMap = latestItem.dpPaths.toMutableMap()
            updatedDpMap["exp"] = expF.absolutePath
            updatedDpMap["lap"] = lapF.absolutePath
            updatedDpMap["gauss"] = gauF.absolutePath

            if (dpKey !in updatedDpMap) {
                updatedDpMap[dpKey] = when(dpKey) {
                    "exp"-> expF.absolutePath
                    "lap"   -> lapF.absolutePath
                    else-> gauF.absolutePath
                }
            }

            val updated = latestItem.copy(
                dpPaths = updatedDpMap,
                status = "dp_done",
                error = null,
                updatedAt = System.currentTimeMillis()
            )

            try {
                ManifestIO.updateItemSuspend(manifestFile, updated)
            } catch (e: Exception) {
                Log.e(TAG, "failed to update manifest for dp result of $itemId", e)
                return Result.success()
            }

            Log.i(TAG, "DPWorker finished successfully for item $itemId")
            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "unexpected DP processing fail for $itemId", e)
            val failedItem = item.copy(status = "failed", error = "dp error: ${e.message}", updatedAt = System.currentTimeMillis())
            try { ManifestIO.updateItemSuspend(manifestFile, failedItem) } catch (ex: Exception) { Log.w(TAG, "failed to persist dp-failure state for $itemId", ex) }
            return Result.failure()
        } finally {
            try {
                ConcurrencyManager.releaseDp()
            } catch (e: Exception) {
                Log.w(TAG, "releaseDp failed", e)
            }
        }
    }
}