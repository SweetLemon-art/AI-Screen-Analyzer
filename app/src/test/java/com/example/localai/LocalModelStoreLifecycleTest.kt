package com.example.localai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalModelStoreLifecycleTest {
    private lateinit var context: Context
    private lateinit var store: LocalModelStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.deleteRecursively()
        context.filesDir.mkdirs()
        store = LocalModelStore(context)
    }

    @Test
    fun importListModelFileDeleteRemovesModel() {
        val source = LocalModelImportPlan(
            sourceUri = android.net.Uri.parse("file:///synthetic/model.litertlm"),
            displayName = "model.litertlm",
            modelType = ModelType.LLM,
            configuration = LocalModelConfiguration(),
            capabilities = ModelCapabilities(),
            accelerator = Accelerator.CPU
        )

        val imported = store.import(source, "model-data".byteInputStream())

        assertEquals(listOf(imported.id), store.list().map { it.id })
        assertTrue(store.modelFile(imported.id).isFile)
        assertTrue(store.delete(imported.id))
        assertTrue(store.list().isEmpty())
        assertFalse(File(context.filesDir, "local_models/${imported.id}").exists())
    }

    @Test
    fun invalidModelIdIsRejectedBeforeFilesystemAccess() {
        val invalidIds = listOf("../escape", "../../escape", "id with spaces", "")

        invalidIds.forEach { id ->
            try {
                store.delete(id)
                throw AssertionError("Expected invalid model id to be rejected: $id")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun failedImportLeavesNoTemporaryOrFinalDirectory() {
        val plan = LocalModelImportPlan(
            sourceUri = android.net.Uri.parse("file:///synthetic/model.litertlm"),
            displayName = "model.litertlm",
            modelType = ModelType.LLM,
            configuration = LocalModelConfiguration(),
            capabilities = ModelCapabilities(),
            accelerator = Accelerator.CPU
        )

        try {
            store.import(plan, object : java.io.InputStream() {
                override fun read(): Int = throw java.io.IOException("synthetic copy failure")
            })
            throw AssertionError("Expected import to fail")
        } catch (_: java.io.IOException) {
            // expected
        }

        val modelRoot = File(context.filesDir, "local_models")
        assertTrue(modelRoot.listFiles().orEmpty().isEmpty())
    }
}
