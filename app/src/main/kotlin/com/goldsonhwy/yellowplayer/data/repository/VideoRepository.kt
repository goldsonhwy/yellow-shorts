package com.goldsonhwy.yellowplayer.data.repository

import android.content.Context
import android.net.Uri
import com.goldsonhwy.yellowplayer.data.local.db.*
import com.goldsonhwy.yellowplayer.data.local.preferences.SettingsDataStore
import com.goldsonhwy.yellowplayer.data.model.*
import com.goldsonhwy.yellowplayer.data.scanner.VideoScanner
import com.goldsonhwy.yellowplayer.data.scanner.LocalGalleryCache
import com.goldsonhwy.yellowplayer.smb.SambaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single source of truth for all video data.
 */
class VideoRepository(private val context: Context) {

    private val scanner = VideoScanner(context)
    private val db = AppDatabase.getInstance(context)
    private val settings = SettingsDataStore(context)
    private val sambaClient = SambaClient()

    val favoriteDao = db.favoriteDao()
    val progressDao = db.playbackProgressDao()
    val sambaServerDao = db.sambaServerDao()

    // ─── Local Video Scanning ──────────────────────────────────

    suspend fun scanLocalVideos(onProgress: (Int) -> Unit = {}): List<VideoInfo> =
        withContext(Dispatchers.IO) {
            scanner.scanAllVideos(onProgress)
        }

    suspend fun scanLocalFolders(source: VideoSource = VideoSource.LOCAL): List<VideoFolder> =
        withContext(Dispatchers.IO) {
            LocalGalleryCache.folders?.let { return@withContext it }

            val savedRoots = context.getSharedPreferences("local_video_folders", Context.MODE_PRIVATE)
                .getStringSet("paths", emptySet())
                .orEmpty()

            val rootDirs = savedRoots
                .map { File(it) }
                .filter { it.exists() && it.isDirectory }

            val folderMap = scanner.scanVideoFolders(rootDirs, source)
            folderMap.map { (dir, videos) ->
                VideoFolder(
                    path = dir.absolutePath,
                    name = dir.name,
                    videoCount = videos.size,
                    thumbnailPath = videos.firstOrNull()?.path ?: "",
                    source = source
                )
            }.sortedBy { it.name }
        }

    suspend fun getVideosInLocalFolder(folderPath: String): List<VideoInfo> =
        withContext(Dispatchers.IO) {
            scanner.scanDirectoryIterative(File(folderPath), VideoSource.LOCAL)
        }

    fun getThumbnailUri(videoPath: String): Uri? {
        return scanner.getThumbnailUri(videoPath)
    }

    // ─── SMB ───────────────────────────────────────────────────

    suspend fun testSambaConnection(server: SambaServer): Result<Boolean> {
        return sambaClient.testConnection(server)
    }

    suspend fun listSambaFolders(server: SambaServer): Result<List<VideoFolder>> {
        return sambaClient.listFolders(server)
    }

    fun getSambaServers(): Flow<List<SambaServer>> {
        return sambaServerDao.getAllServers().map { entities ->
            entities.map { it.toModel() }
        }
    }

    suspend fun saveSambaServer(server: SambaServer): Long {
        val entity = SambaServerEntity(
            id = server.id,
            name = server.displayName.ifEmpty { server.name },
            host = server.host,
            port = server.port,
            shareName = server.shareName,
            username = server.username,
            password = server.password
        )
        return if (server.id == 0L) {
            sambaServerDao.insertServer(entity)
        } else {
            sambaServerDao.updateServer(entity)
            server.id
        }
    }

    suspend fun deleteSambaServer(server: SambaServer) {
        if (server.id != 0L) sambaServerDao.deleteServerById(server.id)
    }

    suspend fun getSambaServer(id: Long): SambaServer? = sambaServerDao.getServer(id)?.toModel()

    suspend fun listSambaFoldersById(serverId: Long): Result<List<VideoFolder>> {
        val server = getSambaServer(serverId) ?: return Result.failure(IllegalArgumentException("Samba server not found: $serverId"))
        return sambaClient.listFolders(server)
    }

