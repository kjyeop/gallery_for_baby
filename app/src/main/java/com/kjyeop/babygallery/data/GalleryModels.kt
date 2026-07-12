package com.kjyeop.babygallery.data

import android.net.Uri

const val ALL_COLLECTION_KEY = "all"
private const val ALBUM_PREFIX = "album:"

enum class GalleryMediaType {
    Image,
    Video,
}

data class GalleryMedia(
    val id: Long,
    val uri: Uri,
    val type: GalleryMediaType,
    val albumId: String,
    val albumName: String,
    val displayName: String,
    val mimeType: String?,
    val takenAtMillis: Long,
    val durationMillis: Long? = null,
    /** MediaStore values used to invalidate generated thumbnail cache entries. */
    val dateModifiedSeconds: Long = 0L,
    val sizeBytes: Long = 0L,
)

data class GalleryAlbum(
    val id: String,
    val name: String,
    val itemCount: Int,
    val cover: GalleryMedia,
)

data class GalleryLibrary(
    val items: List<GalleryMedia>,
    val albums: List<GalleryAlbum>,
)

fun albumCollectionKey(albumId: String): String = "$ALBUM_PREFIX$albumId"

fun albumIdFromCollectionKey(collectionKey: String): String? =
    collectionKey.takeIf { it.startsWith(ALBUM_PREFIX) }?.removePrefix(ALBUM_PREFIX)

fun GalleryLibrary.itemsForCollection(collectionKey: String): List<GalleryMedia> {
    val albumId = albumIdFromCollectionKey(collectionKey) ?: return items
    return items.filter { it.albumId == albumId }
}

fun GalleryLibrary.titleForCollection(collectionKey: String): String {
    if (collectionKey == ALL_COLLECTION_KEY) return "전체 사진 및 영상"
    val albumId = albumIdFromCollectionKey(collectionKey) ?: return "전체 사진 및 영상"
    return albums.firstOrNull { it.id == albumId }?.name ?: "앨범"
}

fun GalleryLibrary.hasCollection(collectionKey: String): Boolean {
    if (collectionKey == ALL_COLLECTION_KEY) return true
    val albumId = albumIdFromCollectionKey(collectionKey) ?: return false
    return albums.any { it.id == albumId }
}
