package com.virtualvolunteer.app.ui.racedetail

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.databinding.DialogRaceEventPhotoViewerBinding
import com.virtualvolunteer.app.ui.util.PreviewImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen pinch-zoom view for one race event photo, with share and delete.
 */
class RaceEventPhotoViewerDialogFragment : DialogFragment() {

    private var _binding: DialogRaceEventPhotoViewerBinding? = null
    private val binding get() = _binding!!

    private var displayedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.ThemeOverlay_VirtualVolunteer_FullScreenPhoto)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogRaceEventPhotoViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val raceId = requireArguments().getString(ARG_RACE_ID) ?: return dismiss()
        val paths = requireArguments().getStringArrayList(ARG_PATHS) ?: return dismiss()
        val index = requireArguments().getInt(ARG_INDEX, 0).coerceIn(0, (paths.size - 1).coerceAtLeast(0))
        val path = paths.getOrNull(index) ?: return dismiss()

        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnShare.setOnClickListener {
            val f = File(path)
            if (f.exists()) {
                RaceDetailShareHelper.shareImage(requireContext(), f)
            } else {
                Toast.makeText(requireContext(), R.string.race_event_photo_share_failed, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.race_event_photo_delete_title)
                .setMessage(R.string.race_event_photo_delete_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
                        val ok = repo.deleteRaceEventPhoto(raceId, path)
                        withContext(Dispatchers.Main) {
                            if (!isAdded) return@withContext
                            if (ok) {
                                requireActivity().supportFragmentManager.setFragmentResult(
                                    REQUEST_KEY,
                                    Bundle().apply { putBoolean(EXTRA_LIST_CHANGED, true) },
                                )
                                dismiss()
                            } else {
                                Toast.makeText(
                                    requireContext(),
                                    R.string.race_event_photo_delete_failed,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
                .show()
        }

        loadPhoto(path)
    }

    private fun loadPhoto(path: String) {
        binding.photoView.setImageBitmap(null)
        displayedBitmap?.recycle()
        displayedBitmap = null
        lifecycleScope.launch(Dispatchers.Default) {
            val bmp = PreviewImageLoader.loadThumbnailOriented(path, maxSidePx = 3200)
            withContext(Dispatchers.Main) {
                if (_binding == null) {
                    bmp?.recycle()
                    return@withContext
                }
                displayedBitmap = bmp
                if (bmp != null) {
                    binding.photoView.setImageBitmap(bmp)
                } else {
                    Toast.makeText(requireContext(), R.string.race_event_photo_load_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        displayedBitmap?.recycle()
        displayedBitmap = null
        binding.photoView.setImageBitmap(null)
        _binding = null
    }

    companion object {
        const val REQUEST_KEY = "RaceEventPhotoViewer.result"
        const val EXTRA_LIST_CHANGED = "listChanged"
        private const val ARG_RACE_ID = "raceId"
        private const val ARG_PATHS = "paths"
        private const val ARG_INDEX = "index"
        private const val TAG = "RaceEventPhotoViewer"

        fun show(fm: FragmentManager, raceId: String, paths: List<String>, startIndex: Int) {
            if (paths.isEmpty()) return
            val f = RaceEventPhotoViewerDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_RACE_ID, raceId)
                    putStringArrayList(ARG_PATHS, ArrayList(paths))
                    putInt(ARG_INDEX, startIndex)
                }
            }
            f.show(fm, TAG)
        }
    }
}
