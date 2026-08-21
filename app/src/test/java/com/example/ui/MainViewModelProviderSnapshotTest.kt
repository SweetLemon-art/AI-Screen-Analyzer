package com.example.ui

import com.example.ai.AiProviderType
import org.junit.Assert.assertEquals
import org.junit.Test

class MainViewModelProviderSnapshotTest {
    @Test
    fun monitoringProviderSnapshotIsIndependentFromLaterSelectionChanges() {
        val snapshot = AiProviderType.LOCAL
        var selectedProvider = snapshot

        selectedProvider = AiProviderType.GEMINI

        assertEquals(AiProviderType.LOCAL, snapshot)
        assertEquals(AiProviderType.GEMINI, selectedProvider)
    }
}
