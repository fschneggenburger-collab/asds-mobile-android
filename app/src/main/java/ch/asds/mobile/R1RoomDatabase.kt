package ch.asds.mobile

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The R1 canonical local store. Values are ALWAYS AES-GCM encrypted with
 * Android Keystore (SecureStore); Room never contains raw client records.
 *
 * Version 1 is additive: the original 2.0.0 SecureStore prefs, WebView data,
 * downloads and legacy draft/queue formats remain entirely untouched.
 * No destructive database migration is enabled.
 */
@Entity(tableName = "r1_secure_document")
data class R1SecureDocument(
    @PrimaryKey val documentKey: String,
    val encryptedJson: String,
    val modifiedAtEpochMs: Long
)

@Dao
interface R1DocumentDao {
    @Query("SELECT * FROM r1_secure_document WHERE documentKey = :key LIMIT 1")
    suspend fun find(key: String): R1SecureDocument?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(document: R1SecureDocument)

    @Query("DELETE FROM r1_secure_document WHERE documentKey = :key")
    suspend fun delete(key: String)
}

@Database(entities = [R1SecureDocument::class], version = 1, exportSchema = true)
abstract class R1RoomDatabase : RoomDatabase() {
    abstract fun documents(): R1DocumentDao

    companion object {
        @Volatile private var database: R1RoomDatabase? = null

        fun open(context: Context): R1RoomDatabase =
            database ?: synchronized(this) {
                database ?: Room.databaseBuilder(
                    context.applicationContext,
                    R1RoomDatabase::class.java,
                    "asds_r1_secure_offline.db"
                )
                // Explicitly DO NOT allow destructive migration: offline work
                // must not disappear after an application version change.
                .build().also { database = it }
            }
    }
}
