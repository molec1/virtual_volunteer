package com.virtualvolunteer.app.ui.racedetail

import android.net.Uri
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.data.files.UriFileCopy
import com.virtualvolunteer.app.domain.RacePhotoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RaceDetailOfflineTestActions(
    private val fragment: Fragment,
    private val raceId: String,
    private val photoProcessor: RacePhotoProcessor,
    private val logTag: String,
) {

    fun onBuildTestProtocolClicked() {
        fragment.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = fragment.requireContext().applicationContext
            val result = photoProcessor.buildTestProtocolFromFinishFolder(ctx, raceId)
            withContext(Dispatchers.Main) {
                result.onSuccess { logFile ->
                    Toast.makeText(
                        fragment.requireContext(),
                        fragment.getString(R.string.test_protocol_built) + "\n${logFile.name}",
                        Toast.LENGTH_LONG,
                    ).show()
                    Log.i(logTag, "Test protocol log: ${logFile.absolutePath}")
                }.onFailure {
                    Toast.makeText(fragment.requireContext(), R.string.test_protocol_failed, Toast.LENGTH_SHORT).show()
                    Log.w(logTag, "build test protocol failed", it)
                }
            }
        }
    }

    fun launchSingleFinishPhotoDebug(uri: Uri) {
        fragment.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = fragment.requireContext().applicationContext
            val tmp = File(ctx.cacheDir, "single_finish_debug_${System.currentTimeMillis()}.img")
            try {
                UriFileCopy.copyToFile(ctx, uri, tmp)
                val report = photoProcessor.analyzeFinishPhotoDebug(raceId, tmp)
                val text = report.fold(
                    onSuccess = { formatFinishPhotoDebugReport(it) },
                    onFailure = { "Analysis failed: ${it.message ?: "unknown"}" },
                )
                Log.i(logTag, text)
                withContext(Dispatchers.Main) {
                    showScrollableDialog(fragment.getString(R.string.finish_debug_dialog_title), text)
                }
            } catch (t: Throwable) {
                Log.w(logTag, "single finish debug failed", t)
                withContext(Dispatchers.Main) {
                    Toast.makeText(fragment.requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show()
                }
            } finally {
                tmp.delete()
            }
        }
    }

    private fun showScrollableDialog(title: String, message: String) {
        val scroll = ScrollView(fragment.requireContext())
        val tv = TextView(fragment.requireContext()).apply {
            setPadding(48, 32, 48, 32)
            text = message
            textSize = 12f
            movementMethod = ScrollingMovementMethod()
        }
        scroll.addView(tv)
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