    suspend fun listSambaFoldersById(serverId: Long, folderPath: String): Result<List<VideoFolder>> {
        val server = getSambaServer(serverId) ?: return Result.failure(IllegalArgumentException("Samba server not found: $serverId"))
        return sambaClient.listFolders(server, folderPath)
    }

    suspend fun listSambaVideosById(serverId: Long, folderPath: String): Result<List<VideoInfo>> {
        val server = getSambaServer(serverId) ?: return Result.failure(IllegalArgumentException("Samba server not found: $serverId"))
        return sambaClient.listVideos(server, folderPath)
    }

    suspend fun listSambaVideos(
        server: SambaServer,
        folderPath: String
    ): Result<List<VideoInfo>> {
        return sambaClient.listVideos(server, folderPath)
    }

    suspend fun cacheSambaThumbnail(
        server: SambaServer,
        remotePath: String,
        cacheDir: File
    ): Result<String> {
        return sambaClient.cacheForThumbnail(server, remotePath, cacheDir)
    }

    suspend fun cacheSambaVideoForLocalPlayback(serverId: Long, video: VideoInfo): VideoInfo {
        val server = getSambaServer(serverId) ?: return video
        val cacheDir = File(context.cacheDir, "smb_playback_cache").apply { mkdirs() }
        return sambaClient.cacheForThumbnail(server, video.path, cacheDir)
            .getOrNull()
            ?.let { localPath ->
                video.copy(
                    uri = Uri.fromFile(File(localPath)).toString(),
                    path = localPath,
                    source = VideoSource.LOCAL
                )
            } ?: video
    }

    suspend fun cacheSambaVideosForLocalUse(serverId: Long, videos: List<VideoInfo>): List<VideoInfo> = withContext(Dispatchers.IO) {
        videos.map { cacheSambaVideoForLocalPlayback(serverId, it) }
    }

    suspend fun prefetchSmbHeader(serverId: Long, remotePath: String): Int = withContext(Dispatchers.IO) {
        val server = getSambaServer(serverId) ?: return@withContext 0
        sambaClient.prefetchHeader(server, remotePath).getOrDefault(0)
    }

    suspend fun deleteSmbFolder(serverId: Long, remotePath: String): Boolean = withContext(Dispatchers.IO) {
        val server = getSambaServer(serverId) ?: return@withContext false
        sambaClient.deletePath(server, remotePath).getOrDefault(false)
    }

    suspend fun deleteSmbVideo(serverId: Long, remotePath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val server = getSambaServer(serverId)
            ?: return@withContext Result.failure(IllegalArgumentException("Samba server not found: $serverId"))
        sambaClient.deleteFile(server, remotePath)
    }

    // ─── Playback Progress ────────────────────────────────────

    suspend fun getPlaybackProgress(path: String): Long {
        return progressDao.getProgress(path)?.position ?: 0L
    }

    suspend fun savePlaybackProgress(path: String, position: Long, duration: Long) {
        progressDao.saveProgress(
            PlaybackProgressEntity(
                path = path,
                position = position,
                duration = duration
            )
        )
    }

    // ─── Favorites ────────────────────────────────────────────

    suspend fun isFavorite(path: String): Boolean {
        return favoriteDao.isFavorite(path) > 0
    }

    suspend fun getFavoriteVideos(): List<VideoInfo> = withContext(Dispatchers.IO) {
        favoriteDao.getAllFavoritesOnce().map { fav ->
            VideoInfo(
                name = fav.name,
                path = fav.currentPath,
                uri = Uri.fromFile(File(fav.currentPath)).toString(),
                folderPath = fav.currentFolderPath,
                source = fav.source,
                serverId = fav.serverId
            )
        }.filter { File(it.path).exists() }
    }

