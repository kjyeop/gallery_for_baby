package com.kjyeop.babygallery

import android.app.Application
import com.kjyeop.babygallery.data.MediaCache

class BabyGalleryApplication : Application() {
    val mediaCache: MediaCache by lazy { MediaCache(this) }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        mediaCache.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mediaCache.onLowMemory()
    }
}
