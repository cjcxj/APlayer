package remix.myplayer.util

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.RecoverableSecurityException
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaFormat
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Parcelable
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dagger.hilt.android.EntryPointAccessors
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import remix.myplayer.App
import remix.myplayer.App.Companion.context
import remix.myplayer.R
import remix.myplayer.data.model.audio.Song
import remix.myplayer.misc.floatpermission.rom.RomUtils
import remix.myplayer.misc.manager.APlayerActivityManager
import remix.myplayer.ui.activity.base.BaseActivity
import remix.myplayer.ui.activity.base.PendingWriteRequest
import remix.myplayer.ui.activity.base.PendingSyncRequest
import remix.myplayer.data.db.room.entity.MetaDataCache
import remix.myplayer.di.DaoEntryPoint
import remix.myplayer.ui.nav.MessageNotifier
import timber.log.Timber
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.EnumMap

/**
 * Created by Remix on 2015/11/30.
 */
/**
 * 通用工具类
 */
object Util {

  /**
   * 注册本地Receiver
   */
  fun registerLocalReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?) {
    LocalBroadcastManager.getInstance(context).registerReceiver(receiver!!, filter!!)
  }

  /**
   * 注销本地Receiver
   */
  fun unregisterLocalReceiver(receiver: BroadcastReceiver?) {
    LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver!!)
  }

  @JvmStatic
  fun sendLocalBroadcast(intent: Intent?) {
    LocalBroadcastManager.getInstance(context).sendBroadcast(intent!!)
  }

  fun sendCMDLocalBroadcast(cmd: Int) {
    LocalBroadcastManager.getInstance(context).sendBroadcast(MusicUtil.makeCmdIntent(cmd))
  }

  /**
   * 注销Receiver
   */
  fun unregisterReceiver(context: Context?, receiver: BroadcastReceiver?) {
    try {
      context?.unregisterReceiver(receiver)
    } catch (e: Exception) {
    }
  }

  /**
   * 判断app是否运行在前台
   */
  val isAppOnForeground: Boolean
    get() {
      try {
        val activityManager = context
          .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager? ?: return false
        val packageName = context.packageName
        val appProcesses: MutableList<RunningAppProcessInfo> = activityManager.runningAppProcesses
          ?: return false
        for (appProcess in appProcesses) {
          if (appProcess.processName == packageName &&
            appProcess.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND
          ) {
            return true
          }
        }
      } catch (e: Exception) {
        Timber.w("isAppOnForeground(), ex: %s", e.message)
        return APlayerActivityManager.isAppForeground
      }
      return false
    }

  /**
   * 震动
   */
  fun vibrate(context: Context?, milliseconds: Long) {
    if (context == null) {
      return
    }
    try {
      val vibrator = context.getSystemService(Service.VIBRATOR_SERVICE) as Vibrator
      vibrator.vibrate(milliseconds)
    } catch (ignore: Exception) {
    }
  }

  /**
   * 获得目录大小
   */
  fun getFolderSize(file: File?): Long {
    var size: Long = 0
    try {
      val fileList = file?.listFiles() ?: return size
      for (i in fileList.indices) {
        // 如果下面还有文件
        size = if (fileList[i].isDirectory) {
          size + getFolderSize(fileList[i])
        } else {
          size + fileList[i].length()
        }
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
    return size
  }

  /**
   * 删除某个目录
   */
  fun deleteFilesByDirectory(directory: File?) {
    if (directory == null) {
      return
    }
    if (directory.isFile) {
      deleteFileSafely(directory)
      return
    }
    if (directory.isDirectory) {
      val childFile = directory.listFiles()
      if (childFile == null || childFile.isEmpty()) {
        deleteFileSafely(directory)
        return
      }
      for (f in childFile) {
        deleteFilesByDirectory(f)
      }
      deleteFileSafely(directory)
    }
  }

  /**
   * 安全删除文件 小米、华为等手机极有可能在删除一个文件后再创建同名文件出现bug
   */
  fun deleteFileSafely(file: File?): Boolean {
    if (file != null) {
      val tmpPath = (file.parent ?: return false) + File.separator + System.currentTimeMillis()
      val tmp = File(tmpPath)
      return file.renameTo(tmp) && tmp.delete()
    }
    return false
  }

  /**
   * 防止修改字体大小
   */
  fun setFontSize(Application: App) {
    val resource = Application.resources
    val c = resource.configuration
    c.fontScale = 1.0f
    resource.updateConfiguration(c, resource.displayMetrics)
  }

  /**
   * 获得歌曲格式
   */
  fun getType(mimeType: String): String {
    return when {
      mimeType == MediaFormat.MIMETYPE_AUDIO_MPEG -> {
        "mp3"
      }

      mimeType == MediaFormat.MIMETYPE_AUDIO_FLAC -> {
        "flac"
      }

      mimeType == MediaFormat.MIMETYPE_AUDIO_AAC -> {
        "aac"
      }

      mimeType.contains("ape") -> {
        "ape"
      }

      else -> {
        try {
          if (mimeType.contains("audio/")) {
            mimeType.substring(6, mimeType.length - 1)
          } else {
            mimeType
          }
        } catch (e: Exception) {
          mimeType
        }
      }
    }
  }

  /**
   * 转换时间
   *
   * @return 00:00格式的时间
   */
  fun getTime(duration: Long): String {
    val minute = duration.toInt() / 1000 / 60
    val second = (duration / 1000).toInt() % 60
    //如果分钟数小于10
    return if (minute < 10) {
      if (second < 10) {
        "0$minute:0$second"
      } else {
        "0$minute:$second"
      }
    } else {
      if (second < 10) {
        "$minute:0$second"
      } else {
        "$minute:$second"
      }
    }
  }

  /**
   * 检测 响应某个意图的Activity 是否存在
   */
  fun isIntentAvailable(context: Context, intent: Intent?): Boolean {
    val packageManager = context.packageManager
    val list = packageManager.queryIntentActivities(
      intent!!,
      PackageManager.MATCH_DEFAULT_ONLY
    )
    return list != null && list.size > 0
  }

  /**
   * 启动 Activity，失败时 toast
   */
  fun startActivitySafely(context: Context, intent: Intent) {
    try {
      context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
      MessageNotifier.show(R.string.activity_not_found_tip)
    }
  }

  fun startActivityForResultSafely(
    activity: Activity,
    intent: Intent,
    requestCode: Int
  ) {
    try {
      activity.startActivityForResult(intent, requestCode)
    } catch (e: ActivityNotFoundException) {
      MessageNotifier.show(R.string.activity_not_found_tip)
    }
  }

  /**
   * 判断网路是否连接
   */
  val isNetWorkConnected: Boolean
    get() {
      val connectivityManager = context
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
      if (connectivityManager != null) {
        val netWorkInfo = connectivityManager.activeNetworkInfo
        if (netWorkInfo != null) {
          return netWorkInfo.isAvailable && netWorkInfo.isConnected
        }
      }
      return false
    }

  /**
   * 删除歌曲
   *
   * @param path 歌曲路径
   * @return 是否删除成功
   */
  fun deleteFile(path: String?): Boolean {
    val file = File(path ?: return false)
    return file.exists() && file.delete()
  }

  /**
   * 处理歌曲名、歌手名或者专辑名
   *
   * @param origin 原始数据
   * @param type 处理类型 0:歌曲名 1:歌手名 2:专辑名 3:文件名
   * @return
   */
  const val TYPE_SONG = 0
  const val TYPE_ARTIST = 1
  const val TYPE_ALBUM = 2
  const val TYPE_DISPLAYNAME = 3
  fun processInfo(origin: String?, type: Int): String {
    return if (type == TYPE_SONG) {
      if (origin == null || origin == "") {
        context.getString(R.string.unknown_song)
      } else {
//                return origin.lastIndexOf(".") > 0 ? origin.substring(0, origin.lastIndexOf(".")) : origin;
        origin
      }
    } else if (type == TYPE_DISPLAYNAME) {
      if (origin == null || origin == "") {
        context.getString(R.string.unknown_song)
      } else {
        if (origin.lastIndexOf(".") > 0) origin.substring(0, origin.lastIndexOf(".")) else origin
      }
    } else {
      if (origin == null || origin == "") {
        context
          .getString(if (type == TYPE_ARTIST) R.string.unknown_artist else R.string.unknown_album)
      } else {
        origin
      }
    }
  }

  /**
   * 判断是否连续点击
   *
   * @return
   */
  private var mLastClickTime: Long = 0
  private const val INTERVAL = 500
  val isFastDoubleClick: Boolean
    get() {
      val time = System.currentTimeMillis()
      val timeInterval = time - mLastClickTime
      if (timeInterval in 1 until INTERVAL) {
        return true
      }
      mLastClickTime = time
      return false
    }

  /**
   * 返回关键词的MD值
   */
  @JvmStatic
  fun hashKeyForDisk(key: String): String {
    val cacheKey: String = try {
      val mDigest = MessageDigest.getInstance("MD5")
      mDigest.update(key.toByteArray())
      bytesToHexString(mDigest.digest())
    } catch (e: NoSuchAlgorithmException) {
      key.hashCode().toString()
    }
    return cacheKey
  }

  private fun bytesToHexString(bytes: ByteArray): String {
    val sb = StringBuilder()
    for (i in bytes.indices) {
      val hex = Integer.toHexString(0xFF and bytes[i].toInt())
      if (hex.length == 1) {
        sb.append('0')
      }
      sb.append(hex)
    }
    return sb.toString()
  }

  /**
   * 浏览器打开指定地址
   */
  fun openUrl(url: String?) {
    if (TextUtils.isEmpty(url)) {
      return
    }
    val uri = Uri.parse(url)
    val it = Intent(Intent.ACTION_VIEW, uri)
    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(it)
  }

  /**
   * 判断wifi是否打开
   */
  fun isWifi(context: Context): Boolean {
    val activeNetInfo = (context
      .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo
    return activeNetInfo != null && activeNetInfo.type == ConnectivityManager.TYPE_WIFI
  }

  /**
   * 获取app当前的渠道号或application中指定的meta-data
   *
   * @return 如果没有获取成功(没有对应值 ， 或者异常)，则返回值为空
   */
  fun getAppMetaData(key: String?): String? {
    if (TextUtils.isEmpty(key)) {
      return null
    }
    var channelNumber: String? = null
    try {
      val packageManager = context.packageManager
      if (packageManager != null) {
        val applicationInfo =
          packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        if (applicationInfo != null) {
          if (applicationInfo.metaData != null) {
            channelNumber = applicationInfo.metaData.getString(key)
          }
        }
      }
    } catch (e: PackageManager.NameNotFoundException) {
      e.printStackTrace()
    }
    return channelNumber
  }

  fun createShareSongFileIntent(song: Song, context: Context): Intent {
    return try {
      val parcelable: Parcelable = FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        File(song.data)
      )
      Intent()
        .setAction(Intent.ACTION_SEND)
        .putExtra(
          Intent.EXTRA_STREAM,
          parcelable
        )
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .setType("audio/*")
    } catch (e: IllegalArgumentException) {
      //the path is most likely not like /storage/emulated/0/... but something like /storage/28C7-75B0/...
      e.printStackTrace()
      Toast.makeText(context, context.getString(R.string.cant_share_song), Toast.LENGTH_SHORT)
        .show()
      Intent()
    }
  }

  fun createShareImageFileIntent(file: File, context: Context): Intent {
    return try {
      val parcelable: Parcelable = FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        file
      )
      Intent()
        .setAction(Intent.ACTION_SEND)
        .putExtra(
          Intent.EXTRA_STREAM,
          parcelable
        )
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .setType("image/*")
    } catch (e: IllegalArgumentException) {
      e.printStackTrace()
      Toast.makeText(context, context.getString(R.string.cant_share_song), Toast.LENGTH_SHORT)
        .show()
      Intent()
    }
  }

  @JvmStatic
  fun closeSafely(closeable: Closeable?) {
    if (closeable != null) {
      if (closeable is Cursor && closeable.isClosed) {
        return
      }
      try {
        closeable.close()
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
  }

  fun installApk(context: Context, path: String) {
    val apkFile = File(path)
    val apkUri = ("file://${apkFile.absolutePath}").toUri()
    val intent: Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      val apkUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
      )
      Intent(Intent.ACTION_INSTALL_PACKAGE).setData(apkUri)
        .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } else {
      Intent(Intent.ACTION_VIEW).setDataAndType(
        apkUri,
        "application/vnd.android.package-archive"
      )
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
  }

  /**
   * 获取进程号对应的进程名
   *
   * @param pid 进程号
   * @return 进程名
   */
  fun getProcessName(pid: Int): String? {
    var reader: BufferedReader? = null
    try {
      reader = BufferedReader(FileReader("/proc/$pid/cmdline"))
      var processName = reader.readLine()
      if (!TextUtils.isEmpty(processName)) {
        processName = processName.trim { it <= ' ' }
      }
      return processName
    } catch (throwable: Throwable) {
      throwable.printStackTrace()
    } finally {
      try {
        reader?.close()
      } catch (exception: IOException) {
        exception.printStackTrace()
      }
    }
    return null
  }

  /**
   * 判断是否支持状态栏歌词
   */
  fun isSupportStatusBarLyric(context: Context): Boolean {
    return RomUtils.checkIsMeizuRom() || Settings.System.getInt(
      context.contentResolver,
      "status_bar_show_lyric",
      0
    ) != 0 || RomUtils.checkIsbaolong24Rom() || RomUtils.checkIsexTHmUIRom()
  }

  /**
   * HTML 转纯文本
   *
   * 用于处理 QQ 歌词中的“&apos;”等
   */
  fun htmlToText(source: String?): String {
    return HtmlCompat.fromHtml(source!!, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
  }

  fun hideKeyboard(view: View?): Boolean {
    if (view == null) {
      return false
    }
    try {
      val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
      return if (!imm.isActive) {
        false
      } else imm.hideSoftInputFromWindow(view.windowToken, 0)
    } catch (e: Exception) {
      e.printStackTrace()
    }
    return false
  }

  suspend fun saveToAlbum(context: Context, resId: Int, fileName: String) {
    val bitmap = BitmapFactory.decodeResource(context.resources, resId)
    val file =
      File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), fileName)

    withContext(Dispatchers.IO) {
      if (file.exists()) {
        file.delete()
      }

      file.createNewFile()
    }

    val values = ContentValues()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      values.put(
        MediaStore.MediaColumns.RELATIVE_PATH,
        Environment.DIRECTORY_PICTURES
      )
    } else {
      values.put(MediaStore.MediaColumns.DATA, file.absolutePath)
    }
    values.put(MediaStore.Images.ImageColumns.TITLE, fileName)
    values.put(MediaStore.Images.ImageColumns.DISPLAY_NAME, fileName)
    values.put(MediaStore.Images.ImageColumns.MIME_TYPE, "image/png")
    values.put(MediaStore.Images.ImageColumns.WIDTH, bitmap.width)
    values.put(MediaStore.Images.ImageColumns.HEIGHT, bitmap.height)

    val insertUri =
      context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return

    context.contentResolver.openOutputStream(insertUri)?.use {
      ByteArrayOutputStream().use { bos ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, bos)
        it.write(bos.toByteArray())
      }
    }

    Timber.v("insertUri: $insertUri")
    MessageNotifier.show(R.string.save_success)
  }

  fun requestSaveAudioTag(
    activity: BaseActivity, song: Song,
    newTitle: String, newAlbum: String, newArtist: String,
    newGenre: String, newYear: String, newTrackNum: String
  ) {
    val fieldMap = EnumMap<FieldKey, String>(FieldKey::class.java)

    fieldMap[FieldKey.TITLE] = newTitle
    fieldMap[FieldKey.ALBUM] = newAlbum
    fieldMap[FieldKey.ARTIST] = newArtist
    fieldMap[FieldKey.GENRE] = newGenre
    fieldMap[FieldKey.YEAR] = newYear
    fieldMap[FieldKey.TRACK] = newTrackNum

    val request = PendingWriteRequest(song.data, fieldMap)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      activity.pendingWriteRequest = request
      activity.writeSongLauncher.launch(
        IntentSenderRequest.Builder(
          MediaStore.createWriteRequest(
            context.contentResolver,
            listOf(song.contentUri)
          ).intentSender
        ).build()
      )
    } else {
      // TODO test
      activity.lifecycleScope.launch {
        try {
          saveAudioTag(activity, request)
        } catch (e: Exception) {
          try {
            val songFD =
              activity.contentResolver.openFileDescriptor(
                song.contentUri,
                "w"
              )!! // test if we can write
            songFD.close()
          } catch (securityException: SecurityException) {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && securityException is RecoverableSecurityException) {
              activity.pendingWriteRequest = request
              activity.writeSongLauncher.launch(
                IntentSenderRequest.Builder(
                  securityException.userAction.actionIntent.intentSender,
                ).build()
              )
              return@launch
            }

            throw securityException
          }

          Timber.v("Fail to save tag: $e")
          MessageNotifier.show(R.string.save_error_arg)
        }
      }
    }
  }

  suspend fun saveAudioTag(context: Context, request: PendingWriteRequest) =
    withContext(Dispatchers.IO) {
      val audioFile = AudioFileIO.read(File(request.path))

      val tag = audioFile.tagOrCreateAndSetDefault
      for ((key, value) in request.fieldMap) {
        try {
          tag.setField(key, value)
        } catch (e: Exception) {
          Timber.v("setField($key, $value) failed: $e")
        }
      }

      audioFile.commit()

      // 更新持久化缓存
      try {
        val dao = EntryPointAccessors.fromApplication(context.applicationContext, DaoEntryPoint::class.java).metaDataCacheDao()
        val existing = dao.get(request.path)
        dao.insert(MetaDataCache(
          url = request.path,
          title = request.fieldMap[FieldKey.TITLE] ?: existing?.title ?: "",
          artist = request.fieldMap[FieldKey.ARTIST] ?: existing?.artist ?: "",
          album = request.fieldMap[FieldKey.ALBUM] ?: existing?.album ?: "",
          duration = existing?.duration ?: 0L,
          fileSize = existing?.fileSize ?: File(request.path).length(),
          lastModified = existing?.lastModified ?: File(request.path).lastModified(),
          year = request.fieldMap[FieldKey.YEAR] ?: existing?.year ?: "",
          genre = request.fieldMap[FieldKey.GENRE] ?: existing?.genre ?: "",
          track = request.fieldMap[FieldKey.TRACK] ?: existing?.track ?: ""
        ))
      } catch (e: Exception) {
        Timber.w("Fail to updated MetaDataCache in saveAudioTag: $e")
      }

      MediaScannerConnection.scanFile(
        context,
        arrayOf(request.path), null
      ) { _, uri ->
//        context.contentResolver.notifyChange(Audio.Media.EXTERNAL_CONTENT_URI, null)
        context.contentResolver.notifyChange(uri, null)
      }

      withContext(Dispatchers.Main) {
        MessageNotifier.show(R.string.save_success)
      }
    }

  suspend fun syncMediaStoreTags(context: Context, song: Song, tag: org.jaudiotagger.tag.Tag) =
    syncMediaStoreTags(
      context,
      song,
      tag.getFirst(FieldKey.TITLE),
      tag.getFirst(FieldKey.ARTIST),
      tag.getFirst(FieldKey.ALBUM)
    )

  suspend fun syncMediaStoreTags(
    context: Context,
    song: Song,
    title: String,
    artist: String,
    album: String
  ) =
    withContext(Dispatchers.IO) {
      val values = ContentValues()

      if (title.isNotEmpty()) values.put(MediaStore.Audio.Media.TITLE, title)
      if (artist.isNotEmpty()) values.put(MediaStore.Audio.Media.ARTIST, artist)
      if (album.isNotEmpty()) values.put(MediaStore.Audio.Media.ALBUM, album)

      if (values.size() > 0) {
        try {
          val updated = context.contentResolver.update(
            song.contentUri,
            values,
            null,
            null
          )
          // 更新内存状态，确保 UI 立即刷新
          if (title.isNotEmpty()) song.title = title
          if (artist.isNotEmpty()) song.artist = artist
          if (album.isNotEmpty()) song.album = album

          // 保存到持久化缓存，防止 MediaStore 重启后覆盖或扫描失败
          try {
            val dao = EntryPointAccessors.fromApplication(context.applicationContext, DaoEntryPoint::class.java).metaDataCacheDao()
            dao.insert(MetaDataCache(
              url = song.data,
              title = title.ifEmpty { song.title },
              artist = artist.ifEmpty { song.artist },
              album = album.ifEmpty { song.album },
              duration = song.duration,
              fileSize = song.size,
              lastModified = song.dateModified,
              year = song.year,
              genre = song.genre,
              track = song.track ?: ""
            ))
          } catch (e: Exception) {
            Timber.w("Fail to save to MetaDataCache: $e")
          }

          withContext(Dispatchers.Main) {
            MessageNotifier.show(R.string.save_success)
            if (updated <= 0) {
              Timber.v("MediaStore update returned 0, but fields were manually updated in memory and DB cache")
            }
          }
        } catch (e: SecurityException) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
            if (context is BaseActivity) {
              context.pendingSyncRequest = PendingSyncRequest(song, title, artist, album)
              val intentSenderRequest = androidx.activity.result.IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
              context.syncMediaStoreLauncher.launch(intentSenderRequest)
            }
          } else {
            e.printStackTrace()
          }
        } catch (e: Exception) {
          e.printStackTrace()
          withContext(Dispatchers.Main) {
            MessageNotifier.show(R.string.save_error)
          }
        }
      }
    }

  private val scanningUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())

  fun autoSyncMetadata(context: Context, songs: List<Song>) {
    val toScan = songs.filter { song -> 
      song.isLocal() && !scanningUrls.contains(song.data) 
    }
    if (toScan.isEmpty()) return

    toScan.forEach { scanningUrls.add(it.data) }

    remix.myplayer.App.applicationScope.launch(Dispatchers.IO) {
      toScan.forEach { song ->
        try {
          val audioFile = AudioFileIO.read(File(song.data))
          val tag = audioFile.tag
          if (tag != null) {
            syncMediaStoreTagsSilently(context, song, tag)
          }
        } catch (e: Exception) {
          // 忽略单个文件扫描错误
        }
      }
    }
  }

  private suspend fun syncMediaStoreTagsSilently(
    context: Context,
    song: Song,
    tag: org.jaudiotagger.tag.Tag
  ) {
    val title = tag.getFirst(FieldKey.TITLE)
    val artist = tag.getFirst(FieldKey.ARTIST)
    val album = tag.getFirst(FieldKey.ALBUM)

    if (title.isEmpty() && artist.isEmpty() && album.isEmpty()) return

    // 更新内存
    if (title.isNotEmpty()) song.title = title
    if (artist.isNotEmpty()) song.artist = artist
    if (album.isNotEmpty()) song.album = album

    // 保存到持久化缓存
    try {
      val dao = EntryPointAccessors.fromApplication(context.applicationContext, DaoEntryPoint::class.java).metaDataCacheDao()
      dao.insert(MetaDataCache(
        url = song.data,
        title = title.ifEmpty { song.title },
        artist = artist.ifEmpty { song.artist },
        album = album.ifEmpty { song.album },
        duration = song.duration,
        fileSize = song.size,
        lastModified = song.dateModified,
        year = tag.getFirst(FieldKey.YEAR).ifEmpty { song.year },
        genre = tag.getFirst(FieldKey.GENRE).ifEmpty { song.genre },
        track = tag.getFirst(FieldKey.TRACK).ifEmpty { song.track ?: "" }
      ))
    } catch (e: Exception) {
      Timber.w("Fail to save to MetaDataCache silently: $e")
    }

    // 尝试更新 MediaStore，但不处理权限弹窗（静默处理）
    try {
      val values = ContentValues()
      if (title.isNotEmpty()) values.put(MediaStore.Audio.Media.TITLE, title)
      if (artist.isNotEmpty()) values.put(MediaStore.Audio.Media.ARTIST, artist)
      if (album.isNotEmpty()) values.put(MediaStore.Audio.Media.ALBUM, album)
      if (values.size() > 0) {
        context.contentResolver.update(song.contentUri, values, null, null)
      }
    } catch (ignore: Exception) {
    }
  }
}