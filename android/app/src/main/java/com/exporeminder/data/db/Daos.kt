package com.exporeminder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ExhibitionDao {
    @Upsert
    suspend fun upsertAll(items: List<ExhibitionEntity>)

    @Query("SELECT * FROM exhibition")
    suspend fun all(): List<ExhibitionEntity>

    @Query("SELECT * FROM exhibition")
    fun observeAll(): Flow<List<ExhibitionEntity>>

    @Query("SELECT * FROM exhibition WHERE id IN (SELECT exhibitionId FROM follow WHERE muted = 0)")
    suspend fun followed(): List<ExhibitionEntity>

    /** 同步後清掉 JSON 已不存在的展覽,但追蹤中的保留(提醒續用最後已知資料)。 */
    @Query(
        "DELETE FROM exhibition WHERE id NOT IN (:keepIds) " +
            "AND id NOT IN (SELECT exhibitionId FROM follow)"
    )
    suspend fun deleteAbsentExcept(keepIds: List<String>)

    @Query("SELECT * FROM exhibition WHERE id = :id")
    suspend fun byId(id: String): ExhibitionEntity?
}

@Dao
interface FollowDao {
    @Query("SELECT * FROM follow")
    fun observeAll(): Flow<List<FollowEntity>>

    @Query("SELECT * FROM follow")
    suspend fun all(): List<FollowEntity>

    /** 提醒只看未靜音的追蹤。 */
    @Query("SELECT exhibitionId FROM follow WHERE muted = 0")
    suspend fun unmutedIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(f: FollowEntity)

    /** 匯入用:整批寫入。Room 會把它包在單一交易,中途失敗不會留下半套資料。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addAll(items: List<FollowEntity>)

    @Query("DELETE FROM follow WHERE exhibitionId = :id")
    suspend fun remove(id: String)

    @Query("UPDATE follow SET muted = :muted WHERE exhibitionId = :id")
    suspend fun setMuted(id: String, muted: Boolean)
}

@Dao
interface NotificationLogDao {
    @Query("SELECT COUNT(*) FROM notification_log WHERE ruleKey = :ruleKey AND refKey = :refKey")
    suspend fun count(ruleKey: String, refKey: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(log: NotificationLogEntity)
}

@Dao
interface SuggestedLinkDao {
    @Query("SELECT * FROM suggested_link")
    suspend fun all(): List<SuggestedLinkEntity>

    @Query("SELECT * FROM suggested_link")
    fun observeAll(): Flow<List<SuggestedLinkEntity>>

    @Transaction
    suspend fun replaceAll(items: List<SuggestedLinkEntity>) {
        clear()
        insertAll(items)
    }

    @Query("DELETE FROM suggested_link")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SuggestedLinkEntity>)
}
