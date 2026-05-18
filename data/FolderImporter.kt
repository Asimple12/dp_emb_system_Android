package com.example.myapplication.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.*
import androidx.room.withTransaction
import com.example.myapplication.models.BatchManifest
import com.example.myapplication.models.BatchItem
import com.example.myapplication.utils.ManifestIO

object FolderImporter {

    enum class BatchMode { SINGLE, PER_FOLDER }
    suspend fun importFromFolder(
        context: Context,
        rootDir: File,
        mode: BatchMode = BatchMode.PER_FOLDER,
        chunkSize: Int = 500
    ): Int = withContext(Dispatchers.IO) {
        fun debug(msg: String) {
            try {
                val f = File(context.filesDir, "folder_import_debug.txt")
                f.appendText("${System.currentTimeMillis()}: $msg\n")
            } catch (_: Exception) { }
        }

        debug("importFromFolder start. root=${rootDir.absolutePath} mode=$mode chunkSize=$chunkSize")
        require(rootDir.exists() && rootDir.isDirectory) { "rootDir must exist and be directory" }

        val db = AppDatabase.getInstance(context)
        val repo = ManifestRepository(db)

        val exts = setOf("wav", "mp3", "flac", "m4a")
        val files = try {
            rootDir.walkTopDown()
                .filter {
                    try {
                        it.isFile && exts.contains(it.extension.lowercase(Locale.getDefault()))
                    } catch (e: Exception) {
                        debug("filter exception for path=${it.absolutePath}: ${e.message}")
                        false
                    }
                }
                .toList()
        } catch (e: Exception) {
            debug("walkTopDown failed: ${e.message}")
            throw e
        }

        debug("found files count=${files.size}")
        if (files.isEmpty()) return@withContext 0

        var processed = 0

        fun makeBatchIdForFile(file: File): String {
            return when (mode) {
                BatchMode.SINGLE -> "batch_${rootDir.name}_${rootDir.lastModified()}"
                BatchMode.PER_FOLDER -> {
                    val relative = rootDir.toURI().relativize(file.parentFile?.toURI() ?: rootDir.toURI()).path
                    val key = if (relative.isEmpty()) rootDir.name else relative.trimEnd('/')
                    "batch_${key.replace(File.separatorChar, '_')}"
                }
            }
        }

        val filesByBatch = files.groupBy { makeBatchIdForFile(it) }
        debug("files grouped into ${filesByBatch.size} batches. sample batches=${filesByBatch.keys.take(10)}")

        for ((batchId, batchFiles) in filesByBatch) {
            val createdAt = System.currentTimeMillis()
            val manifestEntity = BatchManifestEntity(batchId = batchId, createdAt = createdAt)
            val toInsert = mutableListOf<BatchItemEntity>()
            val allItemsForBatch = mutableListOf<BatchItemEntity>()

            debug("processing batchId=$batchId files=${batchFiles.size}")

            for (file in batchFiles) {
                try {
                    if (!file.exists() || !file.isFile) {
                        debug("skip not-file or missing: ${file.absolutePath}")
                        continue
                    }
                    if (!file.canRead()) {
                        debug("file not readable (skip): ${file.absolutePath}")
                        continue
                    }

                    val id = UUID.nameUUIDFromBytes(file.absolutePath.toByteArray()).toString()
                    val wavPath = file.absolutePath
                    val dpMap = readMetaForFile(file)

                    val item = BatchItemEntity(
                        id = id,
                        batchId = batchId,
                        wavPath = wavPath,
                        embeddingPath = null,
                        dpPathsJson = dpMap,
                        ttsOutputPath = null,
                        status = "pending",
                        error = null,
                        updatedAt = System.currentTimeMillis(),
                        version = 0
                    )
                    toInsert.add(item)
                    processed += 1

                    if (toInsert.size >= chunkSize) {
                        try {
                            db.withTransaction { repo.saveManifest(manifestEntity, toInsert) }
                            debug("saved chunk of ${toInsert.size} for batch $batchId")
                            allItemsForBatch.addAll(toInsert)
                        } catch (e: Exception) {
                            debug("db.withTransaction failed for batch $batchId: ${e.message}")
                            throw e
                        }
                        toInsert.clear()
                    }
                } catch (e: Exception) {
                    debug("processing file ${file.absolutePath} failed: ${e.message}")
                }
            }

            if (toInsert.isNotEmpty()) {
                try {
                    db.withTransaction { repo.saveManifest(manifestEntity, toInsert) }
                    debug("saved final chunk ${toInsert.size} for batch $batchId")
                    allItemsForBatch.addAll(toInsert)
                } catch (e: Exception) {
                    debug("db.withTransaction final chunk failed for batch $batchId: ${e.message}")
                    throw e
                }
                toInsert.clear()
            }

            try {
                val itemsModel = allItemsForBatch.map { entity ->
                    BatchItem(
                        id = entity.id,
                        wavPath = entity.wavPath,
                        embeddingPath = entity.embeddingPath,
                        dpPaths = (entity.dpPathsJson ?: emptyMap()).toMutableMap(),
                        ttsOutputPath = entity.ttsOutputPath,
                        status = entity.status ?: "pending",
                        error = entity.error,
                        updatedAt = entity.updatedAt
                    )
                }.toMutableList()

                val manifestModel = BatchManifest(
                    batchId = batchId,
                    createdAt = manifestEntity.createdAt,
                    items = itemsModel
                )

                val batchesDir = File(context.filesDir, "batches")
                if (!batchesDir.exists()) batchesDir.mkdirs()
                val batchDir = File(batchesDir, batchId)
                if (!batchDir.exists()) batchDir.mkdirs()
                val manifestFile = File(batchDir, "manifest.json")
                try { ManifestIO.init(context) } catch (_: Exception) { }
                ManifestIO.writeManifest(manifestModel, manifestFile)
                debug("wrote manifest JSON to ${manifestFile.absolutePath} for batch $batchId")
            } catch (e: Exception) {
                debug("failed to write manifest.json for batch $batchId: ${e.message}")
            }
        }

        debug("importFromFolder done processed=$processed")
        return@withContext processed
    }

    private fun readMetaForFile(file: File): Map<String, String?>? {
        val candidate1 = File(file.parentFile, "${file.name}.meta.json")
        val nameNoExt = file.nameWithoutExtension
        val candidate2 = File(file.parentFile, "$nameNoExt.meta.json")
        val metaFile = when {
            candidate1.exists() -> candidate1
            candidate2.exists() -> candidate2
            else -> null
        }
        if (metaFile == null) return null

        return try {
            val text = metaFile.readText()
            val obj = JSONObject(text)
            val map = mutableMapOf<String, String?>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = if (obj.isNull(k)) null else obj.optString(k, null)
            }
            map
        } catch (e: Exception) {
            null
        }
    }
}