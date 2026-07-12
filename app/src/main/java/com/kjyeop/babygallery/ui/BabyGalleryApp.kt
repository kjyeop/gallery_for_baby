package com.kjyeop.babygallery.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.kjyeop.babygallery.data.ALL_COLLECTION_KEY
import com.kjyeop.babygallery.data.GalleryAlbum
import com.kjyeop.babygallery.data.GalleryLibrary
import com.kjyeop.babygallery.data.GalleryMedia
import com.kjyeop.babygallery.data.GalleryMediaType
import com.kjyeop.babygallery.data.LastViewStore
import com.kjyeop.babygallery.data.MediaRepository
import com.kjyeop.babygallery.data.MotionPhotoResolver
import com.kjyeop.babygallery.data.MotionPhotoSource
import com.kjyeop.babygallery.data.albumCollectionKey
import com.kjyeop.babygallery.data.hasCollection
import com.kjyeop.babygallery.data.itemsForCollection
import com.kjyeop.babygallery.data.titleForCollection
import com.kjyeop.babygallery.permissions.hasMediaReadAccess
import com.kjyeop.babygallery.permissions.requiredMediaPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun BabyGalleryApp(
    mediaRepository: MediaRepository,
    lastViewStore: LastViewStore,
    onStartAppPinning: () -> Boolean,
) {
    val context = LocalContext.current
    var permissionVersion by rememberSaveable { mutableIntStateOf(0) }
    var reloadVersion by rememberSaveable { mutableIntStateOf(0) }
    val hasMediaAccess = remember(permissionVersion) { context.hasMediaReadAccess() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionVersion += 1
    }
    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        permissionVersion += 1
    }

    var loadState by remember { mutableStateOf<LibraryLoadState>(LibraryLoadState.Loading) }

    LaunchedEffect(hasMediaAccess, reloadVersion) {
        if (hasMediaAccess) {
            loadState = LibraryLoadState.Loading
            loadState = runCatching { mediaRepository.loadLibrary() }
                .fold(
                    onSuccess = { LibraryLoadState.Loaded(it) },
                    onFailure = {
                        LibraryLoadState.Failed(
                            it.localizedMessage ?: "사진과 영상을 불러오지 못했어요.",
                        )
                    },
                )
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        if (!hasMediaAccess) {
            PermissionScreen(
                onRequestPermission = {
                    permissionLauncher.launch(requiredMediaPermissions())
                },
                onOpenSettings = {
                    settingsLauncher.launch(context.appSettingsIntent())
                },
            )
            return@Surface
        }

        when (val state = loadState) {
            LibraryLoadState.Loading -> LoadingScreen()
            is LibraryLoadState.Failed -> ErrorScreen(
                message = state.message,
                onRetry = { reloadVersion += 1 },
            )
            is LibraryLoadState.Loaded -> GalleryNavigator(
                library = state.library,
                lastViewStore = lastViewStore,
                onStartAppPinning = onStartAppPinning,
                onRefresh = { reloadVersion += 1 },
            )
        }
    }
}

@Composable
private fun GalleryNavigator(
    library: GalleryLibrary,
    lastViewStore: LastViewStore,
    onStartAppPinning: () -> Boolean,
    onRefresh: () -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf("library") }
    var selectedCollectionKey by rememberSaveable { mutableStateOf(ALL_COLLECTION_KEY) }
    var viewerInitialIndex by rememberSaveable { mutableIntStateOf(0) }
    var restoredLastView by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(library.items.size, library.albums.size) {
        if (!restoredLastView) {
            val savedCollection = lastViewStore.readLastCollectionKey()
            if (savedCollection != null && library.hasCollection(savedCollection)) {
                selectedCollectionKey = savedCollection
                screen = "grid"
            }
            restoredLastView = true
        }

        if (!library.hasCollection(selectedCollectionKey)) {
            screen = "library"
            selectedCollectionKey = ALL_COLLECTION_KEY
        }
    }

    val openCollection: (String) -> Unit = { collectionKey ->
        selectedCollectionKey = collectionKey
        lastViewStore.saveLastCollectionKey(collectionKey)
        screen = "grid"
    }

    val startWatching: (String) -> Unit = { collectionKey ->
        val items = library.itemsForCollection(collectionKey)
        selectedCollectionKey = collectionKey
        lastViewStore.saveLastCollectionKey(collectionKey)
        if (items.isNotEmpty()) {
            viewerInitialIndex = 0
            screen = "viewer"
            onStartAppPinning()
        } else {
            screen = "grid"
        }
    }

    val gridState = rememberSaveable(
        selectedCollectionKey,
        saver = LazyGridState.Saver,
    ) {
        LazyGridState()
    }

    BackHandler(enabled = screen != "library") {
        screen = if (screen == "viewer") "grid" else "library"
    }

    when (screen) {
        "grid" -> MediaGridScreen(
            title = library.titleForCollection(selectedCollectionKey),
            items = library.itemsForCollection(selectedCollectionKey),
            state = gridState,
            onBack = { screen = "library" },
            onRefresh = onRefresh,
            onStartWatching = { startWatching(selectedCollectionKey) },
            onOpenViewer = { index ->
                viewerInitialIndex = index
                screen = "viewer"
            },
        )

        "viewer" -> FullScreenViewer(
            items = library.itemsForCollection(selectedCollectionKey),
            companionCandidates = library.items,
            initialIndex = viewerInitialIndex,
            onBack = { screen = "grid" },
        )

        else -> LibraryScreen(
            library = library,
            onOpenAll = { openCollection(ALL_COLLECTION_KEY) },
            onOpenAlbum = { album -> openCollection(albumCollectionKey(album.id)) },
            onStartWatching = { startWatching(ALL_COLLECTION_KEY) },
            onRefresh = onRefresh,
        )
    }
}

