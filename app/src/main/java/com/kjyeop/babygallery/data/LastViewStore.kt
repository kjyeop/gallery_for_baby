package com.kjyeop.babygallery.data

import android.content.Context
import androidx.core.content.edit

class LastViewStore(context: Context) {
    private val preferences = context.getSharedPreferences("baby_gallery_state", Context.MODE_PRIVATE)

    fun readLastCollectionKey(): String? =
        preferences.getString(KEY_LAST_COLLECTION, null)

    fun saveLastCollectionKey(collectionKey: String) {
        preferences.edit {
            putString(KEY_LAST_COLLECTION, collectionKey)
        }
    }

    companion object {
        private const val KEY_LAST_COLLECTION = "last_collection_key"
    }
}
