package remix.myplayer.util

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages caching of SMB files for metadata extraction
 */
@Singleton
class SmbFileCacheManager @Inject constructor(
  @ApplicationContext private val context: Context
) {

  private val cacheDir: File
    get() = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }

  // Use Mutex to prevent concurrent downloads of the same file
  private val downloadLocks = mutableMapOf<String, Mutex>()
  private val locksMutex = Mutex()

  /**
   * Download SMB file to cache if not already cached
   * @param smbUrl SMB URL in format: smb://[domain;]username:password@host/share/path/file.mp3
   * @return Cached file or null if download fails
   */
  suspend fun getCachedFile(smbUrl: String): File? {
    val cacheFile = File(cacheDir, getCacheFileName(smbUrl))
    
    // Return cached file if exists and is valid (has non-zero size)
    if (cacheFile.exists()) {
      val fileSize = cacheFile.length()
      if (fileSize > 0) {
        Timber.d("SMB file already cached: $smbUrl, size=$fileSize bytes")
        return cacheFile
      } else {
        // Empty file, delete it and re-download
        Timber.w("SMB file is empty, deleting and re-downloading: $smbUrl")
        cacheFile.delete()
      }
    }

    // Get or create a mutex for this URL to prevent concurrent downloads
    val mutex = locksMutex.withLock {
      downloadLocks.getOrPut(smbUrl) { Mutex() }
    }

    // Use the mutex to ensure only one download happens per URL
    return mutex.withLock {
      // Double-check: file might have been downloaded by another thread while waiting for lock
      if (cacheFile.exists() && cacheFile.length() > 0) {
        Timber.d("SMB file was downloaded by another thread: $smbUrl")
        return@withLock cacheFile
      }

      // Delete empty file if exists
      if (cacheFile.exists() && cacheFile.length() == 0L) {
        cacheFile.delete()
      }

      // Download file
      return@withLock try {
        downloadSmbFile(smbUrl, cacheFile)
        cacheFile
      } catch (e: Exception) {
        Timber.e(e, "Failed to download SMB file: $smbUrl")
        cacheFile.delete() // Clean up partial download
        null
      } finally {
        // Clean up the lock when done
        locksMutex.withLock {
          downloadLocks.remove(smbUrl)
        }
      }
    }
  }

  /**
   * Download SMB file to specified destination
   */
  private fun downloadSmbFile(smbUrl: String, destFile: File) {
    // Manual parsing to handle spaces and non-ASCII characters
    // Format: smb://[domain;]username:password@host/share/path/file.ext
    
    if (!smbUrl.startsWith("smb://")) {
      throw IllegalArgumentException("Invalid SMB URL: must start with smb://")
    }
    
    val urlWithoutProtocol = smbUrl.substring(6) // Remove "smb://"
    
    // Split by @ to separate auth from host/path
    val atIndex = urlWithoutProtocol.indexOf('@')
    if (atIndex == -1) {
      throw IllegalArgumentException("Invalid SMB URL: missing credentials")
    }
    
    val authPart = urlWithoutProtocol.substring(0, atIndex)
    val hostAndPath = urlWithoutProtocol.substring(atIndex + 1)
    
    // Parse authentication: [domain;]username:password
    var username = ""
    var password = ""
    var domain: String? = null
    
    val colonIndex = authPart.indexOf(':')
    if (colonIndex != -1) {
      val userPart = authPart.substring(0, colonIndex)
      password = authPart.substring(colonIndex + 1)
      
      val semicolonIndex = userPart.indexOf(';')
      if (semicolonIndex != -1) {
        domain = userPart.substring(0, semicolonIndex)
        username = userPart.substring(semicolonIndex + 1)
      } else {
        username = userPart
      }
    } else {
      username = authPart
    }
    
    // Parse host and path
    val slashIndex = hostAndPath.indexOf('/')
    val host = if (slashIndex != -1) {
      hostAndPath.substring(0, slashIndex)
    } else {
      hostAndPath
    }
    
    val pathPart = if (slashIndex != -1) {
      hostAndPath.substring(slashIndex + 1)
    } else {
      ""
    }
    
    if (pathPart.isEmpty()) {
      throw IllegalArgumentException("Invalid SMB URL: no path")
    }
    
    // Split path into share and file path
    val pathSegments = pathPart.split('/').filter { it.isNotEmpty() }
    if (pathSegments.isEmpty()) {
      throw IllegalArgumentException("Invalid SMB URL: no path segments")
    }
    
    val shareName = pathSegments[0]
    val filePath = pathSegments.drop(1).joinToString("\\")

    // Configure SMB client
    val config = SmbConfig.builder()
      .withMultiProtocolNegotiate(true)
      .withSigningRequired(false)
      .withDfsEnabled(false)
      .withTimeout(120, TimeUnit.SECONDS)
      .withSoTimeout(120, TimeUnit.SECONDS)
      .build()

    val client = SMBClient(config)
    try {
      val connection = client.connect(host)
      try {
        val authContext = AuthenticationContext(username, password.toCharArray(), domain)
        val session = connection.authenticate(authContext)
        try {
          val diskShare = session.connectShare(shareName) as DiskShare
          try {
            // Open file for reading
            val accessMask = setOf(AccessMask.GENERIC_READ)
            val shareMode = setOf(
              SMB2ShareAccess.FILE_SHARE_READ,
              SMB2ShareAccess.FILE_SHARE_WRITE,
              SMB2ShareAccess.FILE_SHARE_DELETE
            )

            val file = diskShare.openFile(
              filePath,
              accessMask,
              null,
              shareMode,
              SMB2CreateDisposition.FILE_OPEN,
              null
            )

            try {
              // Read file content
              val inputStream = file.inputStream
              val outputStream = FileOutputStream(destFile)

              inputStream.use { input ->
                outputStream.use { output ->
                  val buffer = ByteArray(64 * 1024) // 64KB buffer
                  var bytesRead: Int
                  var totalBytes = 0L
                  while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                  }
                  output.flush()
                }
              }

              val downloadedSize = destFile.length()
              Timber.d("Downloaded SMB file: $smbUrl -> ${destFile.absolutePath}, size=$downloadedSize bytes")
            } finally {
              file.close()
            }
          } finally {
            diskShare.close()
          }
        } finally {
          session.close()
        }
      } finally {
        connection.close()
      }
    } finally {
      client.close()
    }
  }

  /**
   * Generate cache filename from SMB URL
   */
  private fun getCacheFileName(smbUrl: String): String {
    val hash = MessageDigest.getInstance("MD5")
      .digest(smbUrl.toByteArray())
      .joinToString("") { "%02x".format(it) }
    
    // Extract original filename for easier debugging
    val originalName = smbUrl.substringAfterLast("/")
    val extension = if (originalName.contains(".")) {
      originalName.substringAfterLast(".")
    } else {
      "tmp"
    }
    
    return "$hash.$extension"
  }

  /**
   * Clear all cached SMB files
   */
  fun clearCache() {
    try {
      cacheDir.deleteRecursively()
      Timber.d("Cleared SMB file cache")
    } catch (e: Exception) {
      Timber.e(e, "Failed to clear SMB file cache")
    }
  }

  /**
   * Clear cached files older than specified age
   * @param maxAgeMs Maximum age in milliseconds
   */
  fun clearOldCache(maxAgeMs: Long) {
    try {
      val now = System.currentTimeMillis()
      cacheDir.listFiles()?.forEach { file ->
        if (now - file.lastModified() > maxAgeMs) {
          file.delete()
          Timber.d("Deleted old cached file: ${file.name}")
        }
      }
    } catch (e: Exception) {
      Timber.e(e, "Failed to clear old SMB file cache")
    }
  }

  /**
   * Get total cache size in bytes
   */
  fun getCacheSize(): Long {
    return try {
      cacheDir.walkTopDown()
        .filter { it.isFile }
        .map { it.length() }
        .sum()
    } catch (e: Exception) {
      0L
    }
  }

  companion object {
    private const val CACHE_DIR_NAME = "smb_audio_cache"
  }
}
