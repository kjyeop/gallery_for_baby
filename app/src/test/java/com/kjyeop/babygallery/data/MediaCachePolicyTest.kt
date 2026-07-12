package com.kjyeop.babygallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaCachePolicyTest {
    @Test
    fun `thumbnail size is rounded up to a reusable 64 pixel bucket`() {
        assertEquals(64, thumbnailSizeBucket(1))
        assertEquals(64, thumbnailSizeBucket(64))
        assertEquals(128, thumbnailSizeBucket(65))
        assertEquals(384, thumbnailSizeBucket(336))
    }

    @Test
    fun `thumbnail size is capped to protect cache budgets`() {
        assertEquals(2_048, thumbnailSizeBucket(10_000))
    }

    @Test
    fun `memory budgets follow the configured memory class limits`() {
        assertEquals(
            CacheBudgets(thumbnailMemoryKb = 8 * 1024, fullScreenMemoryKb = 16 * 1024),
            cacheBudgets(memoryClassMb = 32),
        )
        assertEquals(
            CacheBudgets(thumbnailMemoryKb = 32 * 1024, fullScreenMemoryKb = 96 * 1024),
            cacheBudgets(memoryClassMb = 1_024),
        )
    }

    @Test
    fun `thumbnail cache key changes when the source media version changes`() {
        val original = ThumbnailCacheKey(
            type = GalleryMediaType.Image,
            id = 12L,
            uri = "content://media/images/12",
            dateModifiedSeconds = 100L,
            sizeBytes = 1_000L,
            sizeBucketPx = 384,
        )

        assertNotEquals(original, original.copy(dateModifiedSeconds = 101L))
        assertNotEquals(original, original.copy(sizeBytes = 1_001L))
    }
}
