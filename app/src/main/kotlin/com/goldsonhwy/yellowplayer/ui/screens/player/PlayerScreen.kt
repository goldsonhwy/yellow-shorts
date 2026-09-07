package com.goldsonhwy.yellowplayer.ui.screens.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.TextureView
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.navigation.NavController
import com.goldsonhwy.yellowplayer.data.model.VideoSource
import com.goldsonhwy.yellowplayer.R
import com.goldsonhwy.yellowplayer.smb.SmbStreamDataSource
import com.goldsonhwy.yellowplayer.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(
    navController: NavController,
    source: VideoSource,
    folderPath: String,
    startIndex: Int = 0,
    viewModel: PlayerViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()

    var deletedPaths by remember { mutableStateOf(setOf<String>()) }
    val videos = uiState.videos.filterNot { it.path in deletedPaths }

    var currentIndex by remember { mutableIntStateOf(startIndex.coerceAtLeast(0)) }
    var isFavorite by remember { mutableStateOf(false) }
    var showLikeAnimation by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableFloatStateOf(1f) }
    val longPressSpeed by remember { mutableFloatStateOf(context.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE).getFloat("long_press_speed", 2f)) }
    var seekProgress by remember { mutableFloatStateOf(0f) }
    var progress by remember { mutableFloatStateOf(0f) }
    var bufferedProgress by remember { mutableFloatStateOf(0f) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var actualSpeed by remember { mutableFloatStateOf(1f) }
    var lastShortTapAt by remember { mutableLongStateOf(0L) }
    var playerResizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_ZOOM) }
    var videoScale by remember { mutableFloatStateOf(1f) }
    var suppressSingleFingerGestures by remember { mutableStateOf(false) }
    var isPinching by remember { mutableStateOf(false) }
    var videoRotation by remember { mutableIntStateOf(0) }
    var longPressSpeedBefore by remember { mutableFloatStateOf(1f) }
    var isLongPressSpeedActive by remember { mutableStateOf(false) }
    val verticalDragOffset = remember { Animatable(0f) }

    LaunchedEffect(source, folderPath) {
        viewModel.loadVideos(source, folderPath)
    }

    LaunchedEffect(videos) {
        if (videos.isNotEmpty()) currentIndex = currentIndex.coerceIn(0, videos.lastIndex)
    }

    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SmbStreamDataSource.Factory(context)))
            .build().apply {
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        if (lifecycleOwner == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    val allowBackgroundPlayback = context.getSharedPreferences(
                        "player_prefs", android.content.Context.MODE_PRIVATE
                    ).getBoolean("background_playback_enabled", false)
                    if (!allowBackgroundPlayback) player.pause()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(videos, currentIndex) {
        val video = videos.getOrNull(currentIndex)
        if (video != null) {
            // Do not clear/stop first; this reduces black flicker when switching videos.
            player.setMediaItem(MediaItem.fromUri(video.fileUri), 0L)
            player.prepare()
            player.setPlaybackSpeed(currentSpeed)
            actualSpeed = currentSpeed
            player.playWhenReady = true
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(400)
            val dur = player.duration
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = dur.coerceAtLeast(0L)
            progress = if (dur > 0) player.currentPosition.toFloat() / dur else 0f
            bufferedProgress = (player.bufferedPercentage / 100f).coerceIn(0f, 1f)
        }
    }

    LaunchedEffect(source, currentIndex, videos) {
        if (source == VideoSource.SAMBA) {
            val serverId = folderPath.substringBefore('|').toLongOrNull() ?: 0L
            val next = videos.getOrNull(currentIndex + 1)
            if (serverId > 0 && next != null) viewModel.prefetchSmbHeaderAsync(serverId, next.path)
        }
    }

    LaunchedEffect(showLikeAnimation) {
        if (showLikeAnimation) {
            delay(700)
            showLikeAnimation = false
        }
    }


    val currentPathForDispose by rememberUpdatedState(videos.getOrNull(currentIndex)?.path)

    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.statusBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            if (window != null) {
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.statusBars())
                // MainActivity enables edge-to-edge globally. Restoring decorFitsSystemWindows=true
                // here makes the browser Scaffold receive duplicated system-bar insets after leaving
                // the player, which visually pushes the whole gallery downward. Keep the app's
                // original edge-to-edge layout mode and only bring the status bar back.
                WindowCompat.setDecorFitsSystemWindows(window, false)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val path = currentPathForDispose
            player.release()
            if (path != null) {
                viewModel.moveFavoriteAfterReleaseAsync(path)
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val contentWidth = videoSize.width.takeIf { it > 0 } ?: return
                val contentHeight = videoSize.height.takeIf { it > 0 } ?: return
                val pixelRatio = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                val displayAspect = (contentWidth * pixelRatio) / contentHeight.toFloat()
                playerResizeMode = if (displayAspect < 1f) {
                    // 竖屏短视频保留铺满裁切；横屏/方形视频必须完整显示，不能被放大裁掉左右。
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(videos.getOrNull(currentIndex)?.path) {
        val video = videos.getOrNull(currentIndex)
        isFavorite = if (video != null) viewModel.isFavorite(video.path) else false
        videoRotation = if (video != null) viewModel.getVideoRotation(video.path) else 0
    }

    LaunchedEffect(isPinching) {
        if (isPinching && isLongPressSpeedActive) {
            player.setPlaybackSpeed(longPressSpeedBefore)
            actualSpeed = longPressSpeedBefore
            isLongPressSpeedActive = false
        }
    }

    fun toggleCurrentFavorite(showAnimation: Boolean = true) {
        val video = videos.getOrNull(currentIndex) ?: return
        scope.launch {
            val result = viewModel.toggleFavorite(video)
            isFavorite = result != null
            if (showAnimation && isFavorite) showLikeAnimation = true
        }
    }

    fun shareCurrentVideo() {
        val video = videos.getOrNull(currentIndex) ?: return
        if (source != VideoSource.LOCAL) {
            Toast.makeText(context, "当前仅支持分享本地文件", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val file = File(video.path)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = video.mimeType.ifEmpty { "video/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享视频"))
        } catch (t: Throwable) {
            Toast.makeText(context, "分享失败：${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun removeCurrentVideoFromPlaylist(video: com.goldsonhwy.yellowplayer.data.model.VideoInfo) {
        deletedPaths = deletedPaths + video.path
        val nextSize = videos.size - 1
        currentIndex = when {
            nextSize <= 0 -> 0
            currentIndex >= nextSize -> nextSize - 1
            else -> currentIndex
        }
    }

    fun deleteCurrentVideo() {
        val video = videos.getOrNull(currentIndex) ?: return
        if (source == VideoSource.SAMBA) {
            player.stop()
            scope.launch {
                val result = viewModel.deleteSmbVideo(video)
                result.fold(
                    onSuccess = {
                        removeCurrentVideoFromPlaylist(video)
                    },
                    onFailure = { error ->
                        Toast.makeText(context, "删除失败：${error.message.orEmpty()}", Toast.LENGTH_LONG).show()
                        player.prepare()
                        player.playWhenReady = true
                    }
                )
            }
            return
        }
        try {
            val file = File(video.path)
            if (!file.delete()) {
                Toast.makeText(context, "删除失败：文件不存在或无法删除", Toast.LENGTH_LONG).show()
                return
            }
            removeCurrentVideoFromPlaylist(video)
        } catch (t: Throwable) {
            Toast.makeText(context, "删除失败：${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun stepFrame(deltaMs: Long) {
        player.playWhenReady = false
        player.pause()
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        player.seekTo(target)
        player.playWhenReady = false
        player.pause()
    }

    fun rotateCurrentVideoClockwise() {
        val video = videos.getOrNull(currentIndex) ?: return
        val newRotation = (videoRotation + 90).floorMod360()
        videoRotation = newRotation
        viewModel.saveVideoRotation(video.path, newRotation)
    }

    fun switchToIndex(newIndex: Int) {
        val oldPath = videos.getOrNull(currentIndex)?.path
        currentIndex = newIndex.coerceIn(0, videos.lastIndex)
        if (oldPath != null) {
            viewModel.finalizeVideoAfterSwitchAsync(oldPath)
        }
    }

    Box(Modifier
        .fillMaxSize()
        .background(Color.Black)
    ) {
        if (videos.isNotEmpty()) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.view_player_texture, null, false) as PlayerView).apply {
                        this.player = player
                        useController = false
                        resizeMode = playerResizeMode
                        keepScreenOn = true
                    }
                },
                update = { view ->
                    view.resizeMode = playerResizeMode
                    val textureView = view.videoSurfaceView as? TextureView
                    textureView?.post {
                        applyTextureRotation(textureView, videoRotation)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 34.dp, bottom = 106.dp)
                    .graphicsLayer {
                        scaleX = videoScale
                        scaleY = videoScale
                        translationY = verticalDragOffset.value
                    }
            )
        }

        // Top progress bar.
        if (videos.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Surface(
                        modifier = Modifier.clickable { rotateCurrentVideoClockwise() },
                        shape = androidx.compose.material3.MaterialTheme.shapes.extraLarge,
                        color = DarkSurfaceVariant
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Icon(Icons.Default.RotateRight, "旋转", tint = Yellow500, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(2.dp))
                            androidx.compose.material3.Text("${videoRotation}°", color = Yellow500, fontSize = 11.sp)
                        }
                    }
                    listOf(1f, 1.5f, 2f, 3f, 4f).forEach { speed ->
                        androidx.compose.material3.Surface(
                            modifier = Modifier.clickable {
                                currentSpeed = speed
                                actualSpeed = speed
                                player.setPlaybackSpeed(speed)
                            },
                            shape = androidx.compose.material3.MaterialTheme.shapes.extraLarge,
                            color = if (currentSpeed == speed) Yellow500 else DarkSurfaceVariant
                        ) {
                            androidx.compose.material3.Text(
                                text = if (speed == 1f) "正常" else "${speed}x",
                                color = if (currentSpeed == speed) Black else White,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                    androidx.compose.material3.Surface(
                        shape = androidx.compose.material3.MaterialTheme.shapes.extraLarge,
                        color = DarkSurfaceVariant
                    ) {
                        androidx.compose.material3.Text(
                            text = "${"%.1f".format(actualSpeed)}x",
                            color = Yellow500,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }            }
        }

        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text("正在加载视频…", color = Yellow500)
            }
        } else if (videos.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text("没有可播放的视频", color = White)
            }
        }

        if (showLikeAnimation) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    tint = LikeRed,
                    modifier = Modifier.size(132.dp)
                )
            }
        }

        // Bottom timeline + five equally spaced buttons.
        if (videos.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = (-10).dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    androidx.compose.material3.Text(formatPlayerTime(positionMs), color = White, fontSize = 12.sp)
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Slider(
                            value = progress.coerceIn(0f, 1f),
                            onValueChange = { value ->
                                val dur = durationMs
                                if (dur > 0) {
                                    val target = (dur * value).toLong().coerceIn(0L, dur)
                                    player.seekTo(target)
                                    positionMs = target
                                    progress = value.coerceIn(0f, 1f)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(0.95f),
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = Yellow500,
                                activeTrackColor = Yellow500,
                                inactiveTrackColor = Yellow500.copy(alpha = 0.25f)
                            )
                        )
                    }
                    androidx.compose.material3.Text(formatPlayerTime(durationMs), color = White, fontSize = 12.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CapsuleIconButton(
                        onClick = { shareCurrentVideo() },
                        modifier = Modifier.weight(1f),
                        contentDescription = "分享"
                    ) {
                        Icon(Icons.Default.Share, "分享", tint = Black, modifier = Modifier.size(24.dp))
                    }

                    CapsuleIconButton(
                        onClick = { stepFrame(-33L) },
                        modifier = Modifier.weight(1f),
                        contentDescription = "逐帧后退"
                    ) {
                        Icon(Icons.Default.SkipPrevious, "逐帧后退", tint = Black, modifier = Modifier.size(24.dp))
                    }

                    CapsuleIconButton(
                        onClick = { toggleCurrentFavorite(showAnimation = true) },
                        modifier = Modifier.weight(1f),
                        contentDescription = "收藏"
                    ) {
                        Icon(
                            if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            "收藏",
                            tint = Black,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    CapsuleIconButton(
                        onClick = { stepFrame(33L) },
                        modifier = Modifier.weight(1f),
                        contentDescription = "逐帧前进"
                    ) {
                        Icon(Icons.Default.SkipNext, "逐帧前进", tint = Black, modifier = Modifier.size(24.dp))
                    }

                    CapsuleIconButton(
                        onClick = { deleteCurrentVideo() },
                        modifier = Modifier.weight(1f),
                        contentDescription = "删除"
                    ) {
                        Icon(Icons.Default.Delete, "删除", tint = Black, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }

        // Gesture layer:
        // - 双指缩放：手动控制当前播放器会话的视频大小；切换视频不复位，退出播放器才恢复默认。
        // - 上下滑动：使用 TikTok 式拖动跟随和滑出切换；缩放后仍然允许单指上下切换。
        // - 左右滑动：恢复四个横向进度区，越靠下越快。
        // - 底部进度条/按钮区域不覆盖，避免阻挡 Slider 和按钮点击。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 44.dp)
                .navigationBarsPadding()
                .padding(bottom = 150.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        var sawMultiTouch = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                sawMultiTouch = true
                                isPinching = true
                                val zoom = event.calculateZoom()
                                if (zoom.isFinite() && zoom != 1f) {
                                    videoScale = (videoScale * zoom).coerceIn(0.5f, 5.0f)
                                }
                                event.changes.forEach { it.consume() }
                                if (isLongPressSpeedActive) {
                                    player.setPlaybackSpeed(longPressSpeedBefore)
                                    actualSpeed = longPressSpeedBefore
                                    isLongPressSpeedActive = false
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (sawMultiTouch) {
                            isPinching = false
                            suppressSingleFingerGestures = true
                            scope.launch {
                                delay(200L)
                                suppressSingleFingerGestures = false
                            }
                        }
                    }
                }
                .pointerInput(currentSpeed, longPressSpeed, suppressSingleFingerGestures, isPinching) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        if (suppressSingleFingerGestures || isPinching) {
                            waitForUpOrCancellation()
                            return@awaitEachGesture
                        }
                        val releasedBeforeLongPress = withTimeoutOrNull(520L) {
                            waitForUpOrCancellation()
                        }
                        if (releasedBeforeLongPress != null) {
                            val now = System.currentTimeMillis()
                            if (now - lastShortTapAt <= 320L) {
                                lastShortTapAt = 0L
                                toggleCurrentFavorite(showAnimation = true)
                            } else {
                                lastShortTapAt = now
                                scope.launch {
                                    delay(330L)
                                    if (lastShortTapAt == now) {
                                        if (player.isPlaying) player.pause() else player.play()
                                        lastShortTapAt = 0L
                                    }
                                }
                            }
                        } else {
                            if (suppressSingleFingerGestures || isPinching) return@awaitEachGesture
                            val speedBeforeLongPress = currentSpeed
                            longPressSpeedBefore = speedBeforeLongPress
                            isLongPressSpeedActive = true
                            val temporarySpeed = (speedBeforeLongPress * longPressSpeed).coerceIn(0.1f, 25f)
                            player.setPlaybackSpeed(temporarySpeed)
                            actualSpeed = temporarySpeed
                            try {
                                waitForUpOrCancellation()
                            } finally {
                                player.setPlaybackSpeed(speedBeforeLongPress)
                                actualSpeed = speedBeforeLongPress
                                isLongPressSpeedActive = false
                            }
                        }
                    }
                }
                .pointerInput(videos, currentIndex, suppressSingleFingerGestures, isPinching) {
                    var horizontalSeekDeltaMs = 0L
                    detectHorizontalDragGestures(
                        onDragStart = {
                            horizontalSeekDeltaMs = 0L
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (suppressSingleFingerGestures || isPinching || change.pressed != true) return@detectHorizontalDragGestures
                            change.consume()
                            val duration = player.duration.takeIf { it > 0 } ?: 0L
                            val screenH = size.height.coerceAtLeast(1)
                            val zone = ((change.position.y / screenH) * 4f).toInt().coerceIn(0, 3)
                            val percentPerPx = when (zone) {
                                0 -> 0.00010f  // 最上区：100px ≈ 1%
                                1 -> 0.00020f  // 第二区：100px ≈ 2%
                                2 -> 0.00050f  // 第三区：100px ≈ 5%
                                else -> 0.00100f // 最下区：100px ≈ 10%
                            }
                            val deltaMs = (duration * dragAmount * percentPerPx).toLong()
                            horizontalSeekDeltaMs += deltaMs
                            seekProgress = if (duration > 0) {
                                (horizontalSeekDeltaMs.toFloat() / duration).coerceIn(-0.5f, 0.5f)
                            } else 0f
                        },
                        onDragEnd = {
                            val duration = player.duration
                            if (duration > 0 && horizontalSeekDeltaMs != 0L) {
                                val seekTo = (player.currentPosition + horizontalSeekDeltaMs).coerceIn(0L, duration)
                                player.seekTo(seekTo)
                            }
                            seekProgress = 0f
                            horizontalSeekDeltaMs = 0L
                        }
                    )
                }
                .pointerInput(videos, currentIndex, suppressSingleFingerGestures, isPinching) {
                    var totalVerticalDrag = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            totalVerticalDrag = 0f
                        },
                        onVerticalDrag = { change, dragAmount ->
                            if (suppressSingleFingerGestures || isPinching || change.pressed != true) return@detectVerticalDragGestures
                            change.consume()
                            totalVerticalDrag += dragAmount
                            scope.launch { verticalDragOffset.snapTo(verticalDragOffset.value + dragAmount) }
                        },
                        onDragEnd = {
                            val dragDistance = totalVerticalDrag
                            totalVerticalDrag = 0f
                            scope.launch {
                                if (videos.isNotEmpty() && abs(dragDistance) >= 120f) {
                                    val direction = when {
                                        dragDistance < 0f && currentIndex < videos.lastIndex -> -1f
                                        dragDistance > 0f && currentIndex > 0 -> 1f
                                        else -> 0f
                                    }
                                    if (direction != 0f) {
                                        verticalDragOffset.animateTo(direction * size.height, animationSpec = tween(180))
                                        when {
                                            direction < 0f -> switchToIndex(currentIndex + 1)
                                            direction > 0f -> switchToIndex(currentIndex - 1)
                                        }
                                        verticalDragOffset.snapTo(-direction * size.height)
                                        verticalDragOffset.animateTo(0f, animationSpec = tween(180))
                                    } else {
                                        verticalDragOffset.animateTo(0f, animationSpec = tween(160))
                                    }
                                } else {
                                    verticalDragOffset.animateTo(0f, animationSpec = tween(160))
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch { verticalDragOffset.animateTo(0f, animationSpec = tween(160)) }
                            totalVerticalDrag = 0f
                        }
                    )
                }

        )
    }
}

@Composable
private fun CapsuleIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    androidx.compose.material3.Surface(
        modifier = modifier
            .padding(horizontal = 3.dp)
            .offset(y = 2.dp)
            .height(34.dp)
            .clickable(onClick = onClick),
        shape = androidx.compose.material3.MaterialTheme.shapes.extraLarge,
        color = Yellow500
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}
private fun Int.floorMod360(): Int = ((this % 360) + 360) % 360

private fun applyTextureRotation(textureView: TextureView, rotation: Int) {
    val normalized = rotation.floorMod360()
    val width = textureView.width.toFloat()
    val height = textureView.height.toFloat()
    if (width <= 0f || height <= 0f) return

    textureView.pivotX = width / 2f
    textureView.pivotY = height / 2f
    textureView.rotation = normalized.toFloat()
    val scale = if (normalized == 90 || normalized == 270) {
        minOf(width / height, height / width)
    } else {
        1f
    }
    textureView.scaleX = scale
    textureView.scaleY = scale
}

private fun formatPlayerTime(millis: Long): String {
    val totalSec = (millis / 1000).coerceAtLeast(0L)
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}
