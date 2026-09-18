package com.baltas80.pocketscan

import org.junit.Assert.assertEquals
import org.junit.Test

class AiModelConfigTest {
    @Test
    fun blankModelFallsBackToDefault() {
        assertEquals("gemini-3.8-flash", AiModelConfig.sanitizeModelName(""))
        assertEquals("gemini-3.8-flash", AiModelConfig.sanitizeModelName(null))
    }

    @Test
    fun unsupportedModelFallsBackToDefault() {
        assertEquals(
            "gemini-3.8-flash",
            AiModelConfig.sanitizeModelName("gemini-unknown-model")
        )
    }

    @Test
    fun supportedModelIsPreserved() {
        assertEquals(
            "gemini-3.8-flash",
            AiModelConfig.sanitizeModelName("  gemini-3.8-flash  ")
        )
    }
}