    suspend fun toggleFavorite(video: VideoInfo): VideoInfo? = withContext(Dispatchers.IO) {
        val existing = favoriteDao.getFavoriteByAnyPath(video.path)
        if (existing != null) {
            // Unfavorite: move back if this app moved it into the favorite directory.
            if (existing.movedToFavoriteDir) {
                runCatching {
                    val current = File(existing.currentPath)
                    val original = File(existing.originalPath)
                    original.parentFile?.mkdirs()
                    if (current.exists()) current.renameTo(original)
                }
            }
            favoriteDao.removeFavoriteByPath(video.path)
            null
        } else {
            // Mark favorite only. Do NOT move immediately; move after playback releases
            // the file when user switches away.
            favoriteDao.addFavorite(
                FavoriteEntity(
                    originalPath = video.path,
                    currentPath = video.path,
                    name = video.name,
                    originalFolderPath = video.folderPath,
                    currentFolderPath = video.folderPath,
                    source = video.source,
                    serverId = video.serverId,
                    movedToFavoriteDir = false
                )
            )
            video
        }
    }

    suspend fun moveFavoriteAfterRelease(path: String): VideoInfo? = withContext(Dispatchers.IO) {
        val fav = favoriteDao.getFavoriteByAnyPath(path) ?: return@withContext null
        if (fav.movedToFavoriteDir || fav.source != VideoSource.LOCAL) return@withContext null
        val favoriteRoot = context.getSharedPreferences("favorite_move", Context.MODE_PRIVATE)
            .getString("dir", "")
            .orEmpty()
        if (favoriteRoot.isBlank()) return@withContext null

        val current = File(fav.currentPath)
        if (!current.exists()) return@withContext null
        val savedRoots = context.getSharedPreferences("local_video_folders", Context.MODE_PRIVATE)
            .getStringSet("paths", emptySet())
            .orEmpty()
        val base = savedRoots.firstOrNull { fav.originalPath.startsWith(it.trimEnd('/') + "/") }
            ?: File(fav.originalPath).parentFile?.absolutePath.orEmpty()
        val rel = if (base.isNotBlank()) fav.originalPath.removePrefix(base).trimStart('/') else current.name
        val target = File(favoriteRoot, rel)
        target.parentFile?.mkdirs()
        if (!current.renameTo(target)) {
            // Cross-volume or scoped-storage rename may fail. Fall back to copy + delete,
            // and expose coarse progress for the Favorites page.
            val prefs = context.getSharedPreferences("favorite_move_progress", Context.MODE_PRIVATE)
            val key = fav.originalPath
            val total = current.length().coerceAtLeast(1L)
            var copied = 0L
            prefs.edit().putString(key, "0/${total}").apply()
            try {
                current.inputStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            prefs.edit().putString(key, "${copied}/${total}").apply()
                        }
                    }
                }
                if (target.exists() && target.length() == current.length()) {
                    current.delete()
                    prefs.edit().remove(key).apply()
                } else {
                    return@withContext null
                }
            } catch (_: Exception) {
                prefs.edit().remove(key).apply()
                return@withContext null
            }
        }

        val updated = fav.copy(
            currentPath = target.absolutePath,
            currentFolderPath = target.parentFile?.absolutePath.orEmpty(),
            movedToFavoriteDir = true
        )
        favoriteDao.updateFavorite(updated)
        VideoInfo(
            name = updated.name,
            path = updated.currentPath,
            uri = Uri.fromFile(File(updated.currentPath)).toString(),
            folderPath = updated.currentFolderPath,
            source = updated.source,
            serverId = updated.serverId
        )
    }

    // ─── Settings ─────────────────────────────────────────────

    fun getLongPressSpeed(): Flow<Float> = settings.longPressSpeed
    fun getVideoSort(): Flow<String> = settings.videoSort
    fun getThumbnailSize(): Flow<Int> = settings.thumbnailSize

    suspend fun setLongPressSpeed(speed: Float) = settings.setLongPressSpeed(speed)
    suspend fun setVideoSort(sort: String) = settings.setVideoSort(sort)
    suspend fun setThumbnailSize(size: Int) = settings.setThumbnailSize(size)

    private fun SambaServerEntity.toModel(): SambaServer = SambaServer(
        id = id,
        name = name,
        host = host,
        port = port,
        shareName = shareName,
        username = username,
        password = password,
        displayName = name
    )
}
