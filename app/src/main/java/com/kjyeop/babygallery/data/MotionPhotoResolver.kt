package com.kjyeop.babygallery.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

/** A playable video associated with a still image. */
sealed interface MotionPhotoSource {
    /** A Galaxy/Android Motion Photo whose video is appended to the still image file. */
    data class Embedded(
        val uri: Uri,
        val mimeType: String?,
    ) : MotionPhotoSource

    /** An iPhone Live Photo MOV that is paired to the still image by its content identifier. */
    data class CompanionVideo(val uri: Uri) : MotionPhotoSource
}

/**
 * The inexpensive result of inspecting a still image. Apple companion-MOV matching is intentionally
 * deferred until playback is requested so it cannot contend with photo paging.
 */
internal sealed interface MotionPhotoProbe {
    data object None : MotionPhotoProbe
    data class Embedded(val source: MotionPhotoSource.Embedded) : MotionPhotoProbe
    data class AppleLivePhoto(val contentIdentifier: String) : MotionPhotoProbe
}

internal data class MotionPhotoImageMetadata(
    val xmp: String?,
    val make: String?,
    val makerNote: ByteArray?,
)

/** Isolates blocking MediaStore metadata access so resolver behavior can be unit tested. */
internal interface MotionPhotoMetadataReader {
    fun readImageMetadata(uri: Uri): MotionPhotoImageMetadata?

    fun readQuickTimeContentIdentifier(uri: Uri): String?
}

/**
 * Resolves a photo's optional motion video without ever copying or changing gallery media.
 *
 * Image probing is cached per URI. iPhone companion-video indexing starts only after the user
 * opens a detected Live Photo, and checks for cancellation between each candidate file.
 */
class MotionPhotoResolver internal constructor(
    private val libraryItems: List<GalleryMedia>,
    private val metadataReader: MotionPhotoMetadataReader,
) {
    constructor(context: Context, libraryItems: List<GalleryMedia>) : this(
        libraryItems = libraryItems,
        metadataReader = ContentResolverMotionPhotoMetadataReader(context.contentResolver),
    )

    private val probes = MotionPhotoProbeCache<Uri, MotionPhotoProbe>()
    private val appleCompanionIndex = LazyAppleCompanionIndex(
        candidates = libraryItems
            .filter { it.isPossibleAppleLivePhotoVideo() }
            .map(GalleryMedia::uri),
        readIdentifier = metadataReader::readQuickTimeContentIdentifier,
    )

    /** Reads only the selected still image's metadata; it never scans companion MOV files. */
    internal suspend fun probe(image: GalleryMedia): MotionPhotoProbe {
        if (image.type != GalleryMediaType.Image) return MotionPhotoProbe.None
        probes[image.uri]?.let { return it }

        return withContext(Dispatchers.IO) {
            probes.getOrPut(image.uri) { detectProbe(image) }
        }
    }

    /** Resolves a source after the active photo is displayed, before the user starts playback. */
    internal suspend fun resolveForPlayback(probe: MotionPhotoProbe): MotionPhotoSource? = when (probe) {
        MotionPhotoProbe.None -> null
        is MotionPhotoProbe.Embedded -> probe.source
        is MotionPhotoProbe.AppleLivePhoto -> withContext(Dispatchers.IO) {
            appleCompanionIndex.find(probe.contentIdentifier)?.let(MotionPhotoSource::CompanionVideo)
        }
    }

    private fun detectProbe(image: GalleryMedia): MotionPhotoProbe {
        val metadata = metadataReader.readImageMetadata(image.uri) ?: return MotionPhotoProbe.None

        if (MotionPhotoMetadata.isEmbeddedMotionPhoto(metadata.xmp)) {
            return MotionPhotoProbe.Embedded(
                MotionPhotoSource.Embedded(image.uri, image.mimeType),
            )
        }

        val identifier = metadata.appleLivePhotoContentIdentifier() ?: return MotionPhotoProbe.None
        return MotionPhotoProbe.AppleLivePhoto(identifier)
    }

    /** Drops all disposable metadata state when the library changes or the process is trimmed. */
    internal suspend fun clearCaches() {
        probes.clear()
        appleCompanionIndex.clear()
    }

}

