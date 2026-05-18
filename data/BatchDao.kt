package com.example.myapplication.data

import androidx.room.*

@Dao
interface BatchDao {
    @Transaction
    @Query("SELECT * FROM batch_manifests WHERE batchId = :batchId")
    suspend fun getManifestWithItems(batchId: String): BatchManifestWithItems?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertManifest(manifest: BatchManifestEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<BatchItemEntity>)

    @Query("SELECT * FROM batch_items WHERE wavPath = :wavPath LIMIT 1")
    suspend fun findItemByWavPath(wavPath: String): BatchItemEntity?

    @Query("UPDATE batch_items SET embeddingPath = :embeddingPath, status = :status, error = :error, updatedAt = :updatedAt, version = version + 1 WHERE wavPath = :wavPath")
    suspend fun updateItemByWavPath(wavPath: String, embeddingPath: String?, status: String, error: String?, updatedAt: Long)

    @Query("DELETE FROM batch_items WHERE batchId = :batchId")
    suspend fun deleteItemsByBatch(batchId: String)

    @Query("DELETE FROM batch_manifests WHERE batchId = :batchId")
    suspend fun deleteManifest(batchId: String)

    @Query("SELECT COUNT(*) FROM batch_items WHERE batchId = :batchId")
    suspend fun countItemsInBatch(batchId: String): Int

    @Query("SELECT * FROM batch_items WHERE batchId = :batchId ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun getItemsByBatchPaged(batchId: String, limit: Int, offset: Int): List<BatchItemEntity>
}

data class BatchManifestWithItems(
    @Embedded val manifest: BatchManifestEntity,
    @Relation(parentColumn = "batchId", entityColumn = "batchId")
    val items: List<BatchItemEntity>
)