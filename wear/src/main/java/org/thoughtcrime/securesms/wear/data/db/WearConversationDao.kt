package org.thoughtcrime.securesms.wear.data.db

import androidx.room.Dao
import androidx.room.Query
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
}
