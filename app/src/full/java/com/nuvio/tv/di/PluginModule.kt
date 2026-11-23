package com.nuvio.tv.di

import android.content.Context
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.network.NoDelaySocketFactory
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.plugin.PluginRuntime
import com.nuvio.tv.core.plugin.cloudstream.ExternalExtensionLoader
import com.nuvio.tv.core.plugin.cloudstream.ExternalExtensionRunner
import com.nuvio.tv.core.plugin.cloudstream.ExternalRepoParser
import com.nuvio.tv.core.proxy.JsAddonScriptRunner
import com.nuvio.tv.core.proxy.LocalJsAddonHandler
import com.nuvio.tv.core.proxy.LocalJsAddonStore
import com.nuvio.tv.core.proxy.QuickJsAddonScriptRunner
import com.nuvio.tv.core.sync.PluginSyncService
import com.nuvio.tv.data.local.PluginDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PluginModule {

    @Provides
    @Singleton
    fun provideLocalJsAddonStore(@ApplicationContext context: Context): LocalJsAddonStore {
        return LocalJsAddonStore(File(context.filesDir, "local_js_addons"))
    }

    @Provides
    @Singleton
    fun provideJsAddonScriptRunner(): JsAddonScriptRunner {
        return QuickJsAddonScriptRunner()
    }

    @Provides
    @Singleton
    fun provideLocalJsAddonHandler(
        store: LocalJsAddonStore,
        scriptRunner: JsAddonScriptRunner
    ): LocalJsAddonHandler {
        val outboundClient = OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .socketFactory(NoDelaySocketFactory)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        return LocalJsAddonHandler(
            store = store,
            httpClientProvider = { outboundClient },
            scriptRunner = scriptRunner
        )
    }

    @Provides
    @Singleton
    fun providePluginRuntime(): PluginRuntime {
        return PluginRuntime()
    }

    @Provides
    @Singleton
    fun providePluginManager(
        dataStore: PluginDataStore,
        runtime: PluginRuntime,
        pluginSyncService: PluginSyncService,
        authManager: AuthManager,
        externalRepoParser: ExternalRepoParser,
        externalExtensionLoader: ExternalExtensionLoader,
        externalExtensionRunner: ExternalExtensionRunner
    ): PluginManager {
        return PluginManager(
            dataStore, runtime, pluginSyncService, authManager,
            externalRepoParser, externalExtensionLoader, externalExtensionRunner
        )
    }
}
