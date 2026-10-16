package app.megaglass.android.feature.links

import org.junit.Assert.*
import org.junit.Test

class MegaLinkTest {
    @Test fun supportedHostsNormalizeToHttps() {
        assertEquals(MegaLink.File("https://mega.nz/file/handle#key"), parseMegaLink("https://mega.nz:443/file/handle#key"))
        for (host in listOf("mega.nz", "www.mega.nz", "mega.co.nz", "www.mega.io")) {
            assertEquals(MegaLink.File("https://mega.nz/file/handle#key"), parseMegaLink("http://$host/file/handle#key"))
        }
    }

    @Test fun unsupportedOriginsAndMalformedPathsAreRejected() {
        for (url in listOf(
            "https://mega.nz.evil.test/file/handle#key", "https://evil.test/#P!secret",
            "https://mega.nz@evil.test/file/handle#key", "https://name@mega.nz/file/handle#key",
            "https://mega.nz:1234/file/handle#key", "ftp://mega.nz/file/handle#key",
            "https://mega.nz/unrelated/file/handle#key", "https://mega.nz/file/",
            "https://mega.nz/#!!key", "https://mega.nz/file/../secret#key",
        )) assertTrue(url, parseMegaLink(url) is MegaLink.Invalid)
    }

    @Test fun sharedTextsOpenTheirFirstMegaLink() {
        assertEquals("https://mega.nz/folder/root#key", findMegaLink("Look:\nhttps://evil.test/file/a#b https://mega.nz/folder/root#key mega://#!h!k"))
        assertEquals("mega://#!h!k", findMegaLink("  mega://#!h!k"))
        assertNull(findMegaLink("https://mega.nz.evil.test/file/handle#key and some text"))
        assertNull(findMegaLink(""))
    }

    @Test fun folderSubpathsRemainSeparateFromTheSdkUrl() {
        assertEquals(MegaLink.Folder("https://mega.nz/folder/root#key", "child", null),
            parseMegaLink("https://mega.nz/folder/root#key/folder/child"))
        assertEquals(MegaLink.Folder("https://mega.nz/folder/root#key", null, "child"),
            parseMegaLink("https://mega.nz/folder/root#key/file/child"))
    }

    @Test fun legacyAndCustomSchemeLinksStillWork() {
        assertEquals(MegaLink.File("https://mega.nz/#!handle!key"), parseMegaLink("mega://#!handle!key"))
        assertEquals(MegaLink.Folder("https://mega.nz/#F!root!key", "child", null), parseMegaLink("https://mega.nz/#F!root!key!child"))
        assertTrue(parseMegaLink("https://mega.nz/#P!encrypted") is MegaLink.Protected)
        assertTrue(parseMegaLink("mega://collection/album#key") is MegaLink.Album)
    }

    @Test fun missingKeysCanBeSuppliedWithoutLosingTheFolderPosition() {
        val link = parseMegaLink("https://mega.nz/folder/root#")
        assertTrue(link.needsKey())
        assertEquals(MegaLink.Folder("https://mega.nz/folder/root#key", null, null), link.withKey(" #key "))
        assertTrue(parseMegaLink("https://mega.nz/#!handle").needsKey())
    }
}
