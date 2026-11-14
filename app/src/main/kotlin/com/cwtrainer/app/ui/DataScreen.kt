package com.cwtrainer.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cwtrainer.app.AppContainer
import com.cwtrainer.core.export.Backup
import com.cwtrainer.core.export.BackupException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** CSV export, full backup and restore. No storage permission is involved anywhere. */
@Composable
fun DataScreen(container: AppContainer, onBack: () -> Unit) {
    val exporter = container.exporter
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Backup?>(null) }

    /** Runs an export step and reports the outcome on screen. */
    fun attempt(block: suspend () -> String?) {
        scope.launch {
            try {
                val result = block()
                failed = false
                message = result
            } catch (e: BackupException) {
                failed = true
                message = e.message
            } catch (e: Exception) {
                failed = true
                message = "That did not work: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) attempt { exporter.writeTo(uri, exporter.backupFile()); "Backup saved." }
    }
    val saveCsvFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) attempt {
            val n = exporter.writeToFolder(uri, exporter.csvFiles())
            "$n CSV files saved to the folder."
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) attempt { pending = exporter.readBackup(uri); null }
    }

    Page("Export and backup", onBack = onBack) {
        Panel {
            Label("Spreadsheet export (CSV)")
            Text(
                "Three files: every session, per-character statistics (accuracy, reaction time, SM-2 interval and ease) and your receive speed per day.",
                color = Cw.Dim, fontSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Share", { attempt { exporter.share(exporter.csvFiles(), "CW Trainer export"); null } }, Modifier.weight(1f), height = 56.dp)
                QuietButton("Save to folder", { saveCsvFolder.launch(null) }, Modifier.weight(1f), height = 56.dp)
            }
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            Label("Full backup (JSON)")
            Text(
                "One file with your lesson, settings, every session and all spaced-repetition history. Keep it somewhere outside this phone.",
                color = Cw.Dim, fontSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Save backup", { saveBackup.launch(exporter.backupName()) }, Modifier.weight(1f), height = 56.dp)
                QuietButton("Share", { attempt { exporter.share(listOf(exporter.backupFile()), "CW Trainer backup"); null } }, Modifier.weight(1f), height = 56.dp)
            }
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            Label("Restore")
            Text(
                "Moving to a new phone: install the app there, copy the backup file across, then restore it here. Restoring replaces the progress on this phone.",
                color = Cw.Dim, fontSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
            QuietButton("Choose a backup file", { openBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, Modifier.fillMaxWidth(), height = 56.dp)
        }

        val shown = message
        if (shown != null) {
            Spacer(Modifier.height(16.dp))
            Text(shown, color = if (failed) Cw.Bad else Cw.Good, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Files leave the app only through the share sheet or a location you pick. The app has no storage or internet permission.",
            color = Cw.Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(24.dp))
    }

    val backup = pending
    if (backup != null) {
        val made = if (backup.exportedAt <= 0) "unknown date" else
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").format(Instant.ofEpochMilli(backup.exportedAt).atZone(ZoneId.systemDefault()))
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Made on $made with version ${backup.appVersion.ifEmpty { "?" }}.\n" +
                        "Lesson ${backup.settings["kochLevel"] ?: "?"}, ${backup.sessions.size} sessions, ${backup.items.size} learned items.\n\n" +
                        "Everything on this phone will be replaced. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    container.listening.stop()
                    attempt { container.repo.restore(backup); "Backup restored." }
                }) { Text("Restore", color = Cw.Bad) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}
