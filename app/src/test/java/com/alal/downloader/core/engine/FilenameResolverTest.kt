package com.alal.downloader.core.engine

import org.junit.Assert.*
import org.junit.Test

class FilenameResolverTest {
    @Test fun dispositionWins() = assertEquals("real.zip", FilenameResolver.resolve("https://example.com/other.pdf", "attachment; filename=real.zip", "application/pdf"))
    @Test fun decodedUrlWithExtensionWins() = assertEquals("my file.zip", FilenameResolver.resolve("https://example.com/my%20file.zip", mime = "application/pdf"))
    @Test fun hostTimestampAndMime() = assertTrue(FilenameResolver.resolve("https://example.com/get", mime = "application/pdf", now = 0).matches(Regex("example.com-\\d{8}-\\d{6}\\.pdf")))
    @Test fun unknownMimeHasNoExtension() = assertTrue(FilenameResolver.resolve("https://example.com/", now = 0).matches(Regex("example.com-\\d{8}-\\d{6}")))
    @Test fun octetStreamUsesHostNotDownloadBin() {
        val name = FilenameResolver.resolve("www.example.com", mime = "application/octet-stream", now = 0)
        assertTrue(name.startsWith("www.example.com-"))
        assertTrue(name.endsWith(".bin"))
        assertNotEquals("download.bin", name)
    }
}