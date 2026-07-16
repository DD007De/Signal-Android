package org.thoughtcrime.securesms.wear.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data access for the watch's local conversation cache.
 */
@Dao
interface WearConversationDao {

  @Upsert
  suspend fun upsertAll(items: List<WearConversationEntity>)

  @Query("SELECT * FROM wear_conversation ORDER BY timestamp DESC")
  fun observeAll(): Flow<List<WearConversationEntity>>

  @Query("DELETE FROM wear_conversation")
  suspend fun clear()

  /**
   * Full-replace sync used by [org.thoughtcrime.securesms.wear.bridge.WearMessageListenerService]
   * when a fresh conversation list arrives from the phone: clears the cache and inserts [items]
   * within a single transaction, so a thread dropped from the phone's payload (e.g. archived or
   * deleted) disappears from the watch cache too. [upsertAll] alone only adds/updates and never
   * removes, which is why it isn't reused here directly.
   */
  @Transaction
  suspend fun replaceAll(items: List<WearConversationEntity>) {
    clear()
    upsertAll(items)
  }
}
