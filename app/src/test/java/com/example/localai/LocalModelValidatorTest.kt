package com.example.localai

import android.net.Uri
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalModelValidatorTest {
    private val uri = Uri.parse("content://test/model.litertlm")

    @Test
    fun acceptsValidPlan() {
        LocalModelValidator.validate(
            LocalModelImportPlan(
                sourceUri = uri,
                displayName = "gemma-4-E4B-it.litertlm"
            )
        )
    }

    @Test
    fun rejectsNonLiteRTLMFile() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalModelValidator.validate(LocalModelImportPlan(uri, "model.bin"))
        }
    }

    @Test
    fun rejectsOutOfRangeGenerationSettings() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalModelValidator.validate(
                LocalModelImportPlan(
                    sourceUri = uri,
                    displayName = "model.litertlm",
                    configuration = LocalModelConfiguration(maxTokens = 99)
                )
            )
        }
    }

    @Test
    fun capabilitiesAreMetadataNotImportRestrictions() {
        LocalModelValidator.validate(
            LocalModelImportPlan(
                sourceUri = uri,
                displayName = "text-only.litertlm",
                capabilities = ModelCapabilities(image = false)
            )
        )
    }
}
