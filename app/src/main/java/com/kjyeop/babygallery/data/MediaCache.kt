package com.kjyeop.babygallery.data

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Owns all disposable image data for the application process. It intentionally persists only
 * reduced thumbnails; original gallery media and full-screen images never leave MediaStore.
 */
class MediaCache(context: Context) {
    private val appContext = context.applicationContext
    private val budgets = cacheBudgets(memoryClassMb(appContext))
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val thumbnailMemory = bitmapLruCache(budgets.thumbnailMemoryKb)
    private val fullScreenMemory = bitmapLruCache(budgets.fullScreenMemoryKb)
    private val thumbnailDisk = ThumbnailDiskStore(
        directory = File(appContext.cacheDir, THUMBNAIL_CACHE_DIRECTORY),
        maxBytes = THUMBNAIL_DISK_CACHE_BYTES,
    )
    private val thumbnailInFlight = InFlightLoadRegistry<ThumbnailCacheKey, Bitmap?>(cacheScope)
    private val fullScreenInFlight = InFlightLoadRegistry<FullScreenImageKey, Bitmap?>(cacheScope)
    private val thumbnailDecodeSemaphore = Semaphore(THUMBNAIL_DECODE_PARALLELISM)
    private val fullScreenDecodeSemaphore = Semaphore(FULL_SCREEN_DECODE_PARALLELISM)
    private val cacheGeneration = AtomicLong()
    private val cacheMutationLock = Any()
    private val _memoryTrimEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Lets short-lived media metadata caches release data with the bitmap caches. */
    val memoryTrimEvents: SharedFlow<Unit> = _memoryTrimEvents.asSharedFlow()

    suspend fun loadThumbnail(media: GalleryMedia, requestedMaxDimensionPx: Int): Bitmap? {
        val key = ThumbnailCacheKey.from(media, requestedMaxDimensionPx)
        val generation = cacheGeneration.get()
        thumbnailMemory.get(key)?.let { return it }
        thumbnailDisk.read(key)?.also {
            thumbnailMemory.put(key, it)
            return it
        }

        return bitmapOrNull {
            thumbnailInFlight.load(key) {
                thumbnailMemory.get(key)
                    ?: thumbnailDisk.read(key)?.also { thumbnailMemory.put(key, it) }
                    ?: thumbnailDecodeSemaphore.withPermit {
                        loadMediaStoreThumbnail(appContext, media, key.sizeBucketPx)?.also { bitmap ->
                            cacheThumbnailIfCurrent(generation, key, bitmap)
                        }
                    }
            }
        }
    }

    suspend fun loadFullScreen(media: GalleryMedia, maxSidePx: Int): Bitmap? {
        val key = FullScreenImageKey.from(media, maxSidePx)
        val generation = cacheGeneration.get()
        fullScreenMemory.get(key)?.let { return it }

        return bitmapOrNull {
            fullScreenInFlight.load(key) {
                fullScreenMemory.get(key)
                    ?: fullScreenDecodeSemaphore.withPermit {
                        decodeFullBitmap(appContext, media.uri, key.maxSidePx)?.also {
                            cacheFullScreenIfCurrent(generation, key, it)
                        }
                    }
            }
        }
    }

    suspend fun prefetchFullScreen(media: GalleryMedia, maxSidePx: Int) {
        loadFullScreen(media, maxSidePx)
    }

    fun clearForPermissionRevocation() {
        synchronized(cacheMutationLock) {
            evictAllMemoryLocked()
            thumbnailDisk.clear()
        }
        _memoryTrimEvents.tryEmit(Unit)
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                evictAllMemory()
                _memoryTrimEvents.tryEmit(Unit)
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                evictAllMemory()
                _memoryTrimEvents.tryEmit(Unit)
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                thumbnailMemory.trimToSize(thumbnailMemory.maxSize() / 2)
                fullScreenMemory.trimToSize(fullScreenMemory.maxSize() / 2)
            }
        }
    }

    fun onLowMemory() {
        evictAllMemory()
        _memoryTrimEvents.tryEmit(Unit)
    }

    private fun evictAllMemory() {
        synchronized(cacheMutationLock) {
            evictAllMemoryLocked()
        }
    }

    private fun evictAllMemoryLocked() {
        cacheGeneration.incrementAndGet()
        thumbnailMemory.evictAll()
        fullScreenMemory.evictAll()
    }

    private fun cacheThumbnailIfCurrent(
        generation: Long,
        key: ThumbnailCacheKey,
        bitmap: Bitmap,
    ) = synchronized(cacheMutationLock) {
        if (cacheGeneration.get() != generation) return@synchronized
        thumbnailMemory.put(key, bitmap)
        thumbnailDisk.write(key, bitmap)
    }

    private fun cacheFullScreenIfCurrent(
        generation: Long,
        key: FullScreenImageKey,
        bitmap: Bitmap,
    ) = synchronized(cacheMutationLock) {
        if (cacheGeneration.get() == generation) fullScreenMemory.put(key, bitmap)
    }

}

