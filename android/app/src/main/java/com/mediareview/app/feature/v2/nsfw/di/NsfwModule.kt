package com.mediareview.app.feature.v2.nsfw.di

import android.content.Context
import coil.ImageLoader
import com.mediareview.app.feature.v2.nsfw.data.NsfwPairingGateway
import com.mediareview.app.feature.v2.nsfw.data.NsfwPairingRepository
import com.mediareview.app.feature.v2.nsfw.data.NsfwProfileStore
import com.mediareview.app.feature.v2.nsfw.data.NsfwRepository
import com.mediareview.app.feature.v2.nsfw.data.NsfwReviewDataSource
import com.mediareview.app.feature.v2.nsfw.network.NsfwApiFactory
import com.mediareview.app.feature.v2.nsfw.network.NsfwAuthInterceptor
import com.mediareview.app.feature.v2.nsfw.network.NsfwTokenProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** 包裹 NSFW 专用 ImageLoader，避免与 MediaReview 的 Coil 实例（cache token）混用。 */
class NsfwImageLoader(val loader: ImageLoader)

/**
 * NSFW Studio 独立依赖图：独立 DataStore、独立 token、独立 OkHttp/Retrofit/Coil。
 * 不引用 MediaReview 的 Provider，因此 401 与凭据生命周期天然隔离。
 */
@Module
@InstallIn(SingletonComponent::class)
object NsfwModule {

    @Provides
    @Singleton
    fun provideNsfwProfileStore(@ApplicationContext context: Context): NsfwProfileStore =
        NsfwProfileStore(context)

    @Provides
    @Singleton
    fun provideNsfwTokenProvider(): NsfwTokenProvider = NsfwTokenProvider()

    @Provides
    @Singleton
    fun provideNsfwAuthInterceptor(tokenProvider: NsfwTokenProvider): NsfwAuthInterceptor =
        NsfwAuthInterceptor(tokenProvider)

    @Provides
    @Singleton
    @Named("nsfwAuth")
    fun provideNsfwOkHttp(auth: NsfwAuthInterceptor): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(auth)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    @Named("nsfwPublic")
    fun provideNsfwPublicOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    @Provides
    @Singleton
    fun provideNsfwApiFactory(
        @Named("nsfwAuth") authenticated: OkHttpClient,
        @Named("nsfwPublic") public: OkHttpClient,
        json: Json,
    ): NsfwApiFactory = NsfwApiFactory(authenticated, public, json)

    @Provides
    @Singleton
    fun provideNsfwPairingRepository(
        store: NsfwProfileStore,
        tokenProvider: NsfwTokenProvider,
        apiFactory: NsfwApiFactory,
    ): NsfwPairingRepository = NsfwPairingRepository(store, tokenProvider, apiFactory)

    @Provides
    @Singleton
    fun provideNsfwPairingGateway(impl: NsfwPairingRepository): NsfwPairingGateway = impl

    @Provides
    @Singleton
    fun provideNsfwRepository(
        store: NsfwProfileStore,
        apiFactory: NsfwApiFactory,
    ): NsfwRepository = NsfwRepository(store, apiFactory)

    @Provides
    @Singleton
    fun provideNsfwReviewDataSource(impl: NsfwRepository): NsfwReviewDataSource = impl

    @Provides
    @Singleton
    fun provideNsfwImageLoader(
        @ApplicationContext context: Context,
        @Named("nsfwAuth") http: OkHttpClient,
    ): NsfwImageLoader = NsfwImageLoader(
        ImageLoader.Builder(context).okHttpClient { http }.build(),
    )
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NsfwEntryPoint {
    fun nsfwImageLoader(): NsfwImageLoader
}
