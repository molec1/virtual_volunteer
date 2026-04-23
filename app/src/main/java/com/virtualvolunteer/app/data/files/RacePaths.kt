package com.virtualvolunteer.app.data.files

import android.content.Context
import java.io.File

/**
 * Central place for race folder layout under app-local storage.
 * Layout compatible with future export and global participant registry.
 */
object RacePaths {

    fun racesRoot(context: Context): File =
        File(context.filesDir, "races")

    fun raceFolder(context: Context, raceId: String): File =
        File(racesRoot(context), raceId)

    fun raceXml(context: Context, raceId: String): File =
        File(raceFolder(context, raceId), "race.xml")

    /** Pre-resized list preview (~256px) for the main race list; not part of export. */
    fun raceListThumbnailFile(context: Context, raceId: String): File =
        File(raceFolder(context, raceId), "race_list_thumb.jpg")

    fun protocolXml(context: Context, raceId: String): File =
        File(raceFolder(context, raceId), "protocol.xml")

    fun startPhotosDir(context: Context, raceId: String): File =
        File(raceFolder(context, raceId), "start_photos")

    fun finishPhotosDir(context: Context, raceId: String): File =
        File(raceFolder(context, raceId), "finish_photos")

    /** Cropped face thumbnails from start-photo detection (JPEG). */
    fun facesDir(context: Context, raceId: String): File =
        File(raceFolder(context, raceId), "faces")

    /** Detector overlay dumps (JPEG with drawn boxes). */
    fun debugDir(context: Context, raceId: String): File =
        File(raceFolder(context, raceId), "debug")

    fun exportDir(context: Context, raceId: String): File =
        File(raceFolder(context, raceId), "export")

    /** Debug log written by offline "Build test protocol". */
    fun testProtocolDebugLog(context: Context, raceId: String): File =
        File(raceFolder(context, raceId), "protocol_test_debug.log")

    /** True if [absolutePath] resolves under [facesDir] for this race (face crops, not full-frame photos). */
    fun isPathUnderRaceFacesDir(context: Context, raceId: String, absolutePath: String): Boolean {
        return try {
            val faces = facesDir(context, raceId).canonicalFile
            val p = File(absolutePath).canonicalFile
            p.path.startsWith(faces.path + File.separator) || p.path == faces.path
        } catch (_: Exception) {
            false
        }
    }

    /** Full-frame start or finish photos only (not [facesDir] or [debugDir]). */
    fun isPathUnderStartOrFinishPhotosDir(context: Context, raceId: String, absolutePath: String): Boolean {
        return try {
            val p = File(absolutePath).canonicalFile
            val start = startPhotosDir(context, raceId).canonicalFile
            val finish = finishPhotosDir(context, raceId).canonicalFile
            (p.path.startsWith(start.path + File.separator) || p.path == start.path) ||
                (p.path.startsWith(finish.path + File.separator) || p.path == finish.path)
        } catch (_: Exception) {
            false
        }
    }

    fun ensureRaceLayout(context: Context, raceId: String): File {
        val root = raceFolder(context, raceId)
        root.mkdirs()
        startPhotosDir(context, raceId).mkdirs()
        finishPhotosDir(context, raceId).mkdirs()
        facesDir(context, raceId).mkdirs()
        debugDir(context, raceId).mkdirs()
        exportDir(context, raceId).mkdirs()
        return root
    }
}
