package com.cwtrainer.app.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.cwtrainer.core.export.Backup
import com.cwtrainer.core.export.BackupCodec
import com.cwtrainer.core.export.BackupException
import com.cwtrainer.core.export.CsvExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Android side of export and backup. The file formats themselves live in core (CsvExport,
 * BackupCodec) and are unit-tested there.
 *
 * Nothing here needs a storage permission: files are either handed to another app through
 * the share sheet (FileProvider) or written to a place the user picks in the system file
 * picker (Storage Access Framework).
 */
class Exporter(private val context: Context, private val repo: Repository) {

    class NamedFile(val name: String, val mime: String, val text: String)

    private val appVersion: String
        get() = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }

    suspend fun csvFiles(): List<NamedFile> {
        val zone = ZoneId.systemDefault()
        val sessions = repo.loadSessions().map { it.toRecord() }
        val items = repo.loadItems().associateBy { it.id }
        val level = repo.loadSettings().kochLevel
        return listOf(
            NamedFile(CsvExport.SESSIONS_FILE, "text/csv", CsvExport.sessions(sessions, zone)),
            NamedFile(CsvExport.CHARACTERS_FILE, "text/csv", CsvExport.characters(level, items, zone)),
            NamedFile(CsvExport.WPM_FILE, "text/csv", CsvExport.wpmHistory(sessions, zone)),
        )
    }

    suspend fun backupFile(): NamedFile =
        NamedFile(backupName(), "application/json", BackupCodec.encode(repo.createBackup(appVersion)))

    fun backupName(): String = "cw-trainer-backup-${LocalDate.now()}.json"

    /** Opens the Android share sheet with the given files attached. */
    suspend fun share(files: List<NamedFile>, title: String) {
        val uris = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "export").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            files.map { f ->
                val file = File(dir, f.name)
                file.writeText(f.text)
                FileProvider.getUriForFile(context, context.packageName + ".files", file)
            }
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = files.first().mime
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData is what actually carries the read grant to the receiving app.
        val clip = ClipData.newUri(context.contentResolver, title, uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        intent.clipData = clip
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Writes one file to a document the user created with the system file picker. */
    suspend fun writeTo(uri: Uri, file: NamedFile) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("Could not open the file for writing")
        out.use { it.write(file.text.toByteArray(Charsets.UTF_8)) }
    }

    /** Writes several files into a folder the user picked with the system folder picker. */
    suspend fun writeToFolder(tree: Uri, files: List<NamedFile>): Int = withContext(Dispatchers.IO) {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var written = 0
        for (f in files) {
            val doc = DocumentsContract.createDocument(context.contentResolver, parent, f.mime, f.name) ?: continue
            context.contentResolver.openOutputStream(doc)?.use { it.write(f.text.toByteArray(Charsets.UTF_8)); written++ }
        }
        written
    }

    /** @throws BackupException with a message that can be shown as it is */
    suspend fun readBackup(uri: Uri): Backup {
        val text = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) {
                null
            }
        } ?: throw BackupException("The file could not be read.")
        return BackupCodec.decode(text)
    }
}
