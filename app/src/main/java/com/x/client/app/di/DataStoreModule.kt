package com.x.client.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.OkioSerializer
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * DataStore 提供模块。
 *
 * 使用 [MultiProcessDataStoreFactory] 创建跨进程一致的 Preferences DataStore，
 * 主进程 UI 与 :vpn 服务进程共享同一份设置/配置文件（read-after-write 一致性、
 * 写串行化、读不被写阻塞）。同一文件在每个进程内只创建一个实例（Hilt @Singleton），
 * 各进程 SingletonComponent 持有各自实例，天然实现多进程隔离。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    private const val STORE_FILE_NAME = "xclient_prefs.pb"

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> {
        // datastore 1.1.1 起 PreferencesSerializer 实现的是 OkioSerializer（非旧版 Serializer）。
        val serializer: OkioSerializer<Preferences> = PreferencesSerializer
        return MultiProcessDataStoreFactory.create(
            serializer = serializer,
            corruptionHandler = null,
            produceFile = { File(context.filesDir, STORE_FILE_NAME) },
        )
    }
}
