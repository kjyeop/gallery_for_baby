package com.kjyeop.babygallery.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaRepository(private val context: Context) {
    suspend fun loadLibrary(): GalleryLibrary = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val items = (queryImages(resolver) + queryVideos(resolver))
            .distinctBy { "${it.type}:${it.id}" }
            .sortedByDescending { it.takenAtMillis }

        val albums = items
            .groupBy { it.albumId }
            .mapNotNull { (_, albumItems) ->
                val cover = albumItems.maxByOrNull { it.takenAtMillis } ?: return@mapNotNull null
                GalleryAlbum(
                    id = cover.albumId,
                    name = cover.albumName,
                    itemCount = albumItems.size,
                    cover = cover,
                )
            }
            .sortedByDescending { it.cover.takenAtMillis }

        GalleryLibrary(items = items, albums = albums)
    }

    private fun queryImages(resolver: ContentResolver): List<GalleryMedia> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
        )

        return safeQuery(
            resolver = resolver,
            uri = collection,
            projection = projection,
            sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        ) { cursor ->
            val id = cursor.getRequiredLong(MediaStore.Images.Media._ID)
            val albumId = cursor.getOptionalString(MediaStore.Images.Media.BUCKET_ID) ?: "images"
            val albumName = cursor.getOptionalString(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                ?.ifBlank { null }
                ?: "기타"
            val displayName = cursor.getOptionalString(MediaStore.Images.Media.DISPLAY_NAME)
                ?.ifBlank { null }
                ?: "사진"
            val mimeType = cursor.getOptionalString(MediaStore.Images.Media.MIME_TYPE)
            val takenAt = cursor.mediaDateMillis(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED)
            val dateModified = cursor.getOptionalLong(MediaStore.Images.Media.DATE_MODIFIED) ?: 0L
            val sizeBytes = cursor.getOptionalLong(MediaStore.Images.Media.SIZE) ?: 0L

            GalleryMedia(
                id = id,
                uri = ContentUris.withAppendedId(collection, id),
                type = GalleryMediaType.Image,
                albumId = albumId,
                albumName = albumName,
                displayName = displayName,
                mimeType = mimeType,
                takenAtMillis = takenAt,
                dateModifiedSeconds = dateModified,
                sizeBytes = sizeBytes,
            )
        }
    }

    private fun queryVideos(resolver: ContentResolver): List<GalleryMedia> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.SIZE,
        )

        return safeQuery(
            resolver = resolver,
            uri = collection,
            projection = projection,
            sortOrder = "${MediaStore.Video.Media.DATE_TAKEN} DESC",
        ) { cursor ->
            val id = cursor.getRequiredLong(MediaStore.Video.Media._ID)
            val albumId = cursor.getOptionalString(MediaStore.Video.Media.BUCKET_ID) ?: "videos"
            val albumName = cursor.getOptionalString(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                ?.ifBlank { null }
                ?: "기타"
            val displayName = cursor.getOptionalString(MediaStore.Video.Media.DISPLAY_NAME)
                ?.ifBlank { null }
                ?: "영상"
            val mimeType = cursor.getOptionalString(MediaStore.Video.Media.MIME_TYPE)
            val takenAt = cursor.mediaDateMillis(MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATE_ADDED)
            val duration = cursor.getOptionalLong(MediaStore.Video.Media.DURATION)?.takeIf { it > 0 }
            val dateModified = cursor.getOptionalLong(MediaStore.Video.Media.DATE_MODIFIED) ?: 0L
            val sizeBytes = cursor.getOptionalLong(MediaStore.Video.Media.SIZE) ?: 0L

            GalleryMedia(
                id = id,
                uri = ContentUris.withAppendedId(collection, id),
                type = GalleryMediaType.Video,
                albumId = albumId,
                albumName = albumName,
                displayName = displayName,
                mimeType = mimeType,
                takenAtMillis = takenAt,
                durationMillis = duration,
                dateModifiedSeconds = dateModified,
                sizeBytes = sizeBytes,
            )
        }
    }

    private fun <T> safeQuery(
        resolver: ContentResolver,
        uri: android.net.Uri,
        projection: Array<String>,
        sortOrder: String,
        mapper: (Cursor) -> T,
    ): List<T> {
        return try {
            resolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        runCatching { mapper(cursor) }.getOrNull()?.let(::add)
                    }
                }
            }.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    private fun Cursor.getRequiredLong(columnName: String): Long =
        getLong(getColumnIndexOrThrow(columnName))

    private fun Cursor.getOptionalLong(columnName: String): Long? {
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) return null
        return getLong(index)
    }

    private fun Cursor.getOptionalString(columnName: String): String? {
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) return null
        return getString(index)
    }

    private fun Cursor.mediaDateMillis(dateTakenColumn: String, dateAddedColumn: String): Long {
        val dateTaken = getOptionalLong(dateTakenColumn)?.takeIf { it > 0 }
        if (dateTaken != null) return dateTaken

        val dateAddedSeconds = getOptionalLong(dateAddedColumn)?.takeIf { it > 0 }
        return (dateAddedSeconds ?: 0L) * 1_000L
    }
}
