package com.nuvio.tv.core.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.nuvio.tv.core.network.NetworkValidation
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.report.PlaybackRecorder
import com.nuvio.tv.data.mediaserver.AndroidServerPersistence
import com.nuvio.tv.data.mediaserver.AndroidServerResumeImports
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServerResumeImports
import com.nuvio.tv.data.repository.networkKindForRecorder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object MediaServerModule {
    @Provides
    @Singleton
    fun repository(
        @ApplicationContext context: Context,
        persistence: AndroidServerPersistence
    ): ServerRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repository = ServerRepository(
            persistence = persistence,
            providers = emptyList(),
            scope = scope,
            isOnCurrentNetwork = { false }
        )
        watchNetworkChanges(context)
        return repository
    }

    private fun watchNetworkChanges(context: Context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    NetworkValidation.note(validated)
                    PlaybackRecorder.noteNetwork(kind = networkKindForRecorder(capabilities), validated = validated)
                }

                override fun onLost(network: Network) {
                    NetworkValidation.note(false)
                    PlaybackRecorder.noteNetwork(kind = "none", validated = false)
                }
            })
        }
    }

    @Provides
    fun resumeImports(imports: AndroidServerResumeImports): ServerResumeImports = imports

    @Provides
    @IntoSet
    fun credentialStore(repository: ServerRepository): ProfileScopedCredentialStore = repository
}
