package app.megaglass.android

import android.provider.Settings
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest

/** What the SDK tells MEGA about the device. Uses a blank emulator, no MEGA credentials and no server requests. */
class SdkPrivacyTest {
    private val mega get() = MegaGlassApp.graph.mega

    /** MEGAGLASS-PRIV-001: a random device ID, not a hash of ANDROID_ID, and a new one after a logout. */
    @Test fun deviceIdIsRandomAndChangesAfterLogout() = runBlocking {
        assertEquals("This test must run without a MEGA account", 0, mega.api.isLoggedIn)
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val androidId = Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID).orEmpty()
        val androidIdHash = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        mega.useDeviceId()
        val first = mega.api.deviceId.orEmpty()
        assertFalse(first.isEmpty())
        assertNotEquals(androidIdHash, first)
        mega.useDeviceId()
        assertEquals("The ID stays until a logout", first, mega.api.deviceId)
        mega.clearCaches()
        mega.useDeviceId()
        assertNotEquals(first, mega.api.deviceId)
    }
}
