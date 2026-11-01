package ch.asds.mobile

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on isolated emulator/test installation, never on the live device. */
@RunWith(AndroidJUnit4::class)
class R1RoomEncryptionTest {
    @Test fun encryptedValueRoundTripsWithoutPlaintextInSqliteRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, R1RoomDatabase::class.java).build()
        try {
            val store = SecureStore(context)
            val sensitive = """{"description":"PERSONENBEZOGENER_TEST"}"""
            val ciphertext = store.encryptForRoom(sensitive)
            assertFalse(ciphertext.contains("PERSONENBEZOGENER_TEST"))
            db.documents().put(R1SecureDocument("r1_test_document", ciphertext, 1L))
            val record = db.documents().find("r1_test_document")
            assertNotNull(record)
            assertEquals(sensitive, store.decryptFromRoom(record!!.encryptedJson))
            db.documents().delete("r1_test_document")
            assertNull(db.documents().find("r1_test_document"))
        } finally {
            db.close()
        }
    }
}
