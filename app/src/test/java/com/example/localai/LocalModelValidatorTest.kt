package com.example.localai

import org.junit.Assert.assertThrows
import org.junit.Test

class LocalModelValidatorTest {
    @Test
    fun acceptsValidFileName() {
        LocalModelValidator.validateFileName("gemma-4-E4B-it.litertlm")
    }

    @Test
    fun rejectsNonLiteRTLMFile() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalModelValidator.validateFileName("model.bin")
        }
    }

    @Test
    fun rejectsPathTraversalInFileName() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalModelValidator.validateFileName("../model.litertlm")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalModelValidator.validateFileName("folder\\model.litertlm")
        }
    }

    @Test
    fun rejectsBlankFileName() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalModelValidator.validateFileName("   ")
        }
    }

    @Test
    fun rejectsOutOfRangeGenerationSettings() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalModelValidator.validateConfiguration(
                LocalModelConfiguration(maxTokens = 99)
            )
        }
    }

    @Test
    fun acceptsCapabilitiesAsMetadataOnly() {
        LocalModelValidator.validateConfiguration(LocalModelConfiguration())
    }
}
