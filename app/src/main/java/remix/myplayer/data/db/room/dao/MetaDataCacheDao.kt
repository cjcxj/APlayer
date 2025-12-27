package remix.myplayer.data.db.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import remix.myplayer.data.db.room.entity.MetaDataCache

@Dao
interface MetaDataCacheDao {

  @Query("SELECT * FROM MetaDataCache WHERE url = :url")
  fun get(url: String): MetaDataCache?

  @Query("SELECT * FROM MetaDataCache WHERE url IN (:urls)")
  fun getByUrls(urls: List<String>): List<MetaDataCache>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun insert(cache: MetaDataCache)

  @Query("DELETE FROM MetaDataCache WHERE updateTime < :timestamp")
  suspend fun deleteOldCache(timestamp: Long)
}