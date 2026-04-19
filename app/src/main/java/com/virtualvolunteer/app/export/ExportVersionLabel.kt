package com.virtualvolunteer.app.export

import android.os.Build as AndroidBuild
import com.virtualvolunteer.app.BuildConfig

/**
 * Matches external timing software-style export headers, e.g. {@code virtual_volunteer_android_…}.
 */
object ExportVersionLabel {

    fun get(): String {
        val vn = BuildConfig.VERSION_NAME
        val vc = BuildConfig.VERSION_CODE
        val dev = "${AndroidBuild.MANUFACTURER}_${AndroidBuild.MODEL}"
            .replace(",", "_")
            .replace("\\s+".toRegex(), "")
        return "virtual_volunteer_android_${vn}_${vc}-$dev"
    }
}
