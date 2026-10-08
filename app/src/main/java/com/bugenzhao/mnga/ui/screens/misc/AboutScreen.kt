package com.bugenzhao.mnga.ui.screens.misc

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bugenzhao.mnga.App
import com.bugenzhao.mnga.BackGestureDiagnosticsActivity
import com.bugenzhao.mnga.BuildConfig
import com.bugenzhao.mnga.R
import com.bugenzhao.mnga.ui.components.GroupedList
import com.bugenzhao.mnga.ui.components.GroupedRow
import com.bugenzhao.mnga.ui.components.RowChevron
import com.bugenzhao.mnga.ui.nav.Navigator
import com.bugenzhao.mnga.util.Constants
import com.bugenzhao.mnga.util.L
import com.bugenzhao.mnga.util.BackDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * About page: app icon, version, and the update check. Links point at the
 * GitHub repository that releases are published to (see [Constants.GitHub]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(navigator: Navigator? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveBackLogs = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val logs = BackDiagnostics.snapshot()
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri, "wt")
                            ?: error("Could not open diagnostic file")
                        stream.bufferedWriter().use { it.write(logs) }
                    }
                    Toast.makeText(context, "返回调试日志已保存 / Back logs saved", Toast.LENGTH_SHORT).show()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Toast.makeText(context, "无法保存返回日志 / Could not save back logs", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun open(url: String) {
        App.openURL.open(Uri.parse(url), inApp = false, prefs = App.prefs)
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(L.str(context, "About")) },
                navigationIcon = {
                    IconButton(onClick = { navigator?.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item(key = "header") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(
                        // 用独立 PNG（adaptive-icon XML 不支持 painterResource）。
                        painter = painterResource(R.drawable.ic_launcher_about),
                        contentDescription = null,
                        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)),
                    )
                    Text(
                        // Brand name, intentionally not localized.
                        "LumaGA",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Text(
                        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "update") {
                GroupedList { Column { CheckForUpdatesRow() } }
            }

            item(key = "back-diagnostics") {
                GroupedList {
                    Column {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            GroupedRow(
                                onClick = {
                                    context.startActivity(Intent(context, BackGestureDiagnosticsActivity::class.java))
                                },
                                title = context.getString(R.string.back_probe_title),
                                subtitle = context.getString(R.string.back_probe_subtitle),
                                trailing = { RowChevron() },
                            )
                        }
                        GroupedRow(
                            onClick = { saveBackLogs.launch("predictive-back.txt") },
                            title = "保存返回调试日志 / Save back debug logs",
                            subtitle = "导出文本文件，无需 ADB / Export a text file without ADB",
                            trailing = { RowChevron() },
                        )
                    }
                }
            }

            item(key = "links") {
                GroupedList {
                    Column {
                        GroupedRow(
                            onClick = { open(Constants.GitHub.repoUrl) },
                            title = L.str(context, "Source Code"),
                            subtitle = Constants.GitHub.repo,
                            trailing = { RowChevron() },
                        )
                        GroupedRow(
                            onClick = { open(Constants.GitHub.releasesUrl) },
                            title = L.str(context, "Release Notes"),
                            trailing = { RowChevron() },
                        )
                    }
                }
            }

            item(key = "footer") {
                Text(
                    L.str(context, "An NGA forum client, ported from MNGA."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
    }

    UpdateFlowDialogs()
}
