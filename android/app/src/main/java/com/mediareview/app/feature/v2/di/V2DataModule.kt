package com.mediareview.app.feature.v2.di

import com.mediareview.app.feature.v2.data.DemoMediaRepository
import com.mediareview.app.feature.v2.data.MediaRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** V2 数据层绑定：Demo 实现（Stage 1 离线）。未来替换为真实 Server 仓库只需改这里。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class V2DataModule {

    @Binds
    @Singleton
    abstract fun bindMediaRepository(impl: DemoMediaRepository): MediaRepository
}
