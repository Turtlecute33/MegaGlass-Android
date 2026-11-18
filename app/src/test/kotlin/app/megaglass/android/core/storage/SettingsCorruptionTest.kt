package app.megaglass.android.core.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SettingsCorruptionTest {
    @Test fun aCorruptFileOpensEmptyWithTheLostMarker() = runBlocking {
        val dir = Files.createTempDirectory("settings").toFile()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val file = File(dir, "settings.preferences_pb").apply { writeBytes(byteArrayOf(0x0a, 0x7f, 0x01, 0x02)) }
            val store = PreferenceDataStoreFactory.create(corruptionHandler = settingsCorruptionHandler, scope = scope) { file }
            val prefs = store.data.first()
            assertTrue(prefs[SETTINGS_LOST] == true)
            assertEquals(1, prefs.asMap().size)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