@Composable
private fun PermissionScreen(
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "사진과 영상을 볼 수 있게 허용해주세요",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "기기에 저장된 가족 사진과 영상만 읽어옵니다. 삭제, 편집, 공유 권한은 사용하지 않습니다.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(28.dp))
        Button(onClick = onRequestPermission) {
            Text("사진 및 영상 권한 허용")
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onOpenSettings) {
            Text("앱 설정 열기")
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorScreen(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("다시 시도")
        }
    }
}

@Composable
private fun LibraryScreen(
    library: GalleryLibrary,
    onOpenAll: () -> Unit,
    onOpenAlbum: (GalleryAlbum) -> Unit,
    onStartWatching: () -> Unit,
    onRefresh: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 180.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            HeaderRow(
                title = "Simple Gallery",
                subtitle = if (library.items.isEmpty()) {
                    "표시할 사진이나 영상이 없습니다"
                } else {
                    "전체 ${library.items.size}개"
                },
                actionText = "시청 시작",
                onAction = onStartWatching,
                secondaryActionIcon = Icons.Filled.Refresh,
                secondaryActionContentDescription = "새로고침",
                onSecondaryAction = onRefresh,
            )
        }

        if (library.items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    title = "사진과 영상이 없어요",
                    message = "태블릿에 사진이나 영상을 복사한 뒤 새로고침하세요.",
                )
            }
            return@LazyVerticalGrid
        }

        item {
            CollectionCard(
                cover = library.items.firstOrNull(),
                title = "전체 사진 및 영상",
                subtitle = "${library.items.size}개",
                onClick = onOpenAll,
            )
        }

        items(
            items = library.albums,
            key = { it.id },
        ) { album ->
            CollectionCard(
                cover = album.cover,
                title = album.name,
                subtitle = "${album.itemCount}개",
                onClick = { onOpenAlbum(album) },
            )
        }
    }
}

@Composable
private fun HeaderRow(
    title: String,
    subtitle: String,
    actionText: String,
    onAction: () -> Unit,
    secondaryActionIcon: ImageVector? = null,
    secondaryActionContentDescription: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .statusBarsPadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (secondaryActionIcon != null && onSecondaryAction != null) {
            IconButton(onClick = onSecondaryAction) {
                Icon(
                    imageVector = secondaryActionIcon,
                    contentDescription = secondaryActionContentDescription,
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
        }
        Button(onClick = onAction) {
            Text(actionText)
        }
    }
}

@Composable
private fun CollectionCard(
    cover: GalleryMedia?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            MediaThumbnail(
                media = cover,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.15f),
            )
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MediaGridScreen(
    title: String,
    items: List<GalleryMedia>,
    state: LazyGridState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onStartWatching: () -> Unit,
    onOpenViewer: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Text(
                    text = "‹",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${items.size}개",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "새로고침",
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Button(onClick = onStartWatching) {
                Text("시청 시작")
            }
        }

        if (items.isEmpty()) {
            EmptyState(
                title = "이 앨범은 비어 있어요",
                message = "다른 앨범을 선택하거나 새로고침하세요.",
            )
            return
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> "${item.type}:${item.id}" },
            ) { index, media ->
                MediaThumbnail(
                    media = media,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onOpenViewer(index) },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    message: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullScreenViewer(
    items: List<GalleryMedia>,
    companionCandidates: List<GalleryMedia>,
    initialIndex: Int,
    onBack: () -> Unit,
) {
    HideSystemBarsForViewer()

    if (items.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "표시할 항목이 없어요",
                color = Color.White,
            )
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size },
    )
    val context = LocalContext.current
    val motionPhotoResolver = remember(context.applicationContext, companionCandidates) {
        MotionPhotoResolver(context.applicationContext, companionCandidates)
    }
    var autoPlayedMotionMediaKeys by remember { mutableStateOf(emptySet<String>()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val media = items[page]
            when (media.type) {
                GalleryMediaType.Image -> {
                    val mediaKey = "${media.type}:${media.id}"
                    PhotoPage(
                        media = media,
                        isActive = page == pagerState.currentPage,
                        motionPhotoResolver = motionPhotoResolver,
                        shouldAutoPlayMotion = mediaKey !in autoPlayedMotionMediaKeys,
                        onMotionAutoPlayStarted = {
                            autoPlayedMotionMediaKeys = autoPlayedMotionMediaKeys + mediaKey
                        },
                    )
                }
                GalleryMediaType.Video -> VideoPage(
                    media = media,
                    isActive = page == pagerState.currentPage,
                )
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Text(
                text = "‹",
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        LowBatteryIndicator(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp),
        )
    }
}

@Composable
private fun HideSystemBarsForViewer() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, view).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        onDispose {
            if (window != null) {
                WindowInsetsControllerCompat(window, view).show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
            }
        }
    }
}

