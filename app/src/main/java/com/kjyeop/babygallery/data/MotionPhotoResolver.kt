package com.kjyeop.babygallery.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

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
 * Resolves a photo's optional motion video without ever copying or changing gallery media.
 *
 * The iPhone MOV index is created lazily on an IO dispatcher and retained for the lifetime of
 * this resolver. A MOV is accepted only when its Apple content identifier exactly matches the
 * identifier stored in the still image's metadata.
 */
class MotionPhotoResolver(
    private val context: Context,
    private val libraryItems: List<GalleryMedia>,
) {
    private val resolutionMutex = Mutex()
    private val resolvedUris = mutableMapOf<Uri, MotionPhotoSource?>()
    private var appleVideoIndex: Map<String, Uri>? = null

    suspend fun resolve(image: GalleryMedia): MotionPhotoSource? = withContext(Dispatchers.IO) {
        if (image.type != GalleryMediaType.Image) return@withContext null

        resolutionMutex.withLock {
            if (resolvedUris.containsKey(image.uri)) return@withLock resolvedUris[image.uri]

            val source = resolveImage(image)
            resolvedUris[image.uri] = source
            source
        }
    }

    private fun resolveImage(image: GalleryMedia): MotionPhotoSource? {
        val exif = readExif(image.uri) ?: return null
        val xmp = exif.xmpText()

        if (MotionPhotoMetadata.isEmbeddedMotionPhoto(xmp)) {
            return MotionPhotoSource.Embedded(image.uri, image.mimeType)
        }

        val imageIdentifier = exif.appleLivePhotoContentIdentifier(xmp) ?: return null
        val companionUri = MotionPhotoMetadata.findMatchingCompanion(
            imageIdentifier = imageIdentifier,
            companionVideos = appleVideoIndex(),
        )
            ?: return null

        return MotionPhotoSource.CompanionVideo(companionUri)
    }

    private fun appleVideoIndex(): Map<String, Uri> {
        appleVideoIndex?.let { return it }

        val index = buildMap {
            libraryItems
                .asSequence()
                .filter(GalleryMedia::isPossibleAppleLivePhotoVideo)
                .forEach { video ->
                    val identifier = context.contentResolver.readQuickTimeContentIdentifier(video.uri)
                    if (identifier != null) putIfAbsent(identifier.uppercase(), video.uri)
                }
        }
        appleVideoIndex = index
        return index
    }

    private fun readExif(uri: Uri): ExifInterface? = runCatching {
        context.contentResolver.openInputStream(uri)?.use(::ExifInterface)
    }.getOrNull()
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

private fun ExifInterface.appleLivePhotoContentIdentifier(xmp: String?): String? {
    MotionPhotoMetadata.findAppleContentIdentifier(
        metadata = xmp?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0),
        requireIdentifierMarker = true,
    )?.let { return it }

    val maker = getAttribute(ExifInterface.TAG_MAKE)
    if (!maker.orEmpty().contains("apple", ignoreCase = true)) return null

    return getAttributeBytes(ExifInterface.TAG_MAKER_NOTE)
        ?.let { MotionPhotoMetadata.findAppleContentIdentifier(it, requireIdentifierMarker = false) }
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
