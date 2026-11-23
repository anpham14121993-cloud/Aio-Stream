package com.nuvio.tv.core.sync

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.mediaserver.FakeServerProvider
import com.nuvio.tv.data.mediaserver.MemoryServerPersistence
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.domain.model.AuthState
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaServerSyncServiceTest {
    private val calls = mutableListOf<String>()
    private val authState = MutableStateFlow<AuthState>(AuthState.FullAccount("user", "user@example.com"))
    private val activeProfileId = MutableStateFlow(1)
    private val repository = ServerRepository(MemoryServerPersistence(), listOf(FakeServerProvider()), CoroutineScope(Dispatchers.Unconfined))
        .also { it.setSyncEnabled(true) }
    private val service = serviceFor(repository)

    private fun serviceFor(repository: ServerRepository) = MediaServerSyncService(
        postgrest = postgrest(),
        authManager = mockk<AuthManager>(relaxed = true) { every { authState } returns this@MediaServerSyncServiceTest.authState },
        profileManager = mockk<ProfileManager> { every { activeProfileId } returns this@MediaServerSyncServiceTest.activeProfileId },
        repository = repository,
        syncClientIdentity = mockk { every { currentClientId() } returns "tv-test" }
    )

    @Test
    fun `syncFromRemote is disabled and performs no backend RPC calls`() = runBlocking {
        repository.store(connection("local-a", server = "s1"), token = "token-a")

        val changed = service.syncFromRemote(1).getOrThrow()

        assertFalse(changed)
        assertTrue(calls.isEmpty())
        assertEquals(listOf("local-a"), repository.uiState.value.connections.map { it.id })
    }

    @Test
    fun `requestForegroundPull and profile switch perform no backend RPC calls`() = runBlocking {
        service.requestForegroundPull(force = true)
        activeProfileId.value = 2
        delay(50L)

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `removeFromAccount clears local sync flag without remote calls`() = runBlocking {
        service.removeFromAccount(1).getOrThrow()

        assertFalse(repository.uiState.value.syncEnabled)
        assertTrue(calls.isEmpty())
    }

    private fun connection(id: String, server: String) = ServerConnection(
        id = id,
        providerId = "fake",
        name = "Box",
        address = "https://fake.example",
        remoteServerId = server,
        remoteUserId = "u1",
        userName = "viewer",
        credentialRef = "k$id",
        libraries = listOf(FakeServerProvider.MOVIE_LIBRARY)
    )

    private fun postgrest(): Postgrest {
        val postgrest = mockk<Postgrest>()
        every { postgrest.serializer } returns KotlinXSerializer()
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } answers {
            synchronized(calls) { calls += firstArg<String>() }
            error("Unexpected RPC while media server sync is disabled")
        }
        return postgrest
    }
}
