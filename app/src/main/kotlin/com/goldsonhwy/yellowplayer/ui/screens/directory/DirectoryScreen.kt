package com.goldsonhwy.yellowplayer.ui.screens.directory

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.goldsonhwy.yellowplayer.data.model.VideoFolder
import com.goldsonhwy.yellowplayer.data.model.VideoInfo
import com.goldsonhwy.yellowplayer.data.model.VideoSource
import com.goldsonhwy.yellowplayer.ui.navigation.Routes
import com.goldsonhwy.yellowplayer.ui.theme.*
import java.io.File

/**
 * Directory screen — shows a 3-column grid of video folders or files.
 * Uses ViewModel with real VideoScanner for local storage.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DirectoryScreen(
    navController: NavController,
    source: VideoSource,
    serverId: Long = 0,
    folderPath: String = "",
    viewModel: DirectoryViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()
    var showSortDialog by remember { mutableStateOf(false) }
    val sortPrefs = remember { context.getSharedPreferences("directory_sort", android.content.Context.MODE_PRIVATE) }
    val sortKey = remember(source, serverId, folderPath) { "${source.name}_${serverId}_${folderPath.hashCode()}" }
    var sortMode by remember(sortKey) { mutableStateOf(sortPrefs.getString(sortKey, "name") ?: "name") }
    var moveProgressText by remember { mutableStateOf("") }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var showMoreActions by remember { mutableStateOf(false) }
    var showProperties by remember { mutableStateOf<VideoInfo?>(null) }
    var renameTarget by remember { mutableStateOf<VideoInfo?>(null) }
    var renameText by remember { mutableStateOf("") }
    val thumbnailSize = remember { context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE).getInt("thumbnail_size", 180) }
    val gridColumns = when {
        thumbnailSize <= 140 -> 4
        thumbnailSize >= 220 -> 2
        else -> 3
    }

    fun selectedVideos(): List<VideoInfo> = uiState.videos.filter { it.path in selectedPaths }
    fun selectedFolders(): List<VideoFolder> = uiState.folders.filter { it.path in selectedPaths }
    fun clearSelection() { selectedPaths = emptySet() }
    fun deleteSelected() {
        if (selectedFolders().isNotEmpty() && source == VideoSource.SAMBA) {
            viewModel.deleteSmbFolders(serverId, selectedFolders().map { it.path })
            Toast.makeText(context, "正在删除 SMB 文件夹", Toast.LENGTH_SHORT).show()
            clearSelection()
            return
        }
        selectedVideos().forEach { v -> runCatching { File(v.path).delete() } }
        Toast.makeText(context, "已删除 ${selectedPaths.size} 个", Toast.LENGTH_SHORT).show()
        clearSelection()
    }
    fun setSelectedFoldersCommon() {
        if (source != VideoSource.SAMBA) return
        val prefs = context.getSharedPreferences("smb_common_folders", android.content.Context.MODE_PRIVATE)
        val set = prefs.getStringSet("items", emptySet()).orEmpty().toMutableSet()
        selectedFolders().forEach { f -> set.add("${f.serverId}|${f.path}") }
        prefs.edit().putStringSet("items", set).apply()
        Toast.makeText(context, "已设为常用 SMB 文件夹", Toast.LENGTH_SHORT).show()
        clearSelection()
    }
    fun favoriteSelected() {
        val favDir = context.getSharedPreferences("favorite_move", android.content.Context.MODE_PRIVATE).getString("dir", "").orEmpty()
        if (favDir.isEmpty()) {
            Toast.makeText(context, "请先选择收藏目录", Toast.LENGTH_SHORT).show()
            return
        }
        selectedVideos().forEach { v -> runCatching { File(v.path).copyTo(File(favDir, File(v.path).name), overwrite = true) } }
        Toast.makeText(context, "已批量收藏", Toast.LENGTH_SHORT).show()
        clearSelection()
    }
    fun copyOrCutToFavorite(move: Boolean) {
        val favDir = context.getSharedPreferences("favorite_move", android.content.Context.MODE_PRIVATE).getString("dir", "").orEmpty()
        if (favDir.isEmpty()) {
            Toast.makeText(context, "请先选择收藏目录", Toast.LENGTH_SHORT).show()
            return
        }
        selectedVideos().forEach { v ->
            runCatching {
                val src = File(v.path)
                src.copyTo(File(favDir, src.name), overwrite = true)
                if (move) src.delete()
            }
        }
        Toast.makeText(context, if (move) "已剪切到收藏目录" else "已复制到收藏目录", Toast.LENGTH_SHORT).show()
        clearSelection()
    }

    val isFavorites = folderPath == "__favorites__"
    val isRootLevel = folderPath.isEmpty()

    // Load data on first composition
    LaunchedEffect(source, folderPath) {
        when {
            isFavorites -> { /* Load favorites from Room */ }
            isRootLevel -> viewModel.loadFolders(source, serverId)
            else -> viewModel.loadVideosInFolder(source, folderPath, serverId)
        }
    }

    val title = when {
        isFavorites -> "收藏"
        isRootLevel -> when (source) {
            VideoSource.LOCAL -> "本地存储"
            VideoSource.EXTERNAL -> "外置存储"
            VideoSource.SAMBA -> "Samba"
        }
        else -> folderPath.substringAfterLast("/").ifEmpty { "视频" }
    }

    val sortedFolders = remember(uiState.folders, sortMode) {
        when (sortMode) {
            "count" -> uiState.folders.sortedByDescending { it.videoCount }
            "name_desc" -> uiState.folders.sortedByDescending { it.name }
            else -> uiState.folders.sortedBy { it.name }
        }
    }
    val sortedVideos = remember(uiState.videos, sortMode) {
        when (sortMode) {
            "date" -> uiState.videos.sortedByDescending { it.dateModified }
            "size" -> uiState.videos.sortedByDescending { it.size }
            "name_desc" -> uiState.videos.sortedByDescending { it.name }
            else -> uiState.videos.sortedBy { it.name }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val all = context.getSharedPreferences("favorite_move_progress", android.content.Context.MODE_PRIVATE).all
            moveProgressText = all.entries.firstOrNull()?.let { entry ->
                val parts = entry.value.toString().split('/')
                val copied = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                val total = parts.getOrNull(1)?.toLongOrNull() ?: 1L
                "正在移动收藏文件：${((copied * 100) / total.coerceAtLeast(1L))}%"
            }.orEmpty()
            kotlinx.coroutines.delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedPaths.isNotEmpty()) "已选择 ${selectedPaths.size} 个" else title,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selectedPaths.isNotEmpty()) clearSelection() else navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground),
                actions = {
                    if (selectedPaths.isNotEmpty()) {
                        if (selectedFolders().isNotEmpty()) {
                            IconButton(onClick = { selectedPaths = uiState.folders.map { it.path }.toSet() }) { Icon(Icons.Default.SelectAll, "全选", tint = Yellow500) }
                            IconButton(onClick = { setSelectedFoldersCommon() }) { Icon(Icons.Default.Star, "设为常用", tint = Yellow500) }
                        }
                        IconButton(onClick = { deleteSelected() }) { Icon(Icons.Default.Delete, "删除", tint = LikeRed) }
                        IconButton(onClick = { favoriteSelected() }) { Icon(Icons.Default.Favorite, "批量收藏", tint = LikeRed) }
                        IconButton(onClick = { showMoreActions = true }) { Icon(Icons.Default.MoreVert, "更多", tint = TextSecondary) }
                    } else {
                        IconButton(onClick = { showSortDialog = true }) {
                            Icon(Icons.Default.Sort, "排序", tint = TextSecondary)
                        }
                    }
                }
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        when {
            // Loading state
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SafeLoadingIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在扫描视频…", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            }

            // Error state
            uiState.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = LikeRed,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = uiState.error ?: "未知错误",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Favorites — empty state
            isFavorites -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            tint = LikeRed,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("还没有收藏视频", color = TextSecondary, fontSize = 16.sp)
                        Text("在全屏播放时双击 ❤️ 即可收藏", color = TextHint, fontSize = 14.sp)
                    }
                }
            }

            // Empty state
            uiState.folders.isEmpty() && uiState.videos.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.VideoLibrary,
                            contentDescription = null,
                            tint = TextHint,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("没有找到视频文件", color = TextSecondary, fontSize = 16.sp)
                        Text("支持 mp4, mkv, avi, mov, flv, webm 等格式", color = TextHint, fontSize = 13.sp)
                    }
                }
            }

            // SMB folders and files share one level. Opening a folder pushes
            // another browse route, so this works at any depth.
            uiState.folders.isNotEmpty() || uiState.videos.isNotEmpty() -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 4.dp),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    if (moveProgressText.isNotEmpty()) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text(moveProgressText, color = Yellow500, modifier = Modifier.padding(8.dp), fontSize = 13.sp)
                        }
                    }

                    items(sortedFolders, key = { it.path }) { folder ->
                        VideoFolderCard(
                            folder = folder,
                            thumbnailSize = thumbnailSize,
                            selected = folder.path in selectedPaths,
                            isCommon = context.getSharedPreferences("smb_common_folders", android.content.Context.MODE_PRIVATE).getStringSet("items", emptySet()).orEmpty().contains("${folder.serverId}|${folder.path}"),
                            onLongClick = {
                                if (source == VideoSource.SAMBA) {
                                    selectedPaths = selectedPaths + folder.path
                                }
                            },
                            onClick = {
                                if (selectedPaths.isNotEmpty()) {
                                    selectedPaths = if (folder.path in selectedPaths) selectedPaths - folder.path else selectedPaths + folder.path
                                    return@VideoFolderCard
                                }
                                if (source == VideoSource.SAMBA) {
                                    navController.navigate(
                                        Routes.sambaBrowse(folder.serverId.takeIf { it > 0 } ?: serverId, folder.path)
                                    )
                                } else {
                                    navController.navigate(Routes.directory(source, folder.path))
                                }
                            }
                        )
                    }

                    items(sortedVideos, key = { it.path }) { video ->
                        VideoThumbnailCard(
                            video = video,
                            thumbnailSize = thumbnailSize,
                            selected = video.path in selectedPaths,
                            disableThumbnail = source == VideoSource.SAMBA,
                            onLongClick = { selectedPaths = selectedPaths + video.path },
                            onClick = {
                                if (selectedPaths.isNotEmpty()) {
                                    selectedPaths = if (video.path in selectedPaths) selectedPaths - video.path else selectedPaths + video.path
                                    return@VideoThumbnailCard
                                }
                                val idx = sortedVideos.indexOf(video)
                                navController.navigate(
                                    Routes.player(source, if (source == VideoSource.SAMBA) "${serverId}|${uiState.currentFolderPath}" else uiState.currentFolderPath, idx)
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    if (showMoreActions) {
        AlertDialog(
            onDismissRequest = { showMoreActions = false },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("批量操作", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    TextButton(onClick = {
                        showMoreActions = false
                        val first = selectedVideos().firstOrNull()
                        if (first != null) showProperties = first
                    }) { Text("查看属性", color = TextPrimary) }
                    TextButton(onClick = {
                        showMoreActions = false
                        val first = selectedVideos().firstOrNull()
                        if (first != null) { renameTarget = first; renameText = first.name }
                    }) { Text("重命名第一个", color = TextPrimary) }
                    TextButton(onClick = {
                        showMoreActions = false
                        copyOrCutToFavorite(move = false)
                    }) { Text("复制到收藏目录", color = TextPrimary) }
                    TextButton(onClick = {
                        showMoreActions = false
                        copyOrCutToFavorite(move = true)
                    }) { Text("剪切到收藏目录", color = TextPrimary) }
                }
            },
            confirmButton = { TextButton(onClick = { showMoreActions = false }) { Text("关闭", color = Yellow500) } }
        )
    }

    showProperties?.let { v ->
        AlertDialog(
            onDismissRequest = { showProperties = null },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("属性", fontWeight = FontWeight.Bold) },
            text = { Text("名称：${v.name}\n路径：${v.path}\n大小：${v.size} bytes\n修改时间：${v.dateModified}") },
            confirmButton = { TextButton(onClick = { showProperties = null }) { Text("关闭", color = Yellow500) } }
        )
    }

    renameTarget?.let { v ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("重命名", fontWeight = FontWeight.Bold) },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        val old = File(v.path)
                        old.renameTo(File(old.parentFile, renameText))
                    }
                    renameTarget = null
                    clearSelection()
                }) { Text("保存", color = Yellow500) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消", color = TextSecondary) } }
        )
    }

    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("排序方式", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    listOf(
                        "name" to "名称 A-Z",
                        "name_desc" to "名称 Z-A",
                        "date" to "最新优先",
                        "size" to "大文件优先",
                        "count" to "文件夹视频数"
                    ).forEach { (key, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = sortMode == key,
                                onClick = {
                                    sortMode = key
                                    sortPrefs.edit().putString(sortKey, key).apply()
                                    showSortDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = Yellow500)
                            )
                            Text(label, color = TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSortDialog = false }) {
                    Text("关闭", color = Yellow500)
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoFolderCard(
    folder: VideoFolder,
    thumbnailSize: Int = 180,
    selected: Boolean = false,
    isCommon: Boolean = false,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .aspectRatio(0.75f)
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (selected) Yellow500.copy(alpha = 0.38f) else DarkSurfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (folder.thumbnailPath.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(Uri.fromFile(java.io.File(folder.thumbnailPath)))
                        .crossfade(false)
                        .size(thumbnailSize)
                        .build(),
                    contentDescription = folder.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = Yellow500.copy(alpha = 0.6f),
                    modifier = Modifier.size(36.dp)
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
                shape = MaterialTheme.shapes.extraSmall,
                color = Color.Black.copy(alpha = 0.7f)
            ) {
                Text(
                    text = if (folder.source == VideoSource.SAMBA) "目录" else "${folder.videoCount}",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            if (isCommon) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = Yellow500
                ) {
                    Row(modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, null, tint = Black, modifier = Modifier.size(12.dp))
                        Text("常用", color = Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Text(
            text = folder.name,
            color = TextPrimary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoThumbnailCard(
    video: VideoInfo,
    thumbnailSize: Int = 180,
    selected: Boolean = false,
    disableThumbnail: Boolean = false,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .aspectRatio(0.75f)
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (selected) Yellow500.copy(alpha = 0.35f) else DarkSurfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (disableThumbnail) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Movie, contentDescription = null, tint = Yellow500, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(formatSize(video.size), color = TextSecondary, fontSize = 11.sp)
                }
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(video.fileUri)
                        .crossfade(false)
                        .size(thumbnailSize)
                        .build(),
                    contentDescription = video.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Duration badge
            if (video.duration > 0) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = Color.Black.copy(alpha = 0.7f)
                ) {
                    Text(
                        text = formatDuration(video.duration),
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = video.name,
                color = TextPrimary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!disableThumbnail) {
                Text(
                    text = formatSize(video.size),
                    color = Yellow500,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSec = millis / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "未知大小"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) "%.1fGB".format(mb / 1024.0) else "%.1fMB".format(mb)
}

@Composable
private fun SafeLoadingIndicator() {
    // Avoid Material3 CircularProgressIndicator here: v0.0.7 crash logs showed
    // a NoSuchMethodError inside its keyframes animation on Android SDK 36.
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = DarkSurfaceVariant
    ) {
        Text(
            text = "扫描中",
            color = Yellow500,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
