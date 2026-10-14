package com.modhub.app.di

import android.content.Context
import androidx.room.Room
import com.modhub.app.BuildConfig
import com.modhub.app.data.db.InstalledModDao
import com.modhub.app.data.db.ModHubDatabase
import com.modhub.app.platform.AndroidPackageInspector
import com.modhub.core.plugin.PackageInspector
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** Shared client. Sources add their own auth headers via `newBuilder()`. */
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "ModHub/${BuildConfig.VERSION_NAME} (Android)")
                    .build(),
            )
        }
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader("apikey")
                    },
                )
            }
        }
        .build()

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ModHubDatabase =
        Room.databaseBuilder(context, ModHubDatabase::class.java, "modhub.db").build()

    @Provides
    fun installedModDao(db: ModHubDatabase): InstalledModDao = db.installedModDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlatformBindings {
    @Binds
    abstract fun packageInspector(impl: AndroidPackageInspector): PackageInspector
}
