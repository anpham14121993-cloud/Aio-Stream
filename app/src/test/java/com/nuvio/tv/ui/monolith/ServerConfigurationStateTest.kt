package com.nuvio.tv.ui.monolith

import com.nuvio.tv.domain.model.ServerCapabilities
import com.nuvio.tv.domain.model.ServerConfiguration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigurationStateTest {

    @Test
    fun `server configuration with blank url or key is marked unconfigured`() {
        val blankConfig = ServerConfiguration(
            backendUrl = "",
            publishableKey = "",
            capabilities = ServerCapabilities(emailPasswordAuth = false, tvLogin = true),
            isCustom = false
        )
        assertFalse(blankConfig.isConfigured)

        val missingKey = blankConfig.copy(backendUrl = "https://example.supabase.co")
        assertFalse(missingKey.isConfigured)

        val missingUrl = blankConfig.copy(publishableKey = "anon-key")
        assertFalse(missingUrl.isConfigured)
    }

    @Test
    fun `server configuration with url and key is marked configured`() {
        val valid = ServerConfiguration(
            backendUrl = "https://example.supabase.co",
            publishableKey = "anon-key",
            capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true),
            isCustom = true
        )
        assertTrue(valid.isConfigured)
    }
}