/** Keeps per-media probes reusable without retaining any decoded image data. */
internal class MotionPhotoProbeCache<Key : Any, Value : Any>(
    private val maxEntries: Int = MOTION_PHOTO_PROBE_CACHE_ENTRIES,
) {
    private val values = object : LinkedHashMap<Key, Value>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Value>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    operator fun get(key: Key): Value? = values[key]

    fun getOrPut(key: Key, detect: () -> Value): Value {
        get(key)?.let { return it }
        val detected = detect()
        synchronized(this) {
            return values[key] ?: detected.also { values[key] = it }
        }
    }

    @Synchronized
    fun clear() {
        values.clear()
    }

    @Synchronized
    internal fun size(): Int = values.size
}

/** Builds and caches Apple Live Photo companion identifiers only after a playback lookup. */
internal class LazyAppleCompanionIndex<Key>(
    private val candidates: List<Key>,
    private val readIdentifier: (Key) -> String?,
) {
    private val indexMutex = Mutex()

    @Volatile
    private var index: Map<String, Key>? = null

    private var indexInFlight: CompletableDeferred<Map<String, Key>>? = null

    suspend fun find(imageIdentifier: String): Key? = buildIndex()[imageIdentifier.uppercase()]

    suspend fun clear() {
        indexMutex.withLock {
            index = null
            indexInFlight = null
        }
    }

    private suspend fun buildIndex(): Map<String, Key> {
        index?.let { return it }

        var existingIndex: Map<String, Key>? = null
        var shouldBuildIndex = false
        val deferred = indexMutex.withLock {
            existingIndex = index
            if (existingIndex != null) {
                null
            } else {
                indexInFlight
                    ?: CompletableDeferred<Map<String, Key>>().also {
                        indexInFlight = it
                        shouldBuildIndex = true
                    }
            }
        }

        existingIndex?.let { return it }
        val indexDeferred = checkNotNull(deferred)
        if (!shouldBuildIndex) return indexDeferred.await()

        return try {
            val builtIndex = buildCandidateIndex()
            indexMutex.withLock {
                if (indexInFlight === indexDeferred) {
                    index = builtIndex
                    indexInFlight = null
                }
            }
            indexDeferred.complete(builtIndex)
            builtIndex
        } catch (error: Throwable) {
            indexMutex.withLock {
                if (indexInFlight === indexDeferred) indexInFlight = null
            }
            indexDeferred.completeExceptionally(error)
            throw error
        }
    }

    private suspend fun buildCandidateIndex(): Map<String, Key> {
        val index = LinkedHashMap<String, Key>()
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            val identifier = readIdentifier(candidate)
            currentCoroutineContext().ensureActive()
            if (identifier != null) index.putIfAbsent(identifier.uppercase(), candidate)
        }
        return index
    }
}

private class ContentResolverMotionPhotoMetadataReader(
    private val contentResolver: ContentResolver,
) : MotionPhotoMetadataReader {
    override fun readImageMetadata(uri: Uri): MotionPhotoImageMetadata? = runCatching {
        contentResolver.openInputStream(uri)?.use(::ExifInterface)?.let { exif ->
            MotionPhotoImageMetadata(
                xmp = exif.xmpText(),
                make = exif.getAttribute(ExifInterface.TAG_MAKE),
                makerNote = exif.getAttributeBytes(ExifInterface.TAG_MAKER_NOTE),
            )
        }
    }.getOrNull()

    override fun readQuickTimeContentIdentifier(uri: Uri): String? =
        contentResolver.readQuickTimeContentIdentifier(uri)
}

internal object MotionPhotoMetadata {
    private val motionPhotoAttribute = Regex(
        pattern = """(?is)(?:GCamera|Camera):(?:MotionPhoto|MicroVideo)\s*=\s*["']?1(?:["'\s/>]|$)|<(?:GCamera|Camera):(?:MotionPhoto|MicroVideo)>\s*1\s*<""",
    )
    private val uuid = Regex(
        pattern = """(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""",
    )

