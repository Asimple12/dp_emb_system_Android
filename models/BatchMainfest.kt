package com.example.myapplication.models

data class BatchManifest(
    val batchId: String,
    val createdAt: Long,
    val items: MutableList<BatchItem>
)

data class BatchItem(
    val id: String,
    val wavPath: String,
    var embeddingPath: String?,
    var dpPaths: MutableMap<String, String?> = mutableMapOf(),
    var ttsOutputPath: String? = null,
    var status: String = "pending",
    var error: String? = null,
    var updatedAt: Long = System.currentTimeMillis()
)