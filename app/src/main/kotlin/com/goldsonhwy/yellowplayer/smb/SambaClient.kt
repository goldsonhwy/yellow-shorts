package com.goldsonhwy.yellowplayer.smb

import android.net.Uri
import com.goldsonhwy.yellowplayer.data.model.SambaServer
import com.goldsonhwy.yellowplayer.data.model.VideoFolder
import com.goldsonhwy.yellowplayer.data.model.VideoInfo
import com.goldsonhwy.yellowplayer.data.model.VideoSource
import jcifs.CIFSContext
import jcifs.CIFSException
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Properties

/**
 * SMB client wrapper for jcifs-ng.
 * Handles connection, directory listing, file discovery, and thumbnail caching.
 */
class SambaClient {

    private val cifsContext: ThreadLocal<CIFSContext?> = ThreadLocal()

    /**
     * Create an authenticated CIFS context for a given server.
     */
    private fun createContext(server: SambaServer): CIFSContext? {
        return try {
            val rawUser = server.username
            val auth = if (rawUser.contains("\\\\")) {
                val domain = rawUser.substringBefore("\\\\")
                val user = rawUser.substringAfter("\\\\")
                NtlmPasswordAuthenticator(domain, user, server.password)
            } else {
                // Empty username/password works for guest/anonymous shares.
                NtlmPasswordAuthenticator(null, rawUser, server.password)
            }
            val props = Properties().apply {
                setProperty("jcifs.smb.client.enableSMB2", "true")
                setProperty("jcifs.smb.client.disableSMB1", "true")
                setProperty("jcifs.smb.client.responseTimeout", "8000")
                setProperty("jcifs.smb.client.soTimeout", "8000")
            }
            val config = PropertyConfiguration(props)
            BaseContext(config).withCredentials(auth)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Build a proper SMB URL.
     */
    private fun buildSmbUrl(server: SambaServer, path: String = ""): String {
        val share = if (server.shareName.isNotEmpty()) "/${server.shareName}" else ""
        val dir = if (path.isNotEmpty() && !path.startsWith("/")) "/$path" else path
        return "smb://${server.host}:${server.port}$share$dir/"
    }

    /**
     * Test connection to a Samba server.
     */
    suspend fun testConnection(server: SambaServer): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val ctx = createContext(server)
            val url = buildSmbUrl(server)
            val file = SmbFile(url, ctx)
            Result.success(file.exists())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * List the immediate child folders of the current SMB directory.
     *
     * Folder browsing is intentionally shallow: callers can enter a folder
     * and request the next level. This avoids recursively probing every child
     * directory and allows arbitrarily deep shares to be browsed.
     */
    suspend fun listFolders(server: SambaServer, folderPath: String = ""): Result<List<VideoFolder>> =
        withContext(Dispatchers.IO) {
            try {
                val ctx = createContext(server)
                val url = if (folderPath.isNotBlank()) {
                    if (folderPath.endsWith("/")) folderPath else "$folderPath/"
                } else buildSmbUrl(server)
                val smbFile = SmbFile(url, ctx)
                val entries = smbFile.listFiles()

                val folders = entries
                    .filter { it.isDirectory }
                    .map { dir ->
                        VideoFolder(
                            path = dir.path,
                            name = dir.name,
                            // The count would require opening every child
                            // directory. It is unknown until that folder is opened.
                            videoCount = 0,
                            thumbnailPath = "",
                            source = VideoSource.SAMBA,
                            serverId = server.id
                        )
                    }
                    .sortedBy { it.name.lowercase() }

                Result.success(folders)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * List video files in a specific SMB path.
     */
    suspend fun listVideos(server: SambaServer, folderPath: String): Result<List<VideoInfo>> =
        withContext(Dispatchers.IO) {
            try {
                val ctx = createContext(server)
                val url = if (folderPath.endsWith("/")) folderPath else "$folderPath/"
                val smbFile = SmbFile(url, ctx)
                val entries = smbFile.listFiles()

                val videos = entries
                    .filter { !it.isDirectory && isVideoFile(it.name) }
                    .map { file ->
                        VideoInfo(
                            name = file.name,
                            path = file.path,
                            size = file.length(),
                            dateModified = file.lastModified(),
                            folderPath = file.parent,
                            source = VideoSource.SAMBA,
                            serverId = server.id
                        )
                    }

                Result.success(videos)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Stream a remote SMB file to local cache for thumbnail generation.
     * Returns the local cached file path.
     */
    suspend fun cacheForThumbnail(
        server: SambaServer,
        remotePath: String,
        cacheDir: File
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val ctx = createContext(server)
            val smbFile = SmbFile(remotePath, ctx)
            val cacheFile = File(cacheDir, "smb_thumb_${smbFile.name}")

            if (cacheFile.exists() && cacheFile.lastModified() > smbFile.lastModified()) {
                return@withContext Result.success(cacheFile.absolutePath)
            }

            smbFile.inputStream.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }

            Result.success(cacheFile.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Download SMB file to local cache for playback.
     */
    fun getInputStream(server: SambaServer, remotePath: String): Result<SmbFileInputStream> {
        return try {
            val ctx = createContext(server)
            val smbFile = SmbFile(remotePath, ctx)
            Result.success(smbFile.inputStream as SmbFileInputStream)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun prefetchHeader(server: SambaServer, remotePath: String, bytes: Int = 512 * 1024): Result<Int> {
        return try {
            val ctx = createContext(server) ?: return Result.failure(IllegalStateException("SMB context failed"))
            val smbFile = SmbFile(remotePath, ctx)
            var total = 0
            BufferedInputStream(smbFile.inputStream, 1024 * 1024).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (total < bytes) {
                    val read = input.read(buffer, 0, minOf(buffer.size, bytes - total))
                    if (read <= 0) break
                    total += read
                }
            }
            Result.success(total)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun deletePath(server: SambaServer, remotePath: String): Result<Boolean> {
        return try {
            val ctx = createContext(server) ?: return Result.failure(IllegalStateException("SMB context failed"))
            val file = SmbFile(if (remotePath.endsWith("/")) remotePath else "$remotePath/", ctx)
            file.delete()
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Delete one remote SMB file without converting its path to a directory. */
    fun deleteFile(server: SambaServer, remotePath: String): Result<Boolean> {
        return try {
            val ctx = createContext(server) ?: return Result.failure(IllegalStateException("SMB context failed"))
            SmbFile(remotePath.removeSuffix("/"), ctx).delete()
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun cleanup() {
        cifsContext.set(null)
    }

    companion object {
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "flv", "webm",
            "ts", "m4v", "3gp", "wmv", "mpeg", "mpg", "vob"
        )

        fun isVideoFile(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in VIDEO_EXTENSIONS
        }
    }

    // ─── LAN Auto-Discovery ─────────────────────────────────

    /**
     * Scan the local subnet for SMB servers on port 445.
     * Returns a list of discovered host IPs.
     */
    suspend fun discoverServers(onProgress: (Int, Int) -> Unit = { _, _ -> }): Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                val localIp = getLocalIpV4() ?: return@withContext Result.success(emptyList())
                val prefix = localIp.substringBeforeLast('.')
                val myLastOctet = localIp.substringAfterLast('.').toIntOrNull() ?: return@withContext Result.success(emptyList())

                val discovered = mutableListOf<String>()
                val candidates = (1..254).toList()

                // Scan in parallel batches of 20
                coroutineScope {
                    candidates.chunked(20).forEachIndexed { batchIndex, batch ->
                        val results = batch.map { octet ->
                            async {
                                if (octet == myLastOctet) return@async null
                                val host = "$prefix.$octet"
                                try {
                                    val sock = Socket()
                                    sock.connect(InetSocketAddress(host, 445), 500)
                                    sock.close()
                                    host
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }
                        results.forEach { deferred ->
                            val result = deferred.await()
                            if (result != null) {
                                discovered.add(result)
                            }
                        }
                        onProgress(batchIndex + 1, (candidates.size + 19) / 20)
                    }
                }

                Result.success(discovered.distinct())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Get the device's local IPv4 address on the WiFi/LAN interface.
     */
    private fun getLocalIpV4(): String? {
        try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()?.forEach { networkInterface ->
                if (networkInterface.isLoopback || !networkInterface.isUp) return@forEach
                networkInterface.inetAddresses?.asSequence()?.forEach { addr ->
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: return@forEach
                        // Skip common non-LAN ranges
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return null
    }
}
