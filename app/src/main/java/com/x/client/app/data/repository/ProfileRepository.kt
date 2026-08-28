package com.x.client.app.data.repository

import com.x.client.app.data.model.ProfileConfig
import com.x.client.app.data.model.ProfileInfo
import com.x.client.app.data.prefs.GlobalSettingsDataStore
import com.x.client.app.data.prefs.ProfileDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profile 仓库：聚合 [ProfileDataStore] 与 [GlobalSettingsDataStore]，
 * 对 UI 暴露 Flow 与 suspend 操作。
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val profileDataStore: ProfileDataStore,
    private val globalStore: GlobalSettingsDataStore,
) {

    /** 当前 ProfileId Flow（来自全局设置）。 */
    val currentProfileId: Flow<String?> = globalStore.settings.map { it.currentProfileId }

    /** 全部 Profile 摘要列表 Flow（按名称排序）。 */
    val profileList: Flow<List<ProfileInfo>> = globalStore.settings
        .map { it.profileIds }
        .map { profileDataStore.getProfileList(it) }

    /** profileIds 集合 Flow。 */
    val profileIds: Flow<Set<String>> = globalStore.settings.map { it.profileIds }

    /** 是否存在任何 Profile。 */
    val hasProfiles: Flow<Boolean> = globalStore.settings.map { it.profileIds.isNotEmpty() }

    suspend fun getCurrentProfileId(): String? = globalStore.snapshot().currentProfileId

    suspend fun getProfile(id: String): ProfileConfig = profileDataStore.getProfile(id)

    suspend fun setCurrentProfileId(id: String) = globalStore.setCurrentProfileId(id)

    suspend fun addProfile(name: String): String {
        val id = UUID.randomUUID().toString()
        val ids = globalStore.snapshot().profileIds + id
        globalStore.setProfileIds(ids)
        profileDataStore.setProfileName(id, name)
        return id
    }

    suspend fun saveProfile(config: ProfileConfig) {
        val ids = globalStore.snapshot().profileIds
        if (config.id !in ids) {
            // 新增：先登记 id 再保存字段
            globalStore.setProfileIds(ids + config.id)
        }
        profileDataStore.saveProfile(config)
    }

    suspend fun renameProfile(id: String, name: String) {
        profileDataStore.setProfileName(id, name)
    }

    suspend fun copyProfile(sourceId: String, name: String): String {
        val newId = UUID.randomUUID().toString()
        profileDataStore.copyProfile(sourceId, newId)
        profileDataStore.setProfileName(newId, name)
        globalStore.setProfileIds(globalStore.snapshot().profileIds + newId)
        return newId
    }

    suspend fun removeProfile(id: String) {
        val ids = globalStore.snapshot().profileIds
        if (id !in ids) return
        profileDataStore.removeProfile(id)
        val remaining = ids - id
        globalStore.setProfileIds(remaining)
        // 若删除的是当前配置，自动选第一个剩余
        if (globalStore.snapshot().currentProfileId == id) {
            globalStore.setCurrentProfileId(remaining.firstOrNull())
        }
    }
}
