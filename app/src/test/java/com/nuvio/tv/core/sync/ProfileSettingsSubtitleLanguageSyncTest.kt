package com.nuvio.tv.core.sync

import androidx.datastore.preferences.core.edit
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.SubtitleOrganizationMode
import com.nuvio.tv.data.local.SubtitleOutlineSize
import com.nuvio.tv.domain.model.UserProfile
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.http.Headers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSettingsSubtitleLanguageSyncTest {
    private val dispatcher = StandardTestDispatcher()
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns dispatcher
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        unmockkStatic(Dispatchers::class)
    }

    @Test
    fun `subtitle languages and rules never upload and are not restored from remote`() = runTest(dispatcher) {
        val harness = harness()
        harness.player.setSubtitlePreferredLanguage("es-419")
        harness.player.setSubtitleSecondaryLanguage("es")
        harness.player.setSubtitleTertiaryLanguage("en")
        harness.player.setAddonSubtitlesEnabled(false)
        harness.player.setPreferAddonSubtitles(true)
        harness.player.setSubtitleStripSdh(true)
        harness.player.setSubtitleOrganizationMode(SubtitleOrganizationMode.BY_LANGUAGE)
        harness.service.pushCurrentProfileToRemote().getOrThrow()

        val uploaded = harness.remote.getValue(1).getValue("features").jsonObject
            .getValue("player_settings").jsonObject
        listOf(
            "subtitle_preferred_language",
            "subtitle_secondary_language",
            "subtitle_tertiary_language",
            "addon_subtitles_enabled",
            "subtitle_prefer_addon",
            "subtitle_strip_sdh",
            "subtitle_organization_mode"
        ).forEach { key -> assertFalse(key, uploaded.containsKey(key)) }

        harness.playerStore.edit { it.clear() }
        harness.remote[1] = buildJsonObject {
            put("version", 1)
            put("features", buildJsonObject {
                put("player_settings", buildJsonObject {
                    put("subtitle_preferred_language", encodedString("fr"))
                    put("subtitle_secondary_language", encodedString("de"))
                    put("subtitle_tertiary_language", encodedString("ja"))
                    put("subtitle_use_forced_subtitles", encodedBoolean(false))
                    put("subtitle_show_only_preferred_languages", encodedBoolean(true))
                    put("addon_subtitles_enabled", encodedBoolean(false))
                    put("subtitle_prefer_addon", encodedBoolean(true))
                    put("subtitle_strip_sdh", encodedBoolean(true))
                    put("subtitle_organization_mode", encodedString(SubtitleOrganizationMode.BY_LANGUAGE.name))
                })
            })
        }
        harness.service.pullCurrentProfileFromRemote().getOrThrow()

        val settings = harness.player.playerSettings.first()
        val style = settings.subtitleStyle
        assertTrue(style.isPreferredLanguageSystemDefault)
        assertEquals(null, style.secondaryPreferredLanguage)
        assertEquals(null, style.tertiaryPreferredLanguage)
        assertTrue(settings.addonSubtitlesEnabled)
        assertFalse(style.preferAddonSubtitles)
        assertFalse(style.stripSdh)
        assertEquals(SubtitleOrganizationMode.NONE, settings.subtitleOrganizationMode)
    }

    @Test
    fun `remote subtitle language settings do not overwrite local subtitle choices`() = runTest(dispatcher) {
        val harness = harness()
        harness.player.setSubtitlePreferredLanguage("vi")
        harness.player.setSubtitleSize(85)
        harness.player.setSubtitleBitmapSize(90)
        harness.player.setSubtitleVerticalOffset(12)
        harness.player.setSubtitleOutlineSize(SubtitleOutlineSize.THICK)
        harness.player.setSubtitleShadowEnabled(false)
        harness.service.pushCurrentProfileToRemote().getOrThrow()

        val uploaded = harness.remote.getValue(1).getValue("features").jsonObject
            .getValue("player_settings").jsonObject
        assertFalse(uploaded.containsKey("subtitle_preferred_language"))
        assertFalse(uploaded.containsKey("subtitle_size"))
        assertFalse(uploaded.containsKey("subtitle_bitmap_size"))
        assertFalse(uploaded.containsKey("subtitle_vertical_offset"))

        harness.remote[1] = buildJsonObject {
            put("version", 1)
            put("features", buildJsonObject {
                put("player_settings", buildJsonObject {
                    put("subtitle_preferred_language", encodedString("en"))
                    put("subtitle_size", encodedInt(160))
                    put("subtitle_bitmap_size", encodedInt(150))
                    put("subtitle_vertical_offset", encodedInt(25))
                })
            })
        }

        harness.service.pullCurrentProfileFromRemote().getOrThrow()

        val style = harness.player.playerSettings.first().subtitleStyle
        assertEquals("vi", style.preferredLanguage)
        assertEquals(85, style.size)
        assertEquals(90, style.bitmapSize)
        assertEquals(12, style.verticalOffset)
        assertEquals(SubtitleOutlineSize.THICK, style.effectiveOutlineSize)
        assertFalse(style.effectiveShadowEnabled)
    }

    private fun harness(): Harness {
        val stores = mutableMapOf<Pair<Int, String>, TestPreferencesStore>()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getOrPut(firstArg<Int>() to secondArg<String>()) { TestPreferencesStore() }
        }
        every { factory.corruptedFileNames } returns mutableSetOf()
        val activeProfileId = MutableStateFlow(1)
        val profiles = MutableStateFlow(listOf(UserProfile(1, "Primary", "#000000")))
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfileId
        every { profileManager.profiles } returns profiles
        every { profileManager.activeProfile } answers { profiles.value.first { it.id == activeProfileId.value } }
        val authManager = mockk<AuthManager>(relaxed = true)
        every { authManager.isAuthenticated } returns false
        val identity = mockk<SyncClientIdentity>()
        every { identity.currentClientId() } returns "nuvio-tv-subtitle-sync-test"
        val remote = mutableMapOf<Int, JsonObject>()
        val postgrest = mockk<Postgrest>()
        every { postgrest.serializer } returns KotlinXSerializer()
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } answers {
            val params = secondArg<JsonObject>()
            val requestedProfile = params.getValue("p_profile_id").jsonPrimitive.int
            val response = when (firstArg<String>()) {
                "sync_push_profile_settings_blob" -> {
                    remote[requestedProfile] = params.getValue("p_settings_json").jsonObject
                    ""
                }
                "sync_pull_profile_settings_blob" -> {
                    val blob = remote[requestedProfile]
                    JsonArray(if (blob == null) emptyList() else listOf(buildJsonObject {
                        put("profile_id", requestedProfile)
                        put("settings_json", blob)
                    })).toString()
                }
                else -> error("Unexpected RPC")
            }
            PostgrestResult(response, Headers.Empty, postgrest)
        }
        val service = ProfileSettingsSyncService(
            authManager, postgrest, profileManager, factory, identity,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), ProfileSettingsSyncStateStore(mockk(relaxed = true))
        )
        val player = PlayerSettingsDataStore(factory, profileManager, mockk(relaxed = true))
        scopes += privateScope(service, ProfileSettingsSyncService::class.java, "scope")
        scopes += privateScope(player, PlayerSettingsDataStore::class.java, "ioScope")
        return Harness(service, player, factory.get(1, "player_settings") as TestPreferencesStore, remote)
    }

    private fun privateScope(owner: Any, type: Class<*>, name: String) =
        type.getDeclaredField(name).apply { isAccessible = true }.get(owner) as CoroutineScope

    private fun encodedString(value: String) = buildJsonObject {
        put("type", "string")
        put("value", value)
    }

    private fun encodedBoolean(value: Boolean) = buildJsonObject {
        put("type", "boolean")
        put("value", value)
    }

    private fun encodedInt(value: Int) = buildJsonObject {
        put("type", "int")
        put("value", value)
    }

    private data class Harness(
        val service: ProfileSettingsSyncService,
        val player: PlayerSettingsDataStore,
        val playerStore: TestPreferencesStore,
        val remote: MutableMap<Int, JsonObject>
    )
}
