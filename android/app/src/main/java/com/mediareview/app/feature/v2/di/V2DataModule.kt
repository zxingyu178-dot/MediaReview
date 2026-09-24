package com.mediareview.app.feature.v2.di

import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.data.V2MediaRepositoryRouter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * V2 数据层绑定（Stage 8A）：
 *
 * UI 永远注入 [MediaRepository]，真实实现是 [V2MediaRepositoryRouter]——
 * 它按运行时数据源模式在 Demo 与 Server 之间委托，因此**不会**在 Hilt 里
 * 把 DemoMediaRepository 硬替换成 V2ServerMediaRepository，也不会出现
 * "接 Server 就切回旧 UI"的情况。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class V2DataModule {

    @Binds
    @Singleton
    abstract fun bindMediaRepository(impl: V2MediaRepositoryRouter): MediaRepository
}