package com.example.myapplication.data

import androidx.room.*

@Entity(tableName = "batch_manifests")
data class BatchManifestEntity(
    @PrimaryKey val batchId: String,
    val createdAt: Long
)

@Entity(
    tableName = "batch_items",
    indices = [Index(value = ["wavPath"], unique = true), Index("batchId")],
    foreignKeys = [ForeignKey(
        entity = BatchManifestEntity::class,
        parentColumns = ["batchId"],
        childColumns = ["batchId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class BatchItemEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val wavPath: String,
    val embeddingPath: String?,
    val dpPathsJson: Map<String, String?>?,
    val ttsOutputPath: String?,
    val status: String = "pending",
    val error: String?,
    val updatedAt: Long = System.currentTimeMillis(),
    val version: Int = 0
)