package com.kjyeop.babygallery

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.kjyeop.babygallery.data.LastViewStore
import com.kjyeop.babygallery.data.MediaRepository
import com.kjyeop.babygallery.ui.BabyGalleryApp
import com.kjyeop.babygallery.ui.theme.BabyGalleryTheme

class MainActivity : ComponentActivity() {
    private val mediaRepository by lazy { MediaRepository(applicationContext) }
    private val lastViewStore by lazy { LastViewStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BabyGalleryTheme {
                BabyGalleryApp(
                    mediaRepository = mediaRepository,
                    lastViewStore = lastViewStore,
                    onStartAppPinning = ::startAppPinning,
                )
            }
        }
    }

    private fun startAppPinning(): Boolean {
        return runCatching {
            startLockTask()
            Toast.makeText(this, "화면 고정 확인 창이 보이면 확인하세요.", Toast.LENGTH_SHORT).show()
            true
        }.getOrElse {
            Toast.makeText(this, "화면 고정을 시작할 수 없어요. Android 설정에서 App Pinning을 확인하세요.", Toast.LENGTH_LONG).show()
            false
        }
    }
}
