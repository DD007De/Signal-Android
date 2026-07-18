package org.thoughtcrime.securesms.wear.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * The watch's local, encrypted-at-rest cache of conversation data synced down from the paired
 * phone. Backed by SQLCipher via [SupportOpenHelperFactory], with the passphrase generated once
 * and kept in [WearCachePassphrase]'s EncryptedSharedPreferences store.
 */
@Database(entities = [WearConversationEntity::class], version = 1, exportSchema = false)
abstract class WearCacheDatabase : RoomDatabase() {

  abstract fun wearConversationDao(): WearConversationDao

  companion object {
    private const val DATABASE_NAME = "wear-cache.db"

    @Volatile
    private var instance: WearCacheDatabase? = null

    fun getInstance(context: Context): WearCacheDatabase {
      return instance ?: synchronized(this) {
        instance ?: build(context.applicationContext).also { instance = it }
      }
    }

    private fun build(context: Context): WearCacheDatabase {
      WearSqlCipherLibraryLoader.load()

      val passphrase = WearCachePassphrase.getOrCreate(context)
      val factory = SupportOpenHelperFactory(passphrase)

      return Room.databaseBuilder(context, WearCacheDatabase::class.java, DATABASE_NAME)
        .openHelperFactory(factory)
        .build()
    }
  }
}
