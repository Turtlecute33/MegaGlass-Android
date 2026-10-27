package app.megaglass.android.core.util

import app.megaglass.android.R
import app.megaglass.android.core.mega.MegaException
import app.megaglass.android.core.mega.TwoFactorRequired
import app.megaglass.android.core.storage.NoSpaceException
import app.megaglass.android.core.transfers.AlreadyPreparingException
import nz.mega.sdk.MegaError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class UserMessageTest {
    private fun sdk(code: Int) = MegaException(code, "SDK text", fromSdk = true)

    @Test fun sdkCodesHaveOwnText() {
        val expected = mapOf(
            MegaError.API_EOVERQUOTA to R.string.error_quota,
            MegaError.API_EGOINGOVERQUOTA to R.string.error_quota,
            MegaError.API_EACCESS to R.string.error_access,
            MegaError.API_ENOENT to R.string.error_not_found,
            MegaError.API_EEXIST to R.string.error_exists,
            MegaError.API_ETOOMANY to R.string.error_too_many,
            MegaError.API_ERATELIMIT to R.string.error_too_many,
            MegaError.API_ETOOMANYCONNECTIONS to R.string.error_too_many,
            MegaError.API_EAGAIN to R.string.error_network,
            MegaError.API_ETEMPUNAVAIL to R.string.error_network,
            MegaError.API_ESSL to R.string.error_ssl,
            MegaError.API_EREAD to R.string.error_io,
            MegaError.API_EWRITE to R.string.error_io,
        )
        for ((code, text) in expected) {
            assertEquals("code $code", text, errorText(sdk(code)))
            assertNull("code $code", appText(sdk(code)))
        }
    }

    @Test fun otherSdkCodesUseTheFallback() {
        assertNull(errorText(sdk(MegaError.API_EINTERNAL)))
        assertNull(appText(sdk(MegaError.API_EINTERNAL)))
    }

    @Test fun appErrorsKeepTheirText() {
        assertEquals("The destination already contains an item named a", appText(MegaException(MegaError.API_EEXIST, "The destination already contains an item named a")))
        assertEquals("Downloaded file is incomplete", appText(IOException("Downloaded file is incomplete")))
        assertEquals("This item is not available", appText(IllegalStateException("This item is not available")))
        assertEquals("This file is already being prepared", appText(AlreadyPreparingException()))
        assertEquals("Two-factor authentication code required", appText(TwoFactorRequired()))
    }

    @Test fun systemAndLibraryErrorsDoNotShowTheirText() {
        assertNull(appText(FileNotFoundException("/data/x: open failed: EACCES (Permission denied)")))
        assertNull(appText(IOException("write failed", RuntimeException("cause"))))
        assertNull(appText(IllegalArgumentException("Expected a value")))
        assertNull(appText(NoSpaceException()))
        assertEquals(R.string.error_io, errorText(FileNotFoundException("x")))
        assertEquals(R.string.error_no_space, errorText(NoSpaceException()))
        assertEquals(R.string.error_network, errorText(UnknownHostException("g.api.mega.co.nz")))
        assertEquals(R.string.error_network, errorText(ConnectException("refused")))
        assertEquals(R.string.error_network, errorText(SocketTimeoutException("timeout")))
    }

    @Test fun errorsWithNoMessageUseTheFallback() {
        assertNull(appText(IOException()))
        assertNull(appText(IllegalStateException()))
        assertNull(appText(MegaException(MegaError.API_EARGS, " ")))
        assertNull(errorText(NullPointerException()))
        assertNull(appText(NullPointerException()))
    }
}