@Composable
private fun LowBatteryIndicator(modifier: Modifier = Modifier) {
    val batteryPercent = rememberBatteryPercent()
    if (batteryPercent == null || batteryPercent > LOW_BATTERY_THRESHOLD_PERCENT) return

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.BatteryAlert,
                contentDescription = "배터리 부족",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "$batteryPercent%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun rememberBatteryPercent(): Int? {
    val context = LocalContext.current
    var batteryPercent by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(context) {
        fun update(intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            batteryPercent = calculateBatteryPercent(level, scale)
        }

        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                update(intent)
            }
        }
        val stickyIntent = ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        update(stickyIntent)

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    return batteryPercent
}

private fun calculateBatteryPercent(level: Int, scale: Int): Int? {
    if (level < 0 || scale <= 0) return null
    return ((level.toFloat() / scale) * 100).roundToInt().coerceIn(0, 100)
}

@Composable
private fun PhotoPage(
    media: GalleryMedia,
    isActive: Boolean,
    motionPhotoResolver: MotionPhotoResolver,
    shouldAutoPlayMotion: Boolean,
    onMotionAutoPlayStarted: () -> Unit,
) {
    val context = LocalContext.current
    var imageState by remember(media.uri) { mutableStateOf<BitmapLoadState>(BitmapLoadState.Loading) }
    var motionSource by remember(media.uri) { mutableStateOf<MotionPhotoSource?>(null) }
    var motionPlaybackFailed by remember(media.uri) { mutableStateOf(false) }
    var showMotionVideo by remember(media.uri) { mutableStateOf(false) }
    var controllerRequestCount by remember(media.uri) { mutableIntStateOf(0) }

    LaunchedEffect(media.uri) {
        imageState = BitmapLoadState.Loading
        imageState = withContext(Dispatchers.IO) {
            decodeFullBitmap(context, media.uri, maxSide = 4096)
                ?.let(BitmapLoadState::Ready)
                ?: BitmapLoadState.Failed
        }
    }

    LaunchedEffect(media.uri, motionPhotoResolver) {
        motionSource = motionPhotoResolver.resolve(media)
    }

    val motionPlayer = remember(motionSource) {
        motionSource?.let { source ->
            ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItem(motionMediaItem(source))
                prepare()
            }
        }
    }
    var motionPlayerView by remember(motionPlayer) { mutableStateOf<PlayerView?>(null) }

    val startMotionPlayback: () -> Unit = start@{
        val player = motionPlayer ?: return@start
        motionPlaybackFailed = false
        showMotionVideo = true
        controllerRequestCount += 1
        player.seekTo(0)
        player.play()
    }

    if (motionPlayer != null) {
        DisposableEffect(motionPlayer) {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        motionPlayerView?.hideController()
                        showMotionVideo = false
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    motionPlayerView?.hideController()
                    motionPlaybackFailed = true
                    showMotionVideo = false
                }
            }
            motionPlayer.addListener(listener)

            onDispose {
                motionPlayer.removeListener(listener)
                motionPlayer.release()
            }
        }
    }

    LaunchedEffect(motionPlayer, isActive, shouldAutoPlayMotion, motionPlaybackFailed) {
        if (!isActive) {
            motionPlayer?.pause()
            return@LaunchedEffect
        }

        if (motionPlayer != null && shouldAutoPlayMotion && !motionPlaybackFailed) {
            onMotionAutoPlayStarted()
            startMotionPlayback()
        }
    }

    LaunchedEffect(motionPlayerView, controllerRequestCount, showMotionVideo) {
        if (showMotionVideo && controllerRequestCount > 0) {
            motionPlayerView?.showController()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when (val state = imageState) {
            BitmapLoadState.Loading -> CircularProgressIndicator(color = Color.White)
            BitmapLoadState.Failed -> Text(
                text = "사진을 표시할 수 없어요",
                color = Color.White,
            )
            is BitmapLoadState.Ready -> Image(
                bitmap = state.bitmap.asImageBitmap(),
                contentDescription = media.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }

        if (showMotionVideo && motionPlayer != null) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = true
                        setControllerAutoShow(false)
                        setControllerHideOnTouch(true)
                        setControllerShowTimeoutMs(VIDEO_CONTROLLER_SHOW_TIMEOUT_MS)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        player = motionPlayer
                        motionPlayerView = this
                    }
                },
                update = { it.player = motionPlayer },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (motionSource != null && !showMotionVideo && !motionPlaybackFailed && isActive) {
            Button(
                onClick = startMotionPlayback,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp),
            ) {
                Text("▶  모션 사진 보기")
            }
        }
    }
}

