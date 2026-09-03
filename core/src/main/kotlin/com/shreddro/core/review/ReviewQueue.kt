package com.shreddro.core.review

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * A slip image that passed the on-device gate but whose LLM parse failed the
 * contract twice. Parked here for the user to retry or key in manually —
 * without this queue those images would be silent data loss.
 */
@Serializable
data class ReviewItem(
    val sha256: String,
    val mediaId: String,
    val fileName: String,
    val errorMessage: String,
    val createdAtEpochMs: Long,
)

/** Durable storage port (platform supplies the adapter). */
interface ReviewStore {
    suspend fun load(): List<ReviewItem>
    suspend fun persist(items: List<ReviewItem>)
}

class ReviewQueue(private val store: ReviewStore) {

    private val mutex = Mutex()

    suspend fun add(item: ReviewItem) {
        mutex.withLock {
            val items = store.load()
            if (items.none { it.sha256 == item.sha256 }) {
                store.persist(items + item)
            }
        }
    }

    suspend fun list(): List<ReviewItem> = mutex.withLock { store.load() }

    suspend fun count(): Int = mutex.withLock { store.load().size }

    suspend fun remove(sha256: String) {
        mutex.withLock {
            store.persist(store.load().filterNot { it.sha256 == sha256 })
        }
    }
}
