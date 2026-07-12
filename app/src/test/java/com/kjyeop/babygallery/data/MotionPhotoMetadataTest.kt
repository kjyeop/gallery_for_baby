package com.kjyeop.babygallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

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
}
