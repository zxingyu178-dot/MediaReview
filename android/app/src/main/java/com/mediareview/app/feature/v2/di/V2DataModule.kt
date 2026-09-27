package com.mediareview.app.feature.v2.di

import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.data.V2MediaRepositoryRouter
import com.mediareview.app.feature.v2.data.server.V2ServerResourceCache
import com.mediareview.app.feature.v2.review.data.V2ReviewSessionRepository
import com.mediareview.app.feature.v2.review.data.V2ReviewSessionRepositoryRouter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * V2 数据层绑定（Stage 8A 起，Stage 8B 扩展 Review 会话数据层）：
 *
 * UI 永远注入接口，真实实现是**路由器**——它按运行时数据源模式在 Demo 与 Server
 * 之间委托，因此**不会**在 Hilt 里把 Demo 实现硬替换成 Server 实现，
 * 也不会出现"接 Server 就切回旧 UI"的情况。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class V2DataModule {

    @Binds
    @Singleton
    abstract fun bindMediaRepository(impl: V2MediaRepositoryRouter): MediaRepository

    /** 批阅会话数据层（Stage 8B §14）：媒体 / 收藏 / 待删除仍走 [MediaRepository]。 */
    @Binds
    @Singleton
    abstract fun bindReviewSessionRepository(
        impl: V2ReviewSessionRepositoryRouter,
    ): V2ReviewSessionRepository

    companion object {

        /**
         * Server 资源缓存单例（§15/§38 前提）：媒体墙与批阅队列必须共享同一份映射，
         * 完整播放器才能通过 `mediaById` 打开批阅队列里的当前媒体。
         */
        @Provides
        @Singleton
        fun provideServerResourceCache(): V2ServerResourceCache = V2ServerResourceCache()
    }
}