/** Shares one cache-miss calculation between all visible composables requesting the same key. */
internal class InFlightLoadRegistry<Key : Any, Value>(
    private val scope: CoroutineScope,
) {
    private class Entry<Value> {
        val deferred = CompletableDeferred<Value>()
        val waiters = AtomicInteger()
        val job = AtomicReference<Job?>()
    }

    private val values = ConcurrentHashMap<Key, Entry<Value>>()

    suspend fun load(key: Key, loader: suspend () -> Value): Value {
        val newEntry = Entry<Value>()
        val entry = values.putIfAbsent(key, newEntry) ?: newEntry
        if (entry === newEntry) {
            newEntry.job.set(scope.launch {
                try {
                    newEntry.deferred.complete(loader())
                } catch (error: Throwable) {
                    newEntry.deferred.completeExceptionally(error)
                } finally {
                    values.remove(key, newEntry)
                }
            })
        }

        entry.waiters.incrementAndGet()
        return try {
            entry.deferred.await()
        } finally {
            if (entry.waiters.decrementAndGet() == 0 && !entry.deferred.isCompleted) {
                entry.job.get()?.cancel()
            }
        }
    }
}

private suspend fun bitmapOrNull(loader: suspend () -> Bitmap?): Bitmap? = try {
    loader()
} catch (error: Throwable) {
    if (error is CancellationException) throw error
    null
}

internal data class CacheBudgets(
    val thumbnailMemoryKb: Int,
    val fullScreenMemoryKb: Int,
)

internal fun cacheBudgets(memoryClassMb: Int): CacheBudgets {
    val memoryKb = memoryClassMb.coerceAtLeast(1) * 1024
    return CacheBudgets(
        thumbnailMemoryKb = (memoryKb * 0.10f).roundToInt().coerceIn(8 * 1024, 32 * 1024),
        fullScreenMemoryKb = (memoryKb * 0.25f).roundToInt().coerceIn(16 * 1024, 96 * 1024),
    )
}

internal fun thumbnailSizeBucket(requestedMaxDimensionPx: Int): Int =
    ((requestedMaxDimensionPx.coerceIn(1, MAX_THUMBNAIL_SIZE_PX) + THUMBNAIL_SIZE_BUCKET_PX - 1) /
        THUMBNAIL_SIZE_BUCKET_PX) * THUMBNAIL_SIZE_BUCKET_PX

internal data class ThumbnailCacheKey(
    val type: GalleryMediaType,
    val id: Long,
    val uri: String,
    val dateModifiedSeconds: Long,
    val sizeBytes: Long,
    val sizeBucketPx: Int,
) {
    companion object {
        fun from(media: GalleryMedia, requestedMaxDimensionPx: Int): ThumbnailCacheKey = ThumbnailCacheKey(
            type = media.type,
            id = media.id,
            uri = media.uri.toString(),
            dateModifiedSeconds = media.dateModifiedSeconds,
            sizeBytes = media.sizeBytes,
            sizeBucketPx = thumbnailSizeBucket(requestedMaxDimensionPx),
        )
    }
}

private data class FullScreenImageKey(
    val id: Long,
    val uri: Uri,
    val dateModifiedSeconds: Long,
    val sizeBytes: Long,
    val maxSidePx: Int,
) {
    companion object {
        fun from(media: GalleryMedia, maxSidePx: Int): FullScreenImageKey = FullScreenImageKey(
            id = media.id,
            uri = media.uri,
            dateModifiedSeconds = media.dateModifiedSeconds,
            sizeBytes = media.sizeBytes,
            maxSidePx = maxSidePx.coerceAtLeast(1),
        )
    }
}

