package com.example.myapplication.utils

import android.content.Context
import android.util.Log
import com.example.myapplication.data.AppDatabase
import com.example.myapplication.data.ManifestRepository
import com.example.myapplication.data.BatchManifestEntity
import com.example.myapplication.models.BatchManifest
import com.example.myapplication.models.BatchItem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object ManifestIO {
    private const val TAG = "ManifestIO"

    private val MANIFEST_MUTEX = Mutex()

    @Volatile
    private var initialized = false
    private val lock = Any()

    private lateinit var repo: ManifestRepository

    fun init(context: Context) {
        if (initialized) {
            Log.d(TAG, "init() already done - returning")
            return
        }
        synchronized(lock) {
            if (initialized) {
                Log.d(TAG, "init() already done inside lock - returning")
                return
            }
            Log.i(TAG, "init called - creating DB & repository")
            val db = AppDatabase.getInstance(context)
            repo = ManifestRepository(db)
            initialized = true
            Log.i(TAG, "init completed")
        }
    }

    private fun checkInitialized() {
        if (!initialized) {
            throw IllegalStateException("ManifestIO is not initialized. Call ManifestIO.init(context) first.")
        }
    }

    fun writeManifest(manifest: BatchManifest, dest: File) {
        runBlocking {
            writeManifestSuspend(manifest, dest)
        }
    }

    fun readManifest(src: File): BatchManifest {
        return runBlocking {
            readManifestSuspend(src)
        }
    }

    suspend fun writeManifestSuspend(manifest: BatchManifest, dest: File) {
        checkInitialized()
        MANIFEST_MUTEX.withLock {
            val manifestEntity = BatchManifestEntity(batchId = manifest.batchId, createdAt = manifest.createdAt)
            val itemsEntities = manifest.items.map { toEntity(it, manifest.batchId) }
            repo.saveManifest(manifestEntity, itemsEntities)
            try {
                writeManifestFileAtomic(manifest, dest)
                Log.i(TAG, "writeManifestSuspend: atomically wrote manifest json to ${dest.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "writeManifestSuspend: failed to write manifest file atomically: ${dest.absolutePath}", e)}
        }
    }

    suspend fun readManifestSuspend(src: File): BatchManifest {
        checkInitialized()
        if (src.exists()) {
            return withContext(Dispatchers.IO) {
                parseManifestFile(src)
            }
        } else {
            val guessedBatchId = src.nameWithoutExtension
            val dto = withContext(Dispatchers.IO) { repo.loadManifest(guessedBatchId) }
            if (dto != null) {
                val items = dto.items.map { fromEntity(it) }.toMutableList()
                return BatchManifest(batchId = dto.manifest.batchId, createdAt = dto.manifest.createdAt, items = items)
            } else {
                throw IllegalStateException("manifest file not found and no DB record for batchId=${guessedBatchId}")
            }
        }
    }
    suspend fun updateItemSuspend(manifestFile: File, item: BatchItem) {
        checkInitialized()
        if (manifestFile.exists()) {
            try {
                MANIFEST_MUTEX.withLock {
                    updateManifestFileWithLock(manifestFile, transform = { manifest ->
                        val idx = manifest.items.indexOfFirst { it.id == item.id }
                        if (idx >= 0) {
                            manifest.items[idx] = item
                        } else {
                            manifest.items.add(item)
                        }
                        manifest
                    })
                    val finalManifest = parseManifestFile(manifestFile)
                    val manifestEntity = BatchManifestEntity(batchId = finalManifest.batchId, createdAt = finalManifest.createdAt)
                    val itemsEntities = finalManifest.items.map { toEntity(it, finalManifest.batchId) }
                    repo.saveManifest(manifestEntity, itemsEntities)
                    Log.i(TAG, "updateItemSuspend: updated item ${item.id} in file ${manifestFile.absolutePath} (atomic + locked)")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "updateItemSuspend: failed to update file ${manifestFile.absolutePath} with lock, will fallback to DB.", e)
            }
        }
        val guessedBatchId = manifestFile.nameWithoutExtension
        val dto = withContext(Dispatchers.IO) { repo.loadManifest(guessedBatchId) }
        if (dto != null) {
            val items = dto.items.map { fromEntity(it) }.toMutableList()
            val idx = items.indexOfFirst { it.id == item.id }
            if (idx >= 0) items[idx] = item else items.add(item)
            val newManifest = BatchManifest(batchId = dto.manifest.batchId, createdAt = dto.manifest.createdAt, items = items)
            val manifestEntity = BatchManifestEntity(batchId = newManifest.batchId, createdAt = newManifest.createdAt)
            val itemsEntities = newManifest.items.map { toEntity(it, newManifest.batchId) }
            repo.saveManifest(manifestEntity, itemsEntities)
            Log.i(TAG, "updateItemSuspend: updated item ${item.id} in DB for batch ${guessedBatchId}")
        } else {
            val newManifest = BatchManifest(batchId = guessedBatchId, createdAt = System.currentTimeMillis(), items = mutableListOf(item))
            val manifestEntity = BatchManifestEntity(batchId = newManifest.batchId, createdAt = newManifest.createdAt)
            val itemsEntities = newManifest.items.map { toEntity(it, newManifest.batchId) }
            repo.saveManifest(manifestEntity, itemsEntities)
            Log.i(TAG, "updateItemSuspend: created new DB manifest for batch ${guessedBatchId} and saved item ${item.id}")
        }
    }

    private suspend fun writeManifestFileAtomic(manifest: BatchManifest, dest: File, maxRetries: Int = 3) {
        withContext(Dispatchers.IO) {
            val dir = dest.parentFile ?: dest.absoluteFile.parentFile ?: throw IOException("不能确定 manifest 目录")
            dir.mkdirs()
            val jsonBytes = manifestToJson(manifest).toString(2).toByteArray(Charsets.UTF_8)
            val tmp = File(dir, dest.name + ".tmp")
            var lastEx: Exception? = null
            for (attempt in 1..maxRetries) {
                try {
                    FileOutputStream(tmp).use { fos ->
                        fos.write(jsonBytes)
                        fos.fd.sync()
                    }
                    try {
                        Files.move(
                            tmp.toPath(),
                            dest.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (atomicEx: Exception) {
                        Log.i(TAG, "atomic move failed or not supported, fallback to renameTo/delete for ${dest.absolutePath}: ${atomicEx.message}")
                        if (dest.exists() && !dest.delete()) {
                            throw IOException("无法删除旧 manifest 文件 ${dest.absolutePath}")
                        }
                        if (!tmp.renameTo(dest)) {
                            throw IOException("无法替换 manifest 文件 ${dest.absolutePath} via renameTo fallback")
                        }
                    }
                    return@withContext
                } catch (e: Exception) {
                    lastEx = e
                    Log.w(TAG, "writeManifestFileAtomic attempt $attempt failed for ${dest.absolutePath}", e)
                    if (attempt < maxRetries) {
                        delay(100L * attempt)
                    } else {
                        throw IOException("写manifest 到 ${dest.absolutePath} 重试失败", e)
                    }
                }
            }
            lastEx?.let { throw it }
        }
    }
    private suspend fun updateManifestFileWithLock(
        manifestFile: File,
        transform: (BatchManifest) -> BatchManifest,
        maxRetries: Int = 3
    ) {
        if (!manifestFile.exists()) throw IOException("manifest file not found: ${manifestFile.absolutePath}")
        var lastEx: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                withContext(Dispatchers.IO) {
                    val current = parseManifestFile(manifestFile)
                    val updated = transform(current)
                    val bytes = manifestToJson(updated).toString(2).toByteArray(Charsets.UTF_8)
                    val tmp = File(manifestFile.parentFile, manifestFile.name + ".tmp")
                    FileOutputStream(tmp).use { fos ->
                        fos.write(bytes)
                        fos.fd.sync()
                    }
                    try {
                        Files.move(
                            tmp.toPath(),
                            manifestFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (atomicEx: Exception) {
                        Log.i(TAG, "updateManifestFileWithLock: atomic move fallback for ${manifestFile.absolutePath}: ${atomicEx.message}")
                        if (manifestFile.exists() && !manifestFile.delete()) {
                            throw IOException("无法删除旧 manifest 文件 ${manifestFile.absolutePath}")
                        }
                        check (!tmp.renameTo(manifestFile)) {
                            throw IOException("无法替换 manifest 文件 ${manifestFile.absolutePath} via renameTo fallback")
                        }
                    }
                }
                return
            } catch (e: Exception) {
                lastEx = e
                Log.w(TAG, "updateManifestFileWithLock attempt $attempt failed for ${manifestFile.absolutePath}", e)
                if (attempt < maxRetries) {
                    delay(100L * attempt)
                } else {
                    throw IOException("updateManifestFileWithLock failed after retries: ${manifestFile.absolutePath}", e)
                }
            }
        }
        lastEx?.let { throw it }
    }

    private fun parseManifestFile(src: File): BatchManifest {
        val text = src.readText(Charsets.UTF_8).trim()
        if (text.isEmpty()) throw IOException("manifest file ${src.absolutePath} is empty")
        val obj = JSONObject(text)
        val batchId = obj.getString("batchId")
        val createdAt = obj.getLong("createdAt")
        val itemsArray = obj.optJSONArray("items") ?: JSONArray()
        val items = mutableListOf<BatchItem>()
        for (i in 0 until itemsArray.length()) {
            val it = itemsArray.getJSONObject(i)
            val id = it.getString("id")
            val wavPath = it.optString("wavPath", "")
            val embeddingPath = if (it.has("embeddingPath") && !it.isNull("embeddingPath")) it.getString("embeddingPath") else null
            val dpPaths = mutableMapOf<String, String?>()
            if (it.has("dpPaths") && !it.isNull("dpPaths")) {
                val dpObj = it.getJSONObject("dpPaths")
                val keys = dpObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = if (dpObj.isNull(k)) null else dpObj.getString(k)
                    dpPaths[k] = v
                }
            }
            val ttsOutputPath = if (it.has("ttsOutputPath") && !it.isNull("ttsOutputPath")) it.getString("ttsOutputPath") else null
            val status = it.optString("status", "pending")
            val error = if (it.has("error") && !it.isNull("error")) it.getString("error") else null
            val updatedAt = if (it.has("updatedAt")) it.getLong("updatedAt") else System.currentTimeMillis()
            val item = BatchItem(
                id = id,
                wavPath = wavPath,
                embeddingPath = embeddingPath,
                dpPaths = dpPaths,
                ttsOutputPath = ttsOutputPath,
                status = status,
                error = error,
                updatedAt = updatedAt
            )
            items.add(item)
        }
        return BatchManifest(batchId = batchId, createdAt = createdAt, items = items)
    }

    private fun manifestToJson(manifest: BatchManifest): JSONObject {
        val obj = JSONObject()
        obj.put("batchId", manifest.batchId)
        obj.put("createdAt", manifest.createdAt)
        val arr = JSONArray()
        for (it in manifest.items) {
            val o = JSONObject()
            o.put("id", it.id)
            o.put("wavPath", it.wavPath)
            o.put("embeddingPath", it.embeddingPath ?: JSONObject.NULL)
            val dpObj = JSONObject()
            for ((k, v) in it.dpPaths) {
                dpObj.put(k, v?: JSONObject.NULL)
            }
            o.put("dpPaths", dpObj)
            o.put("ttsOutputPath", it.ttsOutputPath?: JSONObject.NULL)
            o.put("status", it.status)
            o.put("error", it.error ?: JSONObject.NULL)
            o.put("updatedAt", it.updatedAt)
            arr.put(o)
        }
        obj.put("items", arr)
        return obj
    }

    private fun toEntity(item: com.example.myapplication.models.BatchItem, batchId: String): com.example.myapplication.data.BatchItemEntity {
        return com.example.myapplication.data.BatchItemEntity(
            id = item.id,
            batchId = batchId,
            wavPath = item.wavPath,
            embeddingPath = item.embeddingPath,
            dpPathsJson = item.dpPaths.toMap(),
            ttsOutputPath = item.ttsOutputPath,
            status = item.status,
            error = item.error,
            updatedAt = item.updatedAt
        )
    }

    private fun fromEntity(entity: com.example.myapplication.data.BatchItemEntity): com.example.myapplication.models.BatchItem {
        val dp = (entity.dpPathsJson ?: emptyMap()).toMutableMap()
        return com.example.myapplication.models.BatchItem(
            id = entity.id,
            wavPath = entity.wavPath,
            embeddingPath = entity.embeddingPath,
            dpPaths = dp,
            ttsOutputPath = entity.ttsOutputPath,
            status = entity.status ?: "pending",
            error = entity.error,
            updatedAt = entity.updatedAt
        )
    }
}