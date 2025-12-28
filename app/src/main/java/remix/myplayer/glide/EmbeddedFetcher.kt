package remix.myplayer.glide

import android.media.MediaMetadataRetriever
import android.net.Uri
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.engine.GlideException
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * created by Remix on 2021/4/27
 */
class EmbeddedFetcher(private val fileUri: Uri) : DataFetcher<InputStream> {
  private var stream: InputStream? = null

  override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
    // Extract the actual file path from the embedded:// URI
    // embedded://smb://user:pass@host/path -> smb://user:pass@host/path
    // embedded:///local/path -> /local/path
    
    // Use schemeSpecificPart which includes everything after the scheme
    var path = fileUri.schemeSpecificPart ?: ""
    
    // Remove the leading "//" that Uri adds for hierarchical URIs
    if (path.startsWith("//")) {
      path = path.substring(2)
    }
    
    Timber.d("EmbeddedFetcher.loadData: original uri=$fileUri, extracted path=$path")
    
    // Handle SMB files and other remote files first
    if (path.startsWith("smb://") || path.startsWith("http://") || path.startsWith("https://")) {
      try {
        Timber.d("EmbeddedFetcher: loading remote file: $path")
        val stream = AudioFileCoverUtils.fallback(path)
        if (stream != null) {
          this.stream = stream
          Timber.d("EmbeddedFetcher: successfully loaded artwork for: $path")
          callback.onDataReady(stream)
        } else {
          Timber.e("EmbeddedFetcher: failed to load artwork for: $path")
          callback.onLoadFailed(GlideException("Failed to load album art for: $path"))
        }
      } catch (e: Exception) {
        Timber.e(e, "EmbeddedFetcher: exception loading remote file: $path")
        callback.onLoadFailed(GlideException(e.message, e))
      }
      return
    }
    
    // Handle local files with MediaMetadataRetriever
    val mediaDataRetriever = MediaMetadataRetriever()
    try {
      mediaDataRetriever.setDataSource(path)
      val bytes = mediaDataRetriever.embeddedPicture
      stream = if (bytes != null) {
        ByteArrayInputStream(bytes)
      } else {
        AudioFileCoverUtils.fallback(path)
      }
      callback.onDataReady(stream)
    } catch (e: Exception) {
      callback.onLoadFailed(GlideException(e.message, e))
    } finally {
      mediaDataRetriever.release()
    }
  }

  override fun cleanup() {
    try {
      stream?.close()
    } catch (ignore: IOException) {
    }
  }

  override fun cancel() {

  }

  override fun getDataClass(): Class<InputStream> {
    return InputStream::class.java
  }

  override fun getDataSource(): DataSource {
    return DataSource.LOCAL
  }
}