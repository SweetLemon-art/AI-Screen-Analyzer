package com.example.localai

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LocalModelRepositoryTest {
    @Test
    fun validator_accepts_valid_litertlm_plan() {
        val plan = LocalModelImportPlan(
            sourceUri = Uri.parse("file:///tmp/model.litertlm"),
            displayName = "model.litertlm",
            configuration = LocalModelConfiguration(
                maxTokens = 2048,
                topK = 40,
                topP = 0.9,
                temperature = 0.7
            )
        )

        LocalModelValidator.validate(plan)
    }

    @Test(expected = IllegalArgumentException::class)
    fun validator_rejects_non_litertlm_file() {
        LocalModelValidator.validate(
            LocalModelImportPlan(
                sourceUri = Uri.parse("file:///tmp/model.bin"),
                displayName = "model.bin"
            )
        )
    }

    @Test
    fun store_import_list_model_file_and_delete() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val source = File(context.cacheDir, "test-model.litertlm").apply {
            writeText("test model payload")
        }
        val store = LocalModelStore(context)
        val plan = LocalModelImportPlan(
            sourceUri = Uri.fromFile(source),
            displayName = source.name,
            configuration = LocalModelConfiguration(maxTokens = 512)
        )

        val imported = store.import(plan)

        assertEquals(listOf(imported.id), store.list().map { it.id })
        assertEquals("test model payload", store.modelFile(imported.id).readText())
        assertTrue(store.delete(imported.id))
        assertTrue(store.list().isEmpty())
        source.delete()
    }
}
