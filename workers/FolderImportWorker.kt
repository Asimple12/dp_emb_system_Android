// FolderImportWorker.kt
package com.example.myapplication.work

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.myapplication.data.FolderImporter
import com.example.myapplication.models.BatchManifest
import com.example.myapplication.StageRunner
import com.example.myapplication.utils.ManifestIO
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class FolderImportWorker(appContext: android.content.Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object { private const val TAG = "FolderImportWorker" }

    private fun appendDebug(context: android.content.Context, text: String) {
        try {
            val f = File(context.filesDir, "folder_import_debug.txt")
            f.appendText("${System.currentTimeMillis()}: $text\n")
        } catch (e: Exception) {
            Log.w(TAG, "appendDebug failed", e)
        }
    }

    override suspend fun doWork(): Result {
        val rootPath = inputData.getString("folderPath") ?:
        inputData.getString("root_path") ?: return Result.failure()
        Log.i(TAG, "inputData keys: ${inputData.keyValueMap.keys}, rootPath=$rootPath")
        val modeStr = inputData.getString("mode") ?: "PER_FOLDER"
        Log.i(TAG, "doWork start. inputData keys=${inputData.keyValueMap.keys}")

        appendDebug(applicationContext, "doWork start. rootPath=$rootPath mode=$modeStr")

        val hasRead = try {
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
        appendDebug(applicationContext, "READ_EXTERNAL_STORAGE granted=$hasRead")

        val rootFile = File(rootPath)
        if (!rootFile.exists()) {
            appendDebug(applicationContext, "rootPath does not exist: $rootPath")
            return Result.failure(workDataOf("error" to "path does not exist: $rootPath"))
        }
        if (!rootFile.isDirectory) {
            appendDebug(applicationContext, "rootPath is not directory: $rootPath")
            return Result.failure(workDataOf("error" to "not a directory: $rootPath"))
        }

        try {
            val list = rootFile.listFiles()
            appendDebug(applicationContext, "listFiles returned ${list?.size ?: "null"} entries for $rootPath")
        } catch (e: Exception) {
            val sw = StringWriter().also { e.printStackTrace(PrintWriter(it)) }
            appendDebug(applicationContext, "listFiles exception: ${sw.toString()}")
            return Result.failure(workDataOf("error" to "listFiles exception: ${e.message}"))
        }

        return try {
            val processed: Int = FolderImporter.importFromFolder(applicationContext, rootFile, FolderImporter.BatchMode.valueOf(modeStr), chunkSize = 500)
            appendDebug(applicationContext, "importFromFolder done processed=$processed")
            Log.i(TAG, "importFromFolder processed=$processed")
            val batchesDir = File(applicationContext.filesDir, "batches")
            val batchDir = batchesDir.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.lastModified() }

            if (batchDir == null) {
                appendDebug(applicationContext, "batchDir not found after import (no directories in ${batchesDir.absolutePath})")
                Log.w(TAG, "batchDir not found after import")
                return Result.success(workDataOf("processed" to processed))
            }

            val manifestFile = File(batchDir, "manifest.json")
            if (!manifestFile.exists()) {
                appendDebug(applicationContext, "manifest.json not found in located batchDir: ${batchDir.absolutePath}")
                Log.w(TAG, "manifest.json not found in located batchDir: ${batchDir.absolutePath}")
                return Result.success(workDataOf("processed" to processed, "batchDir" to batchDir.absolutePath))
            }
            val manifest: BatchManifest = try {
                ManifestIO.readManifest(manifestFile)
            } catch (e: Exception) {
                appendDebug(applicationContext, "readManifest failed for ${manifestFile.absolutePath}: ${e.message}")
                Log.w(TAG, "readManifest failed", e)
                return Result.success(workDataOf("processed" to processed, "batchDir" to batchDir.absolutePath))
            }

            appendDebug(applicationContext, "importFromFolder done batchId=${manifest.batchId} items=${manifest.items.size}")
            if (batchDir.exists()) {
                appendDebug(applicationContext, "calling StageRunner.enqueueFullPipelineStructured for ${batchDir.absolutePath}")
                try {
                    StageRunner.enqueueFullPipelineStructured(applicationContext, batchDir, dpKey = "gauss", ttsText = "")
                    appendDebug(applicationContext, "enqueueFullPipelineStructured called for ${batchDir.absolutePath}")
                } catch (e: Exception) {
                    appendDebug(applicationContext, "enqueueFullPipelineStructured failed: ${e.message}")
                }
            } else {
                appendDebug(applicationContext, "batchDir not found after import: ${batchDir.absolutePath}")
            }

            Result.success(workDataOf("processed" to processed, "batchId" to manifest.batchId))
        } catch (e: Exception) {
            val sw = StringWriter().also { e.printStackTrace(PrintWriter(it)) }
            appendDebug(applicationContext, "importFromFolder failed: ${e.message}\n$sw")
            Log.e(TAG, "importFromFolder failed", e)
            Result.failure(workDataOf("error" to (e.message ?: "unknown")))
        }
    }
}