internal class ThumbnailDiskStore(
    private val directory: File,
    private val maxBytes: Long,
) {
    private val lock = Any()

    init {
        synchronized(lock) {
            directory.mkdirs()
            directory.listFiles()?.filter { it.name.endsWith(TEMPORARY_FILE_SUFFIX) }?.forEach(File::delete)
            trimToSizeLocked()
        }
    }

    fun read(key: ThumbnailCacheKey): Bitmap? = synchronized(lock) {
        val file = fileFor(key)
        if (!file.isFile) return@synchronized null

        val bitmap = BitmapFactory.decodeFile(file.path)
        if (bitmap == null) {
            file.delete()
            return@synchronized null
        }
        file.setLastModified(System.currentTimeMillis())
        bitmap
    }

    @Suppress("DEPRECATION")
    fun write(key: ThumbnailCacheKey, bitmap: Bitmap) = synchronized(lock) {
        if (!directory.exists() && !directory.mkdirs()) return@synchronized

        val destination = fileFor(key)
        val temporary = File(directory, "${destination.name}${TEMPORARY_FILE_SUFFIX}")
        val bitmapForDisk = runCatching {
            bitmap.takeUnless { it.config == Bitmap.Config.HARDWARE }
                ?: bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }.getOrNull() ?: return@synchronized
        val written = runCatching {
            try {
                FileOutputStream(temporary).use { output ->
                    bitmapForDisk.compress(Bitmap.CompressFormat.WEBP, THUMBNAIL_WEBP_QUALITY, output)
                }
            } finally {
                if (bitmapForDisk !== bitmap) bitmapForDisk.recycle()
            }
        }.getOrDefault(false)
        if (!written) {
            temporary.delete()
            return@synchronized
        }

        if (destination.exists()) destination.delete()
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            return@synchronized
        }
        destination.setLastModified(System.currentTimeMillis())
        trimToSizeLocked()
    }

    fun clear() = synchronized(lock) {
        directory.listFiles()?.forEach(File::delete)
    }

    private fun fileFor(key: ThumbnailCacheKey): File = File(directory, "${key.digest()}.webp")

    private fun trimToSizeLocked() {
        var currentSize = directory.listFiles()?.sumOf { it.length() } ?: 0L
        if (currentSize <= maxBytes) return

        directory.listFiles()
            ?.sortedBy(File::lastModified)
            ?.forEach { file ->
                if (currentSize <= maxBytes) return@forEach
                val length = file.length()
                if (file.delete()) currentSize -= length
            }
    }

    private fun ThumbnailCacheKey.digest(): String = MessageDigest
        .getInstance("SHA-256")
        .digest(toString().toByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun bitmapLruCache(maxSizeKb: Int): LruCache<Any, Bitmap> = object : LruCache<Any, Bitmap>(maxSizeKb) {
    override fun sizeOf(key: Any, value: Bitmap): Int =
        (value.allocationByteCount / 1024).coerceAtLeast(1)
}

private fun memoryClassMb(context: Context): Int =
    context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 192

@Suppress("DEPRECATION")
private fun loadMediaStoreThumbnail(
    context: Context,
    media: GalleryMedia,
    sizePx: Int,
): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.loadThumbnail(media.uri, Size(sizePx, sizePx), null)
    } else {
        when (media.type) {
            GalleryMediaType.Image -> MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver,
                media.id,
                MediaStore.Images.Thumbnails.MINI_KIND,
                null,
            )
            GalleryMediaType.Video -> MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver,
                media.id,
                MediaStore.Video.Thumbnails.MINI_KIND,
                null,
            )
        }
    }
}.getOrElse { error ->
    if (error is CancellationException) throw error
    null
}

private fun decodeFullBitmap(
    context: Context,
    uri: Uri,
    maxSide: Int,
): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val longestSide = max(width, height)
            if (longestSide > maxSide) {
                val scale = maxSide.toFloat() / longestSide.toFloat()
                decoder.setTargetSize(
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxSide || bounds.outHeight / sampleSize > maxSide) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }
}.getOrElse { error ->
    if (error is CancellationException) throw error
    null
}

private const val THUMBNAIL_CACHE_DIRECTORY = "thumbnail-v1"
private const val THUMBNAIL_DISK_CACHE_BYTES = 256L * 1024L * 1024L
private const val THUMBNAIL_SIZE_BUCKET_PX = 64
private const val MAX_THUMBNAIL_SIZE_PX = 2_048
private const val THUMBNAIL_WEBP_QUALITY = 90
private const val THUMBNAIL_DECODE_PARALLELISM = 4
private const val FULL_SCREEN_DECODE_PARALLELISM = 2
private const val TEMPORARY_FILE_SUFFIX = ".tmp"
