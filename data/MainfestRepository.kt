package com.example.myapplication.data

import androidx.room.withTransaction

class ManifestRepository(private val db: AppDatabase) {
    private val dao = db.batchDao()

    suspend fun saveManifest(manifestEntity: BatchManifestEntity, items: List<BatchItemEntity>) {
        db.withTransaction {
            dao.insertManifest(manifestEntity)
            dao.insertItems(items)
        }
    }

    suspend fun loadManifest(batchId: String): BatchManifestWithItems? {
        return dao.getManifestWithItems(batchId)
    }

}