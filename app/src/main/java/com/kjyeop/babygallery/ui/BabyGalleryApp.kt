package com.kjyeop.babygallery.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
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
import com.kjyeop.babygallery.data.MediaCache
import com.kjyeop.babygallery.data.MediaRepository
import com.kjyeop.babygallery.data.MotionPhotoProbe
import com.kjyeop.babygallery.data.MotionPhotoResolver
import com.kjyeop.babygallery.data.MotionPhotoSource
import com.kjyeop.babygallery.data.albumCollectionKey
import com.kjyeop.babygallery.data.hasCollection
import com.kjyeop.babygallery.data.itemsForCollection
import com.kjyeop.babygallery.data.titleForCollection
import com.kjyeop.babygallery.permissions.hasMediaReadAccess
import com.kjyeop.babygallery.permissions.requiredMediaPermissions
import com.kjyeop.babygallery.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun BabyGalleryApp(
    mediaRepository: MediaRepository,
    lastViewStore: LastViewStore,
    mediaCache: MediaCache,
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

    LaunchedEffect(hasMediaAccess) {
        if (!hasMediaAccess) mediaCache.clearForPermissionRevocation()
    }

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
                mediaCache = mediaCache,
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
    mediaCache: MediaCache,
    onStartAppPinning: () -> Boolean,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val motionPhotoResolver = remember(context.applicationContext, library.items) {
        MotionPhotoResolver(context.applicationContext, library.items)
    }
    LaunchedEffect(mediaCache, motionPhotoResolver) {
        mediaCache.memoryTrimEvents.collect {
            motionPhotoResolver.clearCaches()
        }
    }
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
            mediaCache = mediaCache,
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
            initialIndex = viewerInitialIndex,
            mediaCache = mediaCache,
            motionPhotoResolver = motionPhotoResolver,
            onBack = { screen = "grid" },
        )

        else -> LibraryScreen(
            library = library,
            mediaCache = mediaCache,
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
    mediaCache: MediaCache,
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
                mediaCache = mediaCache,
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
                mediaCache = mediaCache,
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
    mediaCache: MediaCache,
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
                mediaCache = mediaCache,
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
    mediaCache: MediaCache,
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

        Box(modifier = Modifier.weight(1f)) {
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
                        mediaCache = mediaCache,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onOpenViewer(index) },
                    )
                }
            }

            MediaGridScrollBar(
                items = items,
                state = state,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun MediaGridScrollBar(
    items: List<GalleryMedia>,
    state: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val layoutInfo = state.layoutInfo
    val totalItemCount = layoutInfo.totalItemsCount
    val visibleItemCount = layoutInfo.visibleItemsInfo.size
    if (visibleItemCount == 0 || totalItemCount <= visibleItemCount) return

    val maxFirstVisibleIndex = (totalItemCount - visibleItemCount).coerceAtLeast(1)
    val scrollProgress = (
        state.firstVisibleItemIndex.toFloat() / maxFirstVisibleIndex.toFloat()
    ).coerceIn(0f, 1f)
    val visibleFraction = (visibleItemCount.toFloat() / totalItemCount.toFloat())
        .coerceIn(0.12f, 1f)
    val visibleDate = items
        .getOrNull(state.firstVisibleItemIndex)
        ?.takenAtMillis
        ?.formatGalleryMonth()
    var isDragging by remember { mutableStateOf(false) }
    val isActive = state.isScrollInProgress || isDragging
    var isDateChipVisible by remember { mutableStateOf(false) }
    LaunchedEffect(isActive) {
        if (isActive) {
            isDateChipVisible = true
        } else {
            delay(GRID_DATE_CHIP_HIDE_DELAY_MS)
            isDateChipVisible = false
        }
    }
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.5f,
        label = "grid scrollbar alpha",
    )
    val dateChipAlpha by animateFloatAsState(
        targetValue = if (isDateChipVisible && visibleDate != null) 1f else 0f,
        label = "grid date chip alpha",
    )
    val currentScrollProgress by rememberUpdatedState(scrollProgress)
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = modifier) {
        val verticalInset = 12.dp
        val trackHeight = (maxHeight - verticalInset * 2).coerceAtLeast(1.dp)
        val thumbHeight = (maxHeight * visibleFraction)
            .coerceAtLeast(40.dp)
            .coerceAtMost(trackHeight)
        val thumbTop = (trackHeight - thumbHeight) * scrollProgress
        val dateChipTop = (verticalInset + thumbTop - 8.dp)
            .coerceIn(8.dp, (maxHeight - GRID_DATE_CHIP_HEIGHT - 8.dp).coerceAtLeast(8.dp))
        val availableScrollDistancePx = with(LocalDensity.current) {
            (trackHeight - thumbHeight).toPx().coerceAtLeast(1f)
        }

        if ((isDateChipVisible || dateChipAlpha > 0f) && visibleDate != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = dateChipTop)
                    .padding(end = 20.dp)
                    .width(104.dp)
                    .graphicsLayer { alpha = dateChipAlpha },
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = visibleDate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(44.dp)
                .pointerInput(
                    maxFirstVisibleIndex,
                    availableScrollDistancePx,
                ) {
                    var dragProgress = currentScrollProgress
                    detectVerticalDragGestures(
                        onDragStart = {
                            isDragging = true
                            dragProgress = currentScrollProgress
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                        onVerticalDrag = { _, dragAmount ->
                            dragProgress = (
                                dragProgress + dragAmount / availableScrollDistancePx
                            ).coerceIn(0f, 1f)
                            scope.launch {
                                state.scrollToItem(
                                    index = (dragProgress * maxFirstVisibleIndex)
                                        .roundToInt(),
                                )
                            }
                        },
                    )
                }
                .padding(top = verticalInset, bottom = verticalInset, end = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(3.dp)
                    .height(trackHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f),
                    ),
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = thumbTop)
                    .width(6.dp)
                    .height(thumbHeight)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = indicatorAlpha),
                    ),
            )
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
    initialIndex: Int,
    mediaCache: MediaCache,
    motionPhotoResolver: MotionPhotoResolver,
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
    var displayedImagePage by remember { mutableIntStateOf(-1) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val maxViewportDp = if (maxWidth > maxHeight) maxWidth else maxHeight
        val targetMaxSidePx = with(LocalDensity.current) {
            maxViewportDp.roundToPx().coerceAtLeast(1)
        }
        val activePage = pagerState.settledPage.takeIf { !pagerState.isScrollInProgress }

        LaunchedEffect(activePage, displayedImagePage, targetMaxSidePx, items) {
            val currentPage = activePage ?: return@LaunchedEffect
            if (displayedImagePage != currentPage) return@LaunchedEffect

            val nearbyImages = listOfNotNull(
                items.getOrNull(currentPage + 2),
                items.getOrNull(currentPage - 2),
            ).filter { it.type == GalleryMediaType.Image }
            if (nearbyImages.isEmpty()) {
                return@LaunchedEffect
            }

            delay(PHOTO_PREFETCH_DELAY_MS)
            nearbyImages.forEach { image ->
                mediaCache.prefetchFullScreen(image, targetMaxSidePx)
            }
        }

        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val media = items[page]
            val shouldPreloadImage = activePage != null && (
                page == activePage ||
                    (displayedImagePage == activePage && abs(page - activePage) <= 1)
                )
            when (media.type) {
                GalleryMediaType.Image -> {
                    PhotoPage(
                        media = media,
                        isActive = page == activePage,
                        shouldLoadImage = shouldPreloadImage,
                        targetMaxSidePx = targetMaxSidePx,
                        mediaCache = mediaCache,
                        onImageDisplayed = { displayedImagePage = page },
                        motionPhotoResolver = motionPhotoResolver,
                    )
                }
                GalleryMediaType.Video -> VideoPage(
                    media = media,
                    isActive = page == activePage,
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
    shouldLoadImage: Boolean,
    targetMaxSidePx: Int,
    mediaCache: MediaCache,
    onImageDisplayed: () -> Unit,
    motionPhotoResolver: MotionPhotoResolver,
) {
    val context = LocalContext.current
    var imageState by remember(media.uri, targetMaxSidePx) {
        mutableStateOf<BitmapLoadState>(BitmapLoadState.Loading)
    }
    var imageLoadStarted by remember(media.uri, targetMaxSidePx) { mutableStateOf(false) }
    var motionProbe by remember(media.uri) { mutableStateOf<MotionPhotoProbe?>(null) }
    var verifiedAppleSource by remember(media.uri) { mutableStateOf<MotionPhotoSource?>(null) }
    var appleSourceValidationCompleted by remember(media.uri) { mutableStateOf(false) }
    var motionPlaybackFailed by remember(media.uri) { mutableStateOf(false) }
    var showMotionControls by remember(media.uri) { mutableStateOf(false) }
    var motionPlayerViewReady by remember(media.uri) { mutableStateOf(false) }
    var motionPlaybackRequested by remember(media.uri) { mutableStateOf(false) }
    var motionPlaybackLoading by remember(media.uri) { mutableStateOf(false) }
    var motionPlayerReady by remember(media.uri) { mutableStateOf(false) }
    var playbackSource by remember(media.uri) { mutableStateOf<MotionPhotoSource?>(null) }

    LaunchedEffect(media.uri, shouldLoadImage, targetMaxSidePx) {
        if (!shouldLoadImage || imageLoadStarted || imageState !is BitmapLoadState.Loading) {
            return@LaunchedEffect
        }

        imageLoadStarted = true
        imageState = mediaCache.loadFullScreen(media, targetMaxSidePx)
            ?.let(BitmapLoadState::Ready)
            ?: BitmapLoadState.Failed
    }

    LaunchedEffect(isActive, imageState) {
        if (isActive && imageState is BitmapLoadState.Ready) onImageDisplayed()
    }

    LaunchedEffect(media.uri, isActive, motionPhotoResolver) {
        if (!isActive || motionProbe != null) return@LaunchedEffect
        motionProbe = motionPhotoResolver.probe(media)
    }

    LaunchedEffect(
        motionProbe,
        isActive,
        imageState,
        appleSourceValidationCompleted,
        motionPhotoResolver,
    ) {
        val appleProbe = motionProbe as? MotionPhotoProbe.AppleLivePhoto ?: return@LaunchedEffect
        if (
            !isActive ||
                imageState !is BitmapLoadState.Ready ||
                appleSourceValidationCompleted
        ) {
            return@LaunchedEffect
        }

        delay(APPLE_LIVE_PHOTO_VALIDATION_DELAY_MS)
        verifiedAppleSource = motionPhotoResolver.resolveForPlayback(appleProbe)
        appleSourceValidationCompleted = true
    }

    LaunchedEffect(isActive) {
        if (!isActive) {
            showMotionControls = false
            motionPlayerViewReady = false
            motionPlaybackRequested = false
            motionPlaybackLoading = false
            motionPlayerReady = false
            playbackSource = null
        }
    }

    val motionSource = when (val probe = motionProbe) {
        is MotionPhotoProbe.Embedded -> probe.source
        is MotionPhotoProbe.AppleLivePhoto -> verifiedAppleSource
        else -> null
    }

    LaunchedEffect(motionSource, isActive, imageState, motionPlaybackFailed) {
        if (
            !isActive ||
                imageState !is BitmapLoadState.Ready ||
                motionSource == null ||
                playbackSource != null ||
                motionPlaybackFailed
        ) {
            return@LaunchedEffect
        }
        motionPlaybackLoading = true
        motionPlayerReady = false
        playbackSource = motionSource
    }

    val motionPlayer = remember(playbackSource) {
        playbackSource?.let { source ->
            ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItem(motionMediaItem(source))
                prepare()
            }
        }
    }

    if (motionPlayer != null) {
        DisposableEffect(motionPlayer) {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        motionPlaybackLoading = false
                        motionPlayerReady = true
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        showMotionControls = false
                        motionPlayerViewReady = false
                        motionPlaybackRequested = false
                        motionPlaybackLoading = false
                        motionPlayerReady = true
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    showMotionControls = false
                    motionPlayerViewReady = false
                    motionPlaybackRequested = false
                    motionPlaybackFailed = true
                    motionPlaybackLoading = false
                    motionPlayerReady = false
                    playbackSource = null
                }
            }
            motionPlayer.addListener(listener)
            if (motionPlayer.playbackState == Player.STATE_READY) {
                motionPlaybackLoading = false
                motionPlayerReady = true
            }

            onDispose {
                motionPlayer.removeListener(listener)
                motionPlayer.release()
            }
        }
    }

    LaunchedEffect(motionPlayer, motionPlayerViewReady, motionPlaybackRequested) {
        val player = motionPlayer
        if (motionPlayerViewReady && motionPlaybackRequested && player != null) {
            player.seekTo(0)
            player.play()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when (val state = imageState) {
            BitmapLoadState.Loading -> if (isActive) {
                CircularProgressIndicator(color = Color.White)
            }
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

        if (motionPlayer != null && motionPlaybackRequested) {
            AndroidView(
                factory = { viewContext ->
                    (LayoutInflater.from(viewContext)
                        .inflate(R.layout.motion_photo_player_view, null) as PlayerView).apply {
                        useController = false
                        isClickable = true
                        setOnClickListener { showMotionControls = !showMotionControls }
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        player = motionPlayer
                        post { motionPlayerViewReady = true }
                    }
                },
                update = {
                    it.player = motionPlayer
                    it.setOnClickListener { showMotionControls = !showMotionControls }
                    it.post { motionPlayerViewReady = true }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (showMotionControls && motionPlayer != null) {
            CompactVideoControls(
                player = motionPlayer,
                visible = showMotionControls,
                onVisibilityChange = { showMotionControls = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        val motionPreparationVisible = isActive && !motionPlaybackFailed && (
            motionPlaybackLoading ||
                (motionProbe is MotionPhotoProbe.AppleLivePhoto && !appleSourceValidationCompleted)
            )
        if (motionPreparationVisible) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp),
                color = Color.Black.copy(alpha = 0.76f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "모션 사진 준비 중",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        if (
            motionSource != null &&
                motionPlayerReady &&
                !motionPlaybackRequested &&
                !motionPlaybackFailed &&
                isActive
        ) {
            Button(
                onClick = {
                    motionPlaybackRequested = true
                    showMotionControls = false
                },
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
    var showControls by remember(media.uri) { mutableStateOf(false) }
    var playerViewReady by remember(media.uri, isActive) { mutableStateOf(false) }
    var firstFrameRendered by remember(media.uri, isActive) { mutableStateOf(false) }
    val player = remember(media.uri, isActive) {
        if (isActive) {
            ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItem(ExoMediaItem.fromUri(media.uri))
                prepare()
            }
        } else {
            null
        }
    }

    if (player != null) {
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    hasError = true
                    showControls = false
                }

                override fun onRenderedFirstFrame() {
                    firstFrameRendered = true
                }
            }
            player.addListener(listener)

            onDispose {
                player.removeListener(listener)
                player.release()
            }
        }

        LaunchedEffect(player, playerViewReady, hasError) {
            if (playerViewReady && !hasError) player.play()
        }
    } else {
        LaunchedEffect(isActive) {
            showControls = false
            playerViewReady = false
            firstFrameRendered = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (player != null) {
            AndroidView(
                factory = { viewContext ->
                    (LayoutInflater.from(viewContext)
                        .inflate(R.layout.motion_photo_player_view, null) as PlayerView).apply {
                        useController = false
                        setKeepContentOnPlayerReset(true)
                        isClickable = true
                        setOnClickListener {
                            if (!hasError) showControls = !showControls
                        }
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        this.player = player
                        post { playerViewReady = true }
                    }
                },
                update = {
                    it.player = player
                    it.setOnClickListener {
                        if (!hasError) showControls = !showControls
                    }
                    it.post { playerViewReady = true }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (player != null && !firstFrameRendered && !hasError) {
            CircularProgressIndicator(color = Color.White)
        }

        if (showControls && !hasError && player != null) {
            CompactVideoControls(
                player = player,
                visible = showControls,
                onVisibilityChange = { showControls = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

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
private fun CompactVideoControls(
    player: Player,
    visible: Boolean,
    onVisibilityChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    var durationMs by remember(player) { mutableStateOf(player.duration.validPlaybackDuration()) }
    var positionMs by remember(player) { mutableStateOf(player.currentPosition.coerceAtLeast(0L)) }
    var seekPositionMs by remember(player) { mutableStateOf(player.currentPosition.coerceAtLeast(0L)) }
    var isSeeking by remember(player) { mutableStateOf(false) }

    fun refreshProgress() {
        val refreshedDurationMs = player.duration.validPlaybackDuration()
        val maxPositionMs = if (refreshedDurationMs > 0L) refreshedDurationMs else Long.MAX_VALUE
        val refreshedPositionMs = player.currentPosition
            .coerceAtLeast(0L)
            .coerceAtMost(maxPositionMs)

        durationMs = refreshedDurationMs
        positionMs = refreshedPositionMs
        if (!isSeeking) seekPositionMs = refreshedPositionMs
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
                refreshProgress()
            }

            override fun onPlaybackStateChanged(playbackStateNow: Int) {
                playbackState = playbackStateNow
                refreshProgress()
            }
        }

        player.addListener(listener)
        refreshProgress()

        onDispose {
            player.removeListener(listener)
        }
    }

    LaunchedEffect(player, isSeeking) {
        while (true) {
            refreshProgress()
            delay(250)
        }
    }

    LaunchedEffect(visible, isPlaying, isSeeking) {
        if (visible && isPlaying && !isSeeking) {
            delay(VIDEO_CONTROLLER_SHOW_TIMEOUT_MS.toLong())
            if (player.isPlaying && !isSeeking) onVisibilityChange(false)
        }
    }

    val displayedPositionMs = if (isSeeking) seekPositionMs else positionMs
    val sliderMax = durationMs.takeIf { it > 0L } ?: 1L

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        color = Color.Black.copy(alpha = 0.76f),
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .height(56.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        if (playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                    refreshProgress()
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "일시정지" else "재생",
                    tint = Color.White,
                )
            }
            Text(
                text = formatDuration(displayedPositionMs),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(48.dp),
            )
            Slider(
                value = displayedPositionMs
                    .coerceIn(0L, sliderMax)
                    .toFloat(),
                onValueChange = { value ->
                    isSeeking = true
                    seekPositionMs = value.toLong().coerceIn(0L, durationMs)
                },
                onValueChangeFinished = {
                    player.seekTo(seekPositionMs.coerceIn(0L, durationMs))
                    isSeeking = false
                    refreshProgress()
                },
                valueRange = 0f..sliderMax.toFloat(),
                enabled = durationMs > 0L,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatDuration(durationMs),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(48.dp),
            )
        }
    }
}

@Composable
private fun MediaThumbnail(
    media: GalleryMedia?,
    mediaCache: MediaCache,
    modifier: Modifier = Modifier,
) {
    var requestedMaxDimensionPx by remember { mutableIntStateOf(0) }
    var thumbnailState by remember(media) {
        mutableStateOf<BitmapLoadState>(
            if (media == null) BitmapLoadState.Failed else BitmapLoadState.Loading,
        )
    }

    LaunchedEffect(media, requestedMaxDimensionPx, mediaCache) {
        if (media == null || requestedMaxDimensionPx <= 0) return@LaunchedEffect
        thumbnailState = BitmapLoadState.Loading
        thumbnailState = mediaCache.loadThumbnail(media, requestedMaxDimensionPx)
            ?.let(BitmapLoadState::Ready)
            ?: BitmapLoadState.Failed
    }

    Box(
        modifier = modifier
            .onSizeChanged { size ->
                requestedMaxDimensionPx = max(size.width, size.height)
            }
            .background(MaterialTheme.colorScheme.surfaceVariant),
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

private fun Long.formatGalleryMonth(): String? {
    if (this <= 0L) return null

    val date = Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    return DateTimeFormatter
        .ofPattern("yyyy년 M월", Locale.KOREA)
        .format(date)
}

private fun Long.validPlaybackDuration(): Long = takeIf { it > 0L } ?: 0L

private val GRID_DATE_CHIP_HEIGHT = 38.dp
private const val GRID_DATE_CHIP_HIDE_DELAY_MS = 750L
private const val LOW_BATTERY_THRESHOLD_PERCENT = 20
private const val VIDEO_CONTROLLER_SHOW_TIMEOUT_MS = 3_000
private const val PHOTO_PREFETCH_DELAY_MS = 100L
private const val APPLE_LIVE_PHOTO_VALIDATION_DELAY_MS = 150L

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
