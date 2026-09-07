package com.goldsonhwy.yellowplayer.ui.screens.player

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goldsonhwy.yellowplayer.data.model.VideoInfo
import com.goldsonhwy.yellowplayer.data.model.VideoSource
import com.goldsonhwy.yellowplayer.data.repository.VideoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull


private const val TAG = "PlayerViewModel"

data class PlayerUiState(
    val videos: List<VideoInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = VideoRepository(application)
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    fun loadVideos(source: VideoSource, folderPath: String) {
        viewModelScope.launch {
            _uiState.value = PlayerUiState(isLoading = true)
            try {
                when {
                    folderPath == "__favorites__" -> {
                        _uiState.value = PlayerUiState(videos = repository.getFavoriteVideos())
                    }
                    source == VideoSource.LOCAL || source == VideoSource.SAMBA -> {
                        val videos = withTimeoutOrNull(60_000L) {
                            withContext(Dispatchers.IO) {
                                val rawVideos = if (source == VideoSource.SAMBA) {
                                    val serverId = folderPath.substringBefore('|').toLongOrNull() ?: 0L
                                    val realFolderPath = folderPath.substringAfter('|', folderPath)
                                    repository.listSambaVideosById(serverId, realFolderPath).getOrElse { emptyList() }
                                } else {
                                    repository.getVideosInLocalFolder(folderPath)
                                }
                                sortVideosForDirectory(source, folderPath, rawVideos)
                            }
                        }
                        _uiState.value = if (videos == null) {
                            PlayerUiState(error = "读取视频列表超时：$folderPath")
                        } else {
                            PlayerUiState(videos = videos)
                        }
                    }
                    else -> _uiState.value = PlayerUiState(error = "播放器暂只接入本地视频，Samba 播放下一版继续接入。")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "loadVideos failed", t)
                _uiState.value = PlayerUiState(error = "加载视频失败：${t.javaClass.simpleName}\n${t.message.orEmpty()}")
            }
        }
    }

    private fun sortVideosForDirectory(source: VideoSource, folderPath: String, videos: List<VideoInfo>): List<VideoInfo> {
        val app = getApplication<Application>()
        val (serverId, realFolderPath) = if (source == VideoSource.SAMBA) {
            (folderPath.substringBefore('|').toLongOrNull() ?: 0L) to folderPath.substringAfter('|', folderPath)
        } else {
            0L to folderPath
        }
        val sortKey = "${source.name}_${serverId}_${realFolderPath.hashCode()}"
        val sortMode = app.getSharedPreferences("directory_sort", Context.MODE_PRIVATE)
            .getString(sortKey, "name") ?: "name"
        return when (sortMode) {
            "date" -> videos.sortedByDescending { it.dateModified }
            "size" -> videos.sortedByDescending { it.size }
            "name_desc" -> videos.sortedByDescending { it.name }
            else -> videos.sortedBy { it.name }
        }
    }

    suspend fun isFavorite(path: String): Boolean = withContext(Dispatchers.IO) {
        repository.isFavorite(path)
    }

    suspend fun toggleFavorite(video: VideoInfo): VideoInfo? = withContext(Dispatchers.IO) {
        repository.toggleFavorite(video)
    }

    suspend fun moveFavoriteAfterRelease(path: String): VideoInfo? = withContext(Dispatchers.IO) {
        repository.moveFavoriteAfterRelease(path)
    }

    fun moveFavoriteAfterReleaseAsync(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.moveFavoriteAfterRelease(path)
        }
    }

    fun prefetchSmbHeaderAsync(serverId: Long, remotePath: String) {
        if (serverId <= 0 || remotePath.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.prefetchSmbHeader(serverId, remotePath)
        }
    }

    suspend fun deleteSmbVideo(video: VideoInfo): Result<Boolean> = withContext(Dispatchers.IO) {
        if (video.source != VideoSource.SAMBA || video.serverId <= 0) {
            return@withContext Result.failure(IllegalArgumentException("Not an SMB video"))
        }
        repository.deleteSmbVideo(video.serverId, video.path)
    }

    suspend fun getVideoRotation(path: String): Int = withContext(Dispatchers.IO) {
        val prefs = getApplication<Application>().getSharedPreferences("video_rotation", Context.MODE_PRIVATE)
        prefs.getInt(path, 0).floorMod360()
    }

    fun saveVideoRotation(path: String, rotation: Int) {
        if (path.isBlank()) return
        val normalized = rotation.floorMod360()
        getApplication<Application>().getSharedPreferences("video_rotation", Context.MODE_PRIVATE)
            .edit().putInt(path, normalized).apply()
    }

    fun finalizeVideoAfterSwitchAsync(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.moveFavoriteAfterRelease(path)
        }
    }
}

private fun Int.floorMod360(): Int = ((this % 360) + 360) % 360
