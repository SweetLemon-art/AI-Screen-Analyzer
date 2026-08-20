package com.example.localai

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalModelRepositoryTest {
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

        try {
            val imported = store.import(plan)

            assertEquals(listOf(imported.id), store.list().map { it.id })
            assertEquals("test model payload", store.modelFile(imported.id).readText())
            assertTrue(store.delete(imported.id))
            assertTrue(store.list().isEmpty())
        } finally {
            source.delete()
        }
    }
}
