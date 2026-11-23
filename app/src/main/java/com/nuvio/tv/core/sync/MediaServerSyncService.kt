package com.nuvio.tv.core.sync

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.mediaserver.ServerRepository
import io.github.jan.supabase.postgrest.Postgrest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaServerSyncService @Inject constructor(
    @Suppress("unused") private val postgrest: Postgrest,
    @Suppress("unused") private val authManager: AuthManager,
    private val profileManager: ProfileManager,
    private val repository: ServerRepository,
    @Suppress("unused") private val syncClientIdentity: SyncClientIdentity
) {
    suspend fun syncFromRemote(
        @Suppress("UNUSED_PARAMETER") profileId: Int = profileManager.activeProfileId.value
    ): Result<Boolean> = Result.success(false)

    /** Media server feature and account sync are disabled across all interfaces. */
    suspend fun removeFromAccount(
        @Suppress("UNUSED_PARAMETER") profileId: Int = profileManager.activeProfileId.value
    ): Result<Unit> {
        repository.setSyncEnabled(false)
        return Result.success(Unit)
    }

    fun requestForegroundPull(@Suppress("UNUSED_PARAMETER") force: Boolean = false) = Unit
}