private fun motionMediaItem(source: MotionPhotoSource): ExoMediaItem = when (source) {
    is MotionPhotoSource.Embedded -> ExoMediaItem.Builder()
        .setUri(source.uri)
        .apply {
            source.mimeType?.let { setMimeType(it) }
        }
        .build()
    is MotionPhotoSource.CompanionVideo -> ExoMediaItem.fromUri(source.uri)
}

@Composable
private fun VideoPage(
    media: GalleryMedia,
    isActive: Boolean,
) {
    val context = LocalContext.current
    var hasError by remember(media.uri) { mutableStateOf(false) }
    val player = remember(media.uri) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            setMediaItem(ExoMediaItem.fromUri(media.uri))
            prepare()
        }
    }
    var playerView by remember(player) { mutableStateOf<PlayerView?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                hasError = true
            }
        }
        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, isActive, hasError, playerView) {
        if (isActive && !hasError) {
            player.play()
            playerView?.showController()
        } else {
            player.pause()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = true
                    setControllerAutoShow(true)
                    setControllerHideOnTouch(true)
                    setControllerShowTimeoutMs(VIDEO_CONTROLLER_SHOW_TIMEOUT_MS)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    this.player = player
                    playerView = this
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        if (hasError) {
            Text(
                text = "이 영상을 재생할 수 없어요",
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(28.dp),
            )
        }
    }
}

@Composable
private fun MediaThumbnail(
    media: GalleryMedia?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { 320.dp.roundToPx() }
    var thumbnailState by remember(media?.uri, sizePx) {
        mutableStateOf<BitmapLoadState>(BitmapLoadState.Loading)
    }

    LaunchedEffect(media?.uri, sizePx) {
        thumbnailState = BitmapLoadState.Loading
        thumbnailState = withContext(Dispatchers.IO) {
            media?.let { loadThumbnailBitmap(context, it, sizePx) }
                ?.let(BitmapLoadState::Ready)
                ?: BitmapLoadState.Failed
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (val state = thumbnailState) {
            BitmapLoadState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
            )
            BitmapLoadState.Failed -> Text(
                text = if (media?.type == GalleryMediaType.Video) "영상" else "사진",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is BitmapLoadState.Ready -> Image(
                bitmap = state.bitmap.asImageBitmap(),
                contentDescription = media?.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        if (media?.type == GalleryMediaType.Video) {
            VideoBadge(
                durationMillis = media.durationMillis,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun VideoBadge(
    durationMillis: Long?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.68f),
        shape = RoundedCornerShape(4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "▶",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = formatDuration(durationMillis),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Suppress("DEPRECATION")
private fun loadThumbnailBitmap(
    context: Context,
    media: GalleryMedia,
    sizePx: Int,
): Bitmap? {
    return runCatching {
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
    }.getOrNull()
}

private fun decodeFullBitmap(
    context: Context,
    uri: Uri,
    maxSide: Int,
): Bitmap? {
    return runCatching {
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
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
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
    }.getOrNull()
}

private fun formatDuration(durationMillis: Long?): String {
    val totalSeconds = ((durationMillis ?: 0L) / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private const val LOW_BATTERY_THRESHOLD_PERCENT = 20
private const val VIDEO_CONTROLLER_SHOW_TIMEOUT_MS = 3_000

private fun Context.appSettingsIntent(): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    )

private sealed interface LibraryLoadState {
    data object Loading : LibraryLoadState
    data class Loaded(val library: GalleryLibrary) : LibraryLoadState
    data class Failed(val message: String) : LibraryLoadState
}

private sealed interface BitmapLoadState {
    data object Loading : BitmapLoadState
    data object Failed : BitmapLoadState
    data class Ready(val bitmap: Bitmap) : BitmapLoadState
}
