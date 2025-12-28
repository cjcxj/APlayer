package remix.myplayer.glide

import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.tag.TagException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SmbFileCacheManagerEntryPoint {
  fun smbFileCacheManager(): remix.myplayer.util.SmbFileCacheManager
}

object AudioFileCoverUtils {
  private val FALLBACKS = arrayOf("cover.jpg", "album.jpg", "folder.jpg", "cover.png", "album.png", "folder.png")

  @Throws(FileNotFoundException::class)
  fun fallback(path: String?): InputStream? {
    if (path == null) {
      return null
    }
    
    // Handle SMB files
    if (path.startsWith("smb://")) {
      return try {
        // Use Hilt EntryPoint to get SmbFileCacheManager
        val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
          remix.myplayer.App.context,
          SmbFileCacheManagerEntryPoint::class.java
        )
        val cacheManager = entryPoint.smbFileCacheManager()
        
        kotlinx.coroutines.runBlocking {
          val cachedFile = cacheManager.getCachedFile(path)
          if (cachedFile != null) {
            fallback(cachedFile.absolutePath)
          } else {
            null
          }
        }
      } catch (e: Exception) {
        timber.log.Timber.e(e, "Failed to get album art from SMB file: $path")
        null
      }
    }
    
    // Method 1: use embedded high resolution album art if there is any
    try {
      val audioFile = org.jaudiotagger.audio.AudioFileIO.read(File(path))
      val tag = audioFile.tag
      if (tag != null) {
        val art = tag.firstArtwork
        if (art != null) {
          val imageData = art.binaryData
          if (imageData != null && imageData.isNotEmpty()) {
            return ByteArrayInputStream(imageData)
          }
        }
      }
      // If there are any exceptions, we ignore them and continue to the other fallback method
    } catch (ignored: Exception) {
    }

    // Method 2: look for album art in external files
    val parent = File(path).parentFile
    for (fallback in FALLBACKS) {
      val cover = File(parent, fallback)
      if (cover.exists()) {
        return FileInputStream(cover)
      }
    }
    return null
  }
}