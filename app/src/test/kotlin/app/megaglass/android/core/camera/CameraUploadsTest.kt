package app.megaglass.android.core.camera

import app.megaglass.android.core.storage.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUploadsTest {
    @Test fun uploadNowWithChargingOnlyIsNotExpedited() {
        val request = CameraUploads.nowRequest(AppSettings(cuRequireCharging = true))
        assertTrue(request.workSpec.constraints.requiresCharging())
        assertFalse(request.workSpec.expedited)
    }

    @Test fun uploadNowWithoutChargingOnlyIsExpedited() {
        assertTrue(CameraUploads.nowRequest(AppSettings()).workSpec.expedited)
    }
}
