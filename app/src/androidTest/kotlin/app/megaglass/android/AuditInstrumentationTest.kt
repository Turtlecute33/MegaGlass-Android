package app.megaglass.android

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import app.megaglass.android.core.mega.await
import app.megaglass.android.core.mega.awaitResult
import app.megaglass.android.core.storage.SessionStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaCancelToken
import nz.mega.sdk.MegaError
import nz.mega.sdk.MegaHandleList
import nz.mega.sdk.MegaRequest
import nz.mega.sdk.MegaRequestListenerInterface
import nz.mega.sdk.MegaSearchFilter
import nz.mega.sdk.MegaSearchPage
import nz.mega.sdk.MegaStringList
import nz.mega.sdk.MegaUploadOptions
import nz.mega.sdk.MegaTransfer
import nz.mega.sdk.MegaTransferListenerInterface
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/** Uses a blank emulator, no MEGA credentials and no server mutations. */
class AuditInstrumentationTest {
    private val api: MegaApiJava get() = MegaGlassApp.graph.mega.api

    private fun withSessionStore(test: (SessionStore, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "audit-session-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = dir
        }
        try { test(SessionStore(isolated), dir) } finally { dir.deleteRecursively() }
    }

    @Test fun encryptedSessionRoundTripsAndReplacesAtomically() = withSessionStore { store, dir ->
        store.save("audit-placeholder-one")
        assertEquals("audit-placeholder-one", SessionStore(object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getNoBackupFilesDir() = dir
        }).load())
        assertFalse(File(dir, "session.bin").readText().contains("audit-placeholder"))
        store.save("audit-placeholder-two")
        assertEquals("audit-placeholder-two", store.load())
        File(dir, "session.tmp").writeText("leftover")
        store.clear()
        assertNull(store.load())
        assertFalse(File(dir, "session.tmp").exists())
    }

    @Test fun truncatedAndTamperedSessionsFailClosed() = withSessionStore { store, dir ->
        val file = File(dir, "session.bin")
        file.writeText("AAAA")
        assertNull(store.load())
        store.save("audit-placeholder")
        val bytes = android.util.Base64.decode(file.readText(), android.util.Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeText(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        assertNull(store.load())
    }

    @Test fun unreadableSessionStorageIsNotTreatedAsAnAbsentSession() = withSessionStore { store, dir ->
        File(dir, "session.bin").mkdir()
        try {
            store.load()
            fail("An unreadable session file must report an IO error")
        } catch (_: IOException) { }
        assertTrue(File(dir, "session.bin").exists())
    }

    @Test fun failedSessionCleanupIsReported() = withSessionStore { store, dir ->
        val leftover = File(dir, "session.tmp").apply { mkdir() }
        File(leftover, "remaining").writeText("audit-placeholder")
        try {
            store.clear()
            fail("Failed cleanup must keep the application in a recovery state")
        } catch (_: IOException) { }
        assertTrue(leftover.exists())
    }

    private class Request : MegaRequest(0, false) {
        override fun getType() = -1
    }
    private class Error(private val code: Int) : MegaError(0, false) {
        override fun getErrorCode() = code
        override fun getErrorString() = "Audit error"
    }

    @Test fun cancelledRequestsIgnoreLateProgressAndCompletion() = runBlocking {
        var listener: MegaRequestListenerInterface? = null
        var updates = 0
        var completed = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            api.await(onUpdate = { updates++ }) { listener = it; addRequestListener(it) }
            completed = true
        }
        fun registered(): Boolean {
            val field = MegaApiJava::class.java.getDeclaredField("activeRequestListeners").apply { isAccessible = true }
            val delegates = field.get(null) as Set<*>
            return synchronized(delegates) { delegates.any { delegate ->
                delegate!!.javaClass.getDeclaredMethod("getUserListener").apply { isAccessible = true }.invoke(delegate) === listener
            } }
        }
        assertTrue(registered())
        job.cancelAndJoin()
        assertFalse(registered())
        listener!!.onRequestUpdate(api, Request())
        listener!!.onRequestFinish(api, Request(), Error(MegaError.API_OK))
        assertEquals(0, updates)
        assertFalse(completed)
    }

    @Test fun requestErrorsAreDeliveredToTheCaller() = runBlocking {
        val result = api.awaitResult { it.onRequestFinish(this, Request(), Error(MegaError.API_EACCESS)) }
        assertEquals(MegaError.API_EACCESS, result.first)
        try {
            api.await { throw IOException("Synchronous dispatch failure") }
            fail("Dispatch errors must reach the caller")
        } catch (e: IOException) { assertEquals("Synchronous dispatch failure", e.message) }
    }

    @Test fun failedUploadsWithRetriableSourcesPreserveTheirBytes() = runBlocking {
        assertEquals("This test must run without a MEGA account", 0, api.isLoggedIn)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("audit-source", ".txt", context.cacheDir).apply { writeText("audit-placeholder") }
        val finished = CompletableDeferred<Int>()
        val token = MegaCancelToken.createInstance()
        val options = MegaUploadOptions.createInstance().apply {
            setIsSourceTemporary(false)
            setAppData("ul:tmp")
        }
        try {
            // A missing destination while logged out cannot create anything on the server.
            api.startUpload(source.absolutePath, null, token, options, object : MegaTransferListenerInterface {
                override fun onTransferStart(api: MegaApiJava, transfer: MegaTransfer) {}
                override fun onTransferUpdate(api: MegaApiJava, transfer: MegaTransfer) {}
                override fun onTransferTemporaryError(api: MegaApiJava, transfer: MegaTransfer, error: MegaError) {}
                override fun onTransferFinish(api: MegaApiJava, transfer: MegaTransfer, error: MegaError) { finished.complete(error.errorCode) }
                override fun onTransferData(api: MegaApiJava, transfer: MegaTransfer, data: ByteArray) = false
                override fun onFolderTransferUpdate(api: MegaApiJava, transfer: MegaTransfer, stage: Int,
                    folders: Long, created: Long, files: Long, currentFolder: String?, currentFileLeafName: String?) {}
            })
            assertNotEquals(MegaError.API_OK, withTimeout(10_000) { finished.await() })
            assertEquals("audit-placeholder", source.readText())
        } finally { token.cancel(); source.delete() }
    }

    @Test fun sdkFactoriesOwnAndReleaseTheirNativeAllocations() {
        repeat(50) {
            val objects = listOf(MegaSearchFilter.createInstance(), MegaSearchPage.createInstance(0, 10),
                MegaHandleList.createInstance(), MegaStringList.createInstance(), MegaCancelToken.createInstance(), MegaUploadOptions.createInstance(),
                api.sets, api.getSetElements(MegaApiJava.INVALID_HANDLE))
            for (value in objects) {
                val owner = value.javaClass.getDeclaredField("swigCMemOwn").apply { isAccessible = true }
                assertTrue(value.javaClass.simpleName, owner.getBoolean(value))
                value.javaClass.getDeclaredMethod("delete").apply { isAccessible = true }.invoke(value)
                val pointer = value.javaClass.getDeclaredField("swigCPtr").apply { isAccessible = true }
                assertEquals(0, pointer.getLong(value))
            }
        }
    }
}
