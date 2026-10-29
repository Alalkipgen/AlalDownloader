package com.alal.downloader.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** Links pulled from made-up share texts. */
class SharedLinksTest {
    private val megaUp = "https://megaup.net/abc123/Verity.2026.720p.AMZN.WEB-DL.(Hmtv).NC.mp4"

    @Test fun telegramTitleAndLink() {
        assertEquals(listOf(megaUp), SharedLinks.extract("Verity (2026) 720p\n$megaUp\n\n#Movie #Thriller"))
        assertEquals(listOf(megaUp), SharedLinks.extract("ဇာတ်ကား - Verity (2026) - $megaUp။"))
    }

    @Test fun facebookAndChromeShares() {
        assertEquals(listOf("https://www.facebook.com/share/p/abc123/"),
            SharedLinks.extract("Movie night! See this post https://www.facebook.com/share/p/abc123/"))
        assertEquals(listOf(megaUp), SharedLinks.extract(megaUp))
        assertEquals(listOf("https://files.example.com/d/abc123"), SharedLinks.extract("Files – Example\nhttps://files.example.com/d/abc123"))
    }

    @Test fun severalLinksKeepOrderWithoutDuplicates() {
        val one = "https://megaup.net/aaa111/Show.S01E01.mp4"
        val two = "http://mirror.example.org/bbb222/Show.S01E02.mkv"
        assertEquals(listOf(one, two), SharedLinks.extract("Part 1: $one\nPart 2: $two, again $one."))
    }

    @Test fun trailingPunctuationIsDropped() {
        assertEquals(listOf("https://example.com/files/movie.zip"), SharedLinks.extract("Download here: https://example.com/files/movie.zip."))
        assertEquals(listOf("https://example.com/a"), SharedLinks.extract("Mirror (https://example.com/a), thanks!"))
        assertEquals(listOf("https://example.com/b"), SharedLinks.extract("[https://example.com/b]"))
        assertEquals(listOf("https://example.com/c"), SharedLinks.extract("“https://example.com/c”"))
        assertEquals(listOf("https://example.com/d"), SharedLinks.extract("'https://example.com/d'…"))
        assertEquals(listOf("https://example.com/e"), SharedLinks.extract("Link:https://example.com/e?!"))
    }

    @Test fun closingBracketStaysWhenTheLinkOpenedOne() {
        assertEquals(listOf(megaUp), SharedLinks.extract("($megaUp)"))
        assertEquals(listOf(megaUp), SharedLinks.extract("Get it: $megaUp."))
        assertEquals(listOf("https://en.wikipedia.org/wiki/Verity_(film)"), SharedLinks.extract("See https://en.wikipedia.org/wiki/Verity_(film)!"))
    }

    @Test fun spacesOfEveryKindEndALink() {
        assertEquals(listOf("https://example.com/f", "https://example.com/g"),
            SharedLinks.extract("A\u00A0https://example.com/f\u200B B\u3000https://example.com/g\u2028"))
        assertEquals(listOf("https://example.com/H"), SharedLinks.extract("HTTPS://Example.com/H"))
    }

    @Test fun noLinkOrAnotherScheme() {
        assertEquals(emptyList<String>(), SharedLinks.extract(null))
        assertEquals(emptyList<String>(), SharedLinks.extract("  "))
        assertEquals(emptyList<String>(), SharedLinks.extract("Watch Verity (2026) tonight"))
        assertEquals(emptyList<String>(), SharedLinks.extract("magnet:?xt=urn:btih:abc123 ftp://example.com/x file:///sdcard/a.mp4"))
        assertEquals(emptyList<String>(), SharedLinks.extract("https:// and http://"))
    }
}
