package com.bugenzhao.mnga

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bugenzhao.mnga.util.BackDiagnostics
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackDiagnosticsTest {
    @Test
    fun snapshotIncludesQueuedLogsAndPersistsPrivately() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val marker = "diagnostic-export-test-${System.nanoTime()}"
        BackDiagnostics.log(marker)

        val text = BackDiagnostics.snapshot()
        assertTrue(text.contains("LumaGABack $marker\n"))
        val file = File(context.noBackupFilesDir, "back-diagnostics/predictive-back.txt")
        assertTrue(file.readText().contains(marker))
    }
}
