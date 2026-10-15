package app.megaglass.android.core.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SafeFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun untrustedNamesStayInsideTheirDirectory() {
        val root = temp.newFolder()
        for (name in listOf("", ".", "..", "../../secret", "/tmp/secret", "..\\secret", "a\u0000b")) {
            val safe = localFileName(name)
            assertEquals(root.canonicalFile, File(root, safe).canonicalFile.parentFile)
            assertFalse(safe.contains('\u0000'))
        }
    }

    @Test fun unicodeAndSpecialCharactersArePreserved() {
        val name = "日本語 è 🐈 + # %.txt"
        assertEquals(name, localFileName(name))
    }

    @Test fun aCompleteLengthDoesNotProveADownloadFinished() {
        val file = temp.newFile("data")
        file.writeText("abcd")
        assertFalse(VerifiedFiles.contains(file, 4))
        VerifiedFiles.mark(file)
        assertTrue(VerifiedFiles.contains(file, 4))
        assertFalse(VerifiedFiles.contains(file, 5))
        VerifiedFiles.forget(file)
        assertFalse(VerifiedFiles.contains(file, 4))
    }

    @Test fun zeroByteFilesNeedCompletionMetadata() {
        val file = temp.newFile("empty")
        assertFalse(VerifiedFiles.contains(file, 0))
        VerifiedFiles.mark(file)
        assertTrue(VerifiedFiles.contains(file, 0))
        file.delete()
        assertFalse(VerifiedFiles.contains(file, 0))
    }

    @Test fun modifiedCopiesAreNotReused() {
        val file = temp.newFile()
        file.writeText("first")
        VerifiedFiles.mark(file)
        file.writeText("other")
        assertTrue(file.setLastModified(file.lastModified() + 2_000))
        assertFalse(VerifiedFiles.contains(file, 5))
    }

    @Test fun longFileNamesCanBeVerified() {
        val file = File(temp.root, "a".repeat(250))
        file.writeText("ok")
        VerifiedFiles.mark(file)
        assertTrue(VerifiedFiles.contains(file, 2))
    }

    @Test fun failedCommitPreservesThePreviousFile() {
        val target = temp.newFile("previous")
        target.writeText("keep me")
        try {
            replaceFile(File(temp.root, "missing"), target)
            fail("A missing source must fail")
        } catch (_: IOException) { }
        assertEquals("keep me", target.readText())
    }

    @Test fun invalidDestinationIsReported() {
        val file = temp.newFile("not-a-directory")
        try {
            file.ensureDirectory()
            fail("A file cannot be a download directory")
        } catch (_: IOException) { }
    }

    @Test fun collidingNamesPreserveExtensionsAndDotFiles() {
        val occupied = setOf("photo.jpg", "photo (1).jpg", ".env", "folder.ext")
        assertEquals("photo (2).jpg", availableName("photo.jpg", occupied = occupied::contains))
        assertEquals(".env (1)", availableName(".env", occupied = occupied::contains))
        assertEquals("folder.ext (1)", availableName("folder.ext", folder = true, occupied = occupied::contains))
        assertEquals("日本語.txt", availableName("日本語.txt", occupied = occupied::contains))
    }

    @Test fun avatarKeysDoNotCollideWhenJavaStringHashesDo() {
        val a = "Aa@example.com"
        val b = "BB@example.com"
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(privateCacheKey(a), privateCacheKey(b))
    }

    @Test fun imageCacheEvictionProtectsRecentReadersAndSdkWrites() {
        val now = System.currentTimeMillis()
        val old = temp.newFile("old.jpg").apply { writeBytes(ByteArray(10)); setLastModified(now - 600_000) }
        val recent = temp.newFile("recent.jpg").apply { writeBytes(ByteArray(10)) }
        val active = temp.newFile("active.part").apply { writeBytes(ByteArray(10)); setLastModified(now - 600_000) }
        trimImageCache(temp.root, 20, now)
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(active.exists())
    }

    @Test fun uploadCleanupCannotDeleteUserFilesOutsideItsPrivateRoots() {
        val root = temp.newFolder("uploads")
        val original = temp.newFile("original.jpg").apply { writeText("keep") }
        try {
            deleteUploadCopy(original, listOf(root))
            fail("The original must not be deleted")
        } catch (_: IllegalArgumentException) { }
        assertEquals("keep", original.readText())
    }

    @Test fun confirmedUploadCleanupRemovesItsCopyAndEmptyContainerOnly() {
        val root = temp.newFolder("uploads")
        val container = File(root, "one").apply { mkdir() }
        val copy = File(container, "copy.jpg").apply { writeText("copy") }
        deleteUploadCopy(copy, listOf(root))
        assertFalse(copy.exists())
        assertFalse(container.exists())
        assertTrue(root.exists())
    }

    @Test fun anUploadSymlinkCannotDeleteAnOriginalOutsideThePrivateRoot() {
        val root = temp.newFolder("uploads")
        val original = temp.newFile("original").apply { writeText("keep") }
        val link = File(root, "alias")
        java.nio.file.Files.createSymbolicLink(link.toPath(), original.toPath())
        try {
            deleteUploadCopy(link, listOf(root))
            fail("The linked original must be preserved")
        } catch (_: IllegalArgumentException) { }
        assertEquals("keep", original.readText())
    }

    @Test fun providerPathsCannotExposePrivateDataOrItsAncestors() {
        val privateRoot = File(temp.root, "private").apply { mkdir() }
        assertTrue(overlapsPrivateStorage(File(privateRoot, "session.bin"), privateRoot))
        assertTrue(overlapsPrivateStorage(privateRoot, privateRoot))
        assertTrue(overlapsPrivateStorage(temp.root, privateRoot))
        assertFalse(overlapsPrivateStorage(File(temp.root, "private-other"), privateRoot))
        assertFalse(overlapsPrivateStorage(File(temp.root, "downloads"), privateRoot))
    }
}
