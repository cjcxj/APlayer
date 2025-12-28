package remix.myplayer.lyric.provider

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import remix.myplayer.data.model.audio.Song
import remix.myplayer.data.model.misc.LyricOrder
import remix.myplayer.lyric.LrcParser
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmbeddedProvider @Inject constructor(
  @ApplicationContext
  private val context: Context,
  private val smbFileCacheManager: remix.myplayer.util.SmbFileCacheManager
) : ILyricsProvider {

  override val id = LyricOrder.Embedded.toString()
  override val displayName = context.getString(LyricOrder.Embedded.stringRes)

  override suspend fun getLyrics(song: Song): LyricsResult {
    val filePath = when (song) {
      is Song.Local -> song.data
      is Song.Remote -> {
        // Support SMB files
        if (song.data.startsWith("smb://")) {
          // Wait for metadata fetch to complete (it caches the file)
          // This ensures we reuse the cached file if metadata is still being fetched
          Timber.d("EmbeddedProvider: waiting for cache or downloading SMB file: ${song.data}")
          val cachedFile = smbFileCacheManager.getCachedFile(song.data)
          if (cachedFile != null) {
            Timber.d("EmbeddedProvider: using cached file: ${cachedFile.absolutePath}")
            cachedFile.absolutePath
          } else {
            Timber.e("EmbeddedProvider: failed to download SMB file: ${song.data}")
            throw Exception("Failed to download SMB file for lyrics")
          }
        } else {
          throw Exception("Remote file lyrics only supported for SMB protocol")
        }
      }
    }
    
    val audioFile = AudioFileIO.read(File(filePath))

    // 先读标准的 FieldKey.LYRICS
    var lrc = audioFile.tag.getFirst(FieldKey.LYRICS)
    if (lrc.isNullOrEmpty()) {
      val uslt = audioFile.tag.getFirst("USLT")
      if (!uslt.isNullOrEmpty()) {
        lrc = uslt
      }
    }

    // 如果没有，再尝试扫描所有 TXXX
    if (lrc.isNullOrEmpty()) {
      val candidates = audioFile.tag.getFields("TXXX")
      for (f in candidates) {
        if (f is AbstractID3v2Frame) {
          val body = f.body
          if (body is FrameBodyTXXX) {
            val desc = body.description?.lowercase()?.trim()
            val text = body.text
            if ((desc != null &&
                  (desc.contains("lyric") || desc.contains("lrc") || desc.contains("歌词")))
              || text.contains(Regex("""\[(\d+:){1,2}\d+(\.\d*)?]"""))
            ) {
              lrc = text
              break
            }
          }
        }
      }
    }

    if (lrc.isNullOrEmpty()) {
      throw Exception("Field `LYRICS` doesn't exist or is empty")
    }
    return LyricsResult(LrcParser.parse(lrc), id)
  }
}
