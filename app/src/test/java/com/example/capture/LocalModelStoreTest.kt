package com.example.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalModelStoreTest {
    private lateinit var context: Context
    private lateinit var store: LocalModelStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FileCleanup.delete(context.filesDir.resolve("local_models"))
        store = LocalModelStore(context)
    }

    @After
    fun tearDown() {
        FileCleanup.delete(context.filesDir.resolve("local_models"))
    }

    @Test
    fun import_persists_model_and_configuration() {
        val model = sampleModel()

        store.importModel(
            sourceName = model.fileName,
            source = ByteArrayInputStream("fake-litertlm".toByteArray()),
            model = model,
        )

        assertEquals(listOf(model), store.list())
        assertTrue(store.modelFile(model.id).isFile)
        assertEquals("fake-litertlm", store.modelFile(model.id).readText())
    }

    @Test
    fun delete_removes_model_and_files() {
        val model = sampleModel()
        store.importModel(model.fileName, ByteArrayInputStream(byteArrayOf(1, 2, 3)), model)

        store.delete(model.id)

        assertTrue(store.list().isEmpty())
        assertFalse(store.modelFile(model.id).exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicate_model_id_is_rejected_without_overwriting_existing_model() {
        val model = sampleModel()
        store.importModel(model.fileName, ByteArrayInputStream(byteArrayOf(1)), model)
        store.importModel(model.fileName, ByteArrayInputStream(byteArrayOf(2)), model)
    }

    private fun sampleModel() = LocalModel(
        id = "test-model",
        fileName = "gemma-test.litertlm",
        modelType = ModelType.LLM,
        configuration = LocalModelConfiguration(
            maxTokens = 2048,
            topK = 32,
            topP = 0.9,
            temperature = 0.7,
        ),
        capabilities = ModelCapabilities(image = true, thinking = true),
        accelerator = Accelerator.CPU,
    )

    private object FileCleanup {
        fun delete(file: java.io.File) {
            if (file.exists()) file.deleteRecursively()
        }
    }
}
