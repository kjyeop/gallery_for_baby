package com.kjyeop.babygallery.data

import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MotionPhotoMetadataTest {
    @Test
    fun `recognises current and legacy Android motion photo XMP`() {
        assertTrue(MotionPhotoMetadata.isEmbeddedMotionPhoto("<rdf Camera:MotionPhoto=\"1\"/>"))
        assertTrue(MotionPhotoMetadata.isEmbeddedMotionPhoto("<Camera:MicroVideo>1</Camera:MicroVideo>"))
        assertFalse(MotionPhotoMetadata.isEmbeddedMotionPhoto("<rdf Camera:MotionPhoto=\"0\"/>"))
    }

    @Test
    fun `returns Apple identifier only when required marker is present`() {
        val identifier = "6D6B8B76-08BA-4D9A-8382-2AA0EFAB599B"
        val marked = "com.apple.quicktime.content.identifier\u0000$identifier"
            .toByteArray(StandardCharsets.ISO_8859_1)
        val unmarked = identifier.toByteArray(StandardCharsets.ISO_8859_1)

        assertEquals(
            identifier,
            MotionPhotoMetadata.findAppleContentIdentifier(marked, requireIdentifierMarker = true),
        )
        assertEquals(
            identifier,
            MotionPhotoMetadata.findAppleContentIdentifier(unmarked, requireIdentifierMarker = false),
        )
        assertEquals(
            null,
            MotionPhotoMetadata.findAppleContentIdentifier(unmarked, requireIdentifierMarker = true),
        )
    }

    @Test
    fun `matches only the exact Live Photo companion identifier`() {
        val identifier = "6D6B8B76-08BA-4D9A-8382-2AA0EFAB599B"
        val companions = mapOf(identifier to "live-photo.mov")

        assertEquals(
            "live-photo.mov",
            MotionPhotoMetadata.findMatchingCompanion(identifier.lowercase(), companions),
        )
        assertNull(
            MotionPhotoMetadata.findMatchingCompanion(
                "00000000-0000-0000-0000-000000000000",
                companions,
            ),
        )
    }

    @Test
    fun `caches a motion-photo probe per media key`() {
        val cache = MotionPhotoProbeCache<String, String>()
        var detectCount = 0

        assertEquals(
            "embedded",
            cache.getOrPut("content://images/1") {
                detectCount += 1
                "embedded"
            },
        )
        assertEquals(
            "embedded",
            cache.getOrPut("content://images/1") {
                detectCount += 1
                "none"
            },
        )
        assertEquals(1, detectCount)
    }

    @Test
    fun `defers and caches Apple companion indexing until playback lookup`() = runBlocking {
        val identifier = "6D6B8B76-08BA-4D9A-8382-2AA0EFAB599B"
        val readCandidates = mutableListOf<String>()
        val index = LazyAppleCompanionIndex(
            candidates = listOf("first.mov", "live.mov"),
            readIdentifier = { candidate ->
                readCandidates += candidate
                if (candidate == "live.mov") identifier else null
            },
        )

        assertTrue(readCandidates.isEmpty())
        assertEquals("live.mov", index.find(identifier.lowercase()))
        assertEquals(listOf("first.mov", "live.mov"), readCandidates)
        assertNull(index.find("00000000-0000-0000-0000-000000000000"))
        assertEquals(listOf("first.mov", "live.mov"), readCandidates)
    }

    @Test
    fun `cancelling Apple companion indexing stops before the next candidate`() = runBlocking {
        val firstReadStarted = CountDownLatch(1)
        val releaseFirstRead = CountDownLatch(1)
        val readCandidates = mutableListOf<String>()
        val index = LazyAppleCompanionIndex(
            candidates = listOf("first.mov", "second.mov"),
            readIdentifier = { candidate ->
                readCandidates += candidate
                if (candidate == "first.mov") {
                    firstReadStarted.countDown()
                    releaseFirstRead.await(2, TimeUnit.SECONDS)
                }
                null
            },
        )

        val lookup = async(Dispatchers.Default) {
            index.find("6D6B8B76-08BA-4D9A-8382-2AA0EFAB599B")
        }
        assertTrue(firstReadStarted.await(1, TimeUnit.SECONDS))
        lookup.cancel()
        releaseFirstRead.countDown()
        lookup.join()

        assertTrue(lookup.isCancelled)
        assertEquals(listOf("first.mov"), readCandidates)
    }
}
