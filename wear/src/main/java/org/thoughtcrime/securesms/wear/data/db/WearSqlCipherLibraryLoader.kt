package org.thoughtcrime.securesms.wear.data.db

/**
 * Loads the native SQLCipher library exactly once before any [WearCacheDatabase] is opened.
 * Mirrors the app module's `SqlCipherLibraryLoader`.
 */
internal object WearSqlCipherLibraryLoader {
  @Volatile
  private var loaded = false
  private val LOCK = Any()

  fun load() {
    if (!loaded) {
      synchronized(LOCK) {
        if (!loaded) {
          System.loadLibrary("sqlcipher")
          loaded = true
        }
      }
    }
  }
}
