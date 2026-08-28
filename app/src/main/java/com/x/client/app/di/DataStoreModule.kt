package com.x.client.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Storage
import androidx.datastore.core.createMultiProcessCoordinator
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlin.coroutines.EmptyCoroutineContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * DataStore 提供模块。
 *
 * 使用 [MultiProcessDataStoreFactory] 创建跨进程一致的 Preferences DataStore，
 * 主进程 UI 与 :vpn 服务进程共享同一份设置/配置文件（read-after-write 一致性、
 * 写串行化、读不被写阻塞）。同一文件在每个进程内只创建一个实例（Hilt @Singleton），
 * 各进程 SingletonComponent 持有各自实例，天然实现多进程隔离。
 *
 * datastore 1.1.1 起 [PreferencesSerializer] 实现的是
 * [androidx.datastore.core.okio.OkioSerializer]（不再是旧版 Serializer），无法直接传给
 * `MultiProcessDataStoreFactory.create(serializer)` 重载。故改用接受 [Storage] 的重载：
 * 以 [OkioStorage] 包装 [PreferencesSerializer]，并通过 [createMultiProcessCoordinator]
 * 提供跨进程协调器（文件锁 + 版本计数 + 文件变更通知），实现真正的多进程一致性。
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
        val storeFile = File(context.filesDir, STORE_FILE_NAME)
        // coordinatorProducer 的 CoroutineContext 用于 SharedCounter 初始化时切换线程；
        // EmptyCoroutineContext 表示在调用线程同步初始化（DataStore 内部默认也如此）。
        val storage: Storage<Preferences> = OkioStorage(
            fileSystem = FileSystem.SYSTEM,
            serializer = PreferencesSerializer,
            coordinatorProducer = { path, _ ->
                createMultiProcessCoordinator(EmptyCoroutineContext, path.toFile())
            },
            producePath = { storeFile.toOkioPath() },
        )
        return MultiProcessDataStoreFactory.create(
            storage = storage,
            corruptionHandler = null,
        )
    }
}
