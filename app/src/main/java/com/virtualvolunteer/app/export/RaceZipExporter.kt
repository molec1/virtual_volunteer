package com.virtualvolunteer.app.export

import android.content.Context
import com.virtualvolunteer.app.data.files.RacePaths
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Creates a simple ZIP export with race.xml, protocol.xml, and both photo folders.
 */
object RaceZipExporter {

    fun exportRaceFolder(context: Context, raceId: String): Result<File> = runCatching {
        val exportDir = RacePaths.exportDir(context, raceId)
        exportDir.mkdirs()
        val zip = File(exportDir, "race_${raceId.take(8)}_${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            addFile(zos, RacePaths.raceXml(context, raceId), "race.xml")
            addFile(zos, RacePaths.protocolXml(context, raceId), "protocol.xml")
            addFile(zos, RacePaths.testProtocolDebugLog(context, raceId), "protocol_test_debug.log")
            addDirectory(zos, RacePaths.startPhotosDir(context, raceId), "start_photos")
            addDirectory(zos, RacePaths.finishPhotosDir(context, raceId), "finish_photos")
            addDirectory(zos, RacePaths.facesDir(context, raceId), "faces")
        }
        zip
    }

    private fun addFile(zos: ZipOutputStream, file: File, entryName: String) {
        if (!file.exists()) return
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        BufferedInputStream(FileInputStream(file)).use { input -> input.copyTo(zos) }
        zos.closeEntry()
    }

    private fun addDirectory(zos: ZipOutputStream, dir: File, prefix: String) {
        if (!dir.exists()) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (!f.isFile) continue
            val rel = "$prefix/${f.name}"
            addFile(zos, f, rel)
        }
    }
}