    fun isEmbeddedMotionPhoto(xmp: String?): Boolean =
        xmp != null && motionPhotoAttribute.containsMatchIn(xmp)

    fun findAppleContentIdentifier(
        metadata: ByteArray,
        requireIdentifierMarker: Boolean,
    ): String? {
        val text = metadata.toString(StandardCharsets.ISO_8859_1)
        val normalized = text.replace('\u0000', ' ')
        val marker = normalized.indexOf("contentidentifier", ignoreCase = true)
            .takeIf { it >= 0 }
            ?: normalized.indexOf("content.identifier", ignoreCase = true).takeIf { it >= 0 }

        if (requireIdentifierMarker && marker == null) return null

        val searchArea = if (marker != null) {
            normalized.substring(marker, (marker + 8_192).coerceAtMost(normalized.length))
        } else {
            normalized
        }
        return uuid.find(searchArea)?.value
    }

    fun <T> findMatchingCompanion(
        imageIdentifier: String,
        companionVideos: Map<String, T>,
    ): T? = companionVideos[imageIdentifier.uppercase()]
}

private fun ExifInterface.xmpText(): String? =
    getAttributeBytes(ExifInterface.TAG_XMP)
        ?.toString(StandardCharsets.UTF_8)
        ?.takeIf(String::isNotBlank)

private fun MotionPhotoImageMetadata.appleLivePhotoContentIdentifier(): String? {
    MotionPhotoMetadata.findAppleContentIdentifier(
        metadata = xmp?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0),
        requireIdentifierMarker = true,
    )?.let { return it }

    if (!make.orEmpty().contains("apple", ignoreCase = true)) return null

    return makerNote?.let {
        MotionPhotoMetadata.findAppleContentIdentifier(it, requireIdentifierMarker = false)
    }
}

private fun GalleryMedia.isPossibleAppleLivePhotoVideo(): Boolean =
    type == GalleryMediaType.Video && (
        mimeType.equals("video/quicktime", ignoreCase = true) ||
            displayName.endsWith(".mov", ignoreCase = true)
        )

private fun ContentResolver.readQuickTimeContentIdentifier(uri: Uri): String? {
    val metadata = readFileMetadataWindows(uri) ?: return null
    return MotionPhotoMetadata.findAppleContentIdentifier(
        metadata = metadata,
        requireIdentifierMarker = true,
    )
}

private fun ContentResolver.readFileMetadataWindows(uri: Uri): ByteArray? {
    val descriptorBytes = runCatching {
        openFileDescriptor(uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                val size = channel.size()
                if (size <= 0L) return@use null

                val windowSize = minOf(FILE_METADATA_WINDOW_BYTES.toLong(), size).toInt()
                val head = channel.readWindow(position = 0L, byteCount = windowSize)
                if (size <= windowSize) return@use head

                val tail = channel.readWindow(position = size - windowSize, byteCount = windowSize)
                head + tail
            }
        }
    }.getOrNull()
    if (descriptorBytes != null) return descriptorBytes

    return runCatching {
        openInputStream(uri)?.use { input ->
            input.readUpTo(FILE_METADATA_FALLBACK_BYTES)
        }
    }.getOrNull()
}

private fun InputStream.readUpTo(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
    val buffer = ByteArray(8 * 1024)
    var remaining = maxBytes
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) break
        output.write(buffer, 0, read)
        remaining -= read
    }
    return output.toByteArray()
}

private fun java.nio.channels.FileChannel.readWindow(
    position: Long,
    byteCount: Int,
): ByteArray {
    val buffer = ByteBuffer.allocate(byteCount)
    var offset = position
    while (buffer.hasRemaining()) {
        val read = read(buffer, offset)
        if (read <= 0) break
        offset += read
    }
    return buffer.array().copyOf(buffer.position())
}

private const val FILE_METADATA_WINDOW_BYTES = 1_048_576
private const val FILE_METADATA_FALLBACK_BYTES = 2_097_152
private const val MOTION_PHOTO_PROBE_CACHE_ENTRIES = 512
