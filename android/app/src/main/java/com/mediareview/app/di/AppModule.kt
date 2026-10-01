package com.mediareview.app.di

import android.content.Context
import coil.ImageLoader
import com.mediareview.app.core.datastore.MediaWallSettingsStore
import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.feature.v2.releasenotes.ReleaseNotesDataSource
import com.mediareview.app.feature.v2.releasenotes.ReleaseNotesStore
import com.mediareview.app.core.media.PlayerCore
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.AuthInterceptor
import com.mediareview.app.core.network.CacheAuthInterceptor
import com.mediareview.app.core.network.TokenProvider
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.feature.connect.data.PairingRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideJson(): Json = json

    @Provides
    @Singleton
    fun provideServerProfileStore(
        @ApplicationContext context: Context,
    ): ServerProfileStore = ServerProfileStore(context)

    @Provides
    @Singleton
    fun provideMediaWallSettingsStore(
        @ApplicationContext context: Context,
    ): MediaWallSettingsStore = MediaWallSettingsStore(context)

    /** 版本更新日志展示记录（Stage 8C.1 §40）：DataStore 持久化 last_seen_version_code。 */
    @Provides
    @Singleton
    fun provideReleaseNotesDataSource(
        @ApplicationContext context: Context,
    ): ReleaseNotesDataSource = ReleaseNotesStore(context)

    @Provides
    @Singleton
    fun provideTokenProvider(): TokenProvider = TokenProvider()

    @Provides
    @Singleton
    fun provideAuthInterceptor(
        tokenProvider: TokenProvider,
    ): AuthInterceptor = AuthInterceptor(tokenProvider)

    @Provides
    @Singleton
    fun provideCacheAuthInterceptor(
        tokenProvider: TokenProvider,
    ): CacheAuthInterceptor = CacheAuthInterceptor(tokenProvider)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        auth: AuthInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(auth)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    @Named("public")
    fun providePublicOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    @Provides
    @Singleton
    @Named("cache")
    fun provideCacheOkHttpClient(
        cacheAuth: CacheAuthInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(cacheAuth)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        @Named("cache") cacheOkHttp: OkHttpClient,
    ): ImageLoader = ImageLoader.Builder(context)
        .okHttpClient { cacheOkHttp }
        .build()

    /**
     * 提供 API 工厂:根据服务器地址重建 Retrofit。
     * Retrofit baseUrl 必须存在,此作为连接/配对流程的健康检查与正式 API 统一入口。
     */
    @Provides
    @Singleton
    fun provideApiFactory(
        okHttp: OkHttpClient,
        @Named("public") publicHttp: OkHttpClient,
        json: Json,
    ): ApiFactory = ApiFactory(okHttp, publicHttp, json)

    @Provides
    @Singleton
    fun providePairingRepository(
        store: ServerProfileStore,
        tokenProvider: TokenProvider,
        apiFactory: ApiFactory,
    ): PairingRepository = PairingRepository(store, tokenProvider, apiFactory)

    @Provides
    @Singleton
    fun providePlayerCore(
        @ApplicationContext context: Context,
    ): PlayerCore = PlayerCore(context)
}
