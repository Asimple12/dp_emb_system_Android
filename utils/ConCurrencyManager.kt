package com.example.myapplication.utils

import kotlinx.coroutines.sync.Semaphore

/**
 * ConcurrencyManager - 基本并发许可控制
 * dpSemaphore: 控制 DP任务并发数量
 * ttsSemaphore: 控制 TTS 任务并发数量
 */
object ConcurrencyManager {
    private val dpSemaphore = Semaphore(1)
    private val ttsSemaphore = Semaphore(1)
    suspend fun acquireDp(): Boolean {
        dpSemaphore.acquire()
        return true
    }

    fun releaseDp() {
        dpSemaphore.release()
    }

    suspend fun acquireTts(): Boolean {
        ttsSemaphore.acquire()
        return true
    }

    fun releaseTts() {
        ttsSemaphore.release()
    }
}