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
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.databinding.DialogRaceEventPhotoViewerBinding
import com.virtualvolunteer.app.ui.util.PreviewImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen pinch-zoom view for race event photos with swipe-left/right navigation.
 * PhotoView + ViewPager2 work together: when zoomed to minimum scale, PhotoView defers
 * horizontal scroll to ViewPager2, enabling natural swipe-to-page behaviour.
 */
class RaceEventPhotoViewerDialogFragment : DialogFragment() {

    private var _binding: DialogRaceEventPhotoViewerBinding? = null
    private val binding get() = _binding!!

    private val paths = mutableListOf<String>()
    private var currentIndex = 0
    private lateinit var pagerAdapter: EventPhotoPageAdapter

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
        val argPaths = requireArguments().getStringArrayList(ARG_PATHS) ?: return dismiss()
        if (argPaths.isEmpty()) return dismiss()

        paths.addAll(argPaths)
        currentIndex = requireArguments().getInt(ARG_INDEX, 0)
            .coerceIn(0, paths.size - 1)

        pagerAdapter = EventPhotoPageAdapter()
        binding.photoPager.adapter = pagerAdapter
        binding.photoPager.setCurrentItem(currentIndex, false)
        updateCounter()

        binding.photoPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = position
                updateCounter()
            }
        })

        binding.btnClose.setOnClickListener { dismiss() }

        binding.btnShare.setOnClickListener {
            val path = paths.getOrNull(currentIndex) ?: return@setOnClickListener
            val f = File(path)
            if (f.exists()) {
                RaceDetailShareHelper.shareImage(requireContext(), f)
            } else {
                Toast.makeText(requireContext(), R.string.race_event_photo_share_failed, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDelete.setOnClickListener {
            val path = paths.getOrNull(currentIndex) ?: return@setOnClickListener
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
                                val removedAt = currentIndex
                                paths.removeAt(removedAt)
                                if (paths.isEmpty()) {
                                    dismiss()
                                    return@withContext
                                }
                                pagerAdapter.notifyItemRemoved(removedAt)
                                val next = removedAt.coerceAtMost(paths.size - 1)
                                if (currentIndex != next) {
                                    binding.photoPager.setCurrentItem(next, false)
                                } else {
                                    currentIndex = next
                                }
                                updateCounter()
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
    }

    private fun updateCounter() {
        if (paths.size > 1) {
            binding.photoCounter.visibility = View.VISIBLE
            binding.photoCounter.text = getString(R.string.photo_counter, currentIndex + 1, paths.size)
        } else {
            binding.photoCounter.visibility = View.GONE
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
        binding.photoPager.adapter = null
        _binding = null
    }

    // -------------------------------------------------------------------------
    // Adapter
    // -------------------------------------------------------------------------

    private inner class EventPhotoPageAdapter : RecyclerView.Adapter<EventPhotoPageAdapter.VH>() {

        override fun getItemCount() = paths.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val photoView = PhotoView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            return VH(photoView)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(paths[position])
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            holder.recycle()
        }

        inner class VH(val photoView: PhotoView) : RecyclerView.ViewHolder(photoView) {
            private var loadJob: Job? = null
            private var loadedBitmap: Bitmap? = null

            fun bind(path: String) {
                loadJob?.cancel()
                loadedBitmap?.recycle()
                loadedBitmap = null
                photoView.setImageBitmap(null)
                loadJob = lifecycleScope.launch(Dispatchers.Default) {
                    val bmp = PreviewImageLoader.loadThumbnailOriented(path, maxSidePx = 3200)
                    if (!isActive) { bmp?.recycle(); return@launch }
                    withContext(Dispatchers.Main) {
                        if (_binding == null) { bmp?.recycle(); return@withContext }
                        loadedBitmap = bmp
                        if (bmp != null) {
                            photoView.setImageBitmap(bmp)
                        } else {
                            Toast.makeText(requireContext(), R.string.race_event_photo_load_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            fun recycle() {
                loadJob?.cancel()
                loadedBitmap?.recycle()
                loadedBitmap = null
                photoView.setImageBitmap(null)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------

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
