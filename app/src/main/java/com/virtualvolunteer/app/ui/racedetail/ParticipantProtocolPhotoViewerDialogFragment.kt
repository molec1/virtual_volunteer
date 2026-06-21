package com.virtualvolunteer.app.ui.racedetail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.util.containsKey
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.face.Face
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.data.files.FaceCropManifestDisk
import com.virtualvolunteer.app.databinding.DialogParticipantProtocolPhotoViewerBinding
import com.virtualvolunteer.app.domain.face.EmbeddingMath
import com.virtualvolunteer.app.domain.face.MlKitFaceDetector
import com.virtualvolunteer.app.domain.face.TfliteFaceEmbedder
import com.virtualvolunteer.app.ui.util.PreviewImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Full-screen pinch-zoom for participant protocol photos with swipe-left/right navigation.
 * Face-box annotation and detach-embedding state are resolved lazily per page.
 */
class ParticipantProtocolPhotoViewerDialogFragment : DialogFragment() {

    private var _binding: DialogParticipantProtocolPhotoViewerBinding? = null
    private val binding get() = _binding!!

    /** embedding IDs keyed by pager position; null entry = no detachable embedding for that page */
    private val embeddingIdByPosition = SparseArray<Long?>()
    private var currentIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.ThemeOverlay_VirtualVolunteer_FullScreenPhoto)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogParticipantProtocolPhotoViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val paths = requireArguments().getStringArrayList(ARG_PATHS)
            ?.takeIf { it.isNotEmpty() }
            ?: run {
                val single = requireArguments().getString(ARG_PATH)
                if (single != null) arrayListOf(single) else null
            }
            ?: return dismiss()

        currentIndex = requireArguments().getInt(ARG_INDEX, 0).coerceIn(0, paths.size - 1)

        val participantId = requireArguments().getLong(ARG_PARTICIPANT_ID, 0L)
        val raceId = requireArguments().getString(ARG_RACE_ID).orEmpty()

        val adapter = AnnotatedPhotoPageAdapter(paths, participantId, raceId)
        binding.photoPager.adapter = adapter
        binding.photoPager.setCurrentItem(currentIndex, false)
        updateCounter(currentIndex, paths.size)

        binding.photoPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = position
                updateCounter(position, paths.size)
                refreshDetachButton(position)
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

        binding.btnDetach.setOnClickListener {
            val embId = embeddingIdByPosition[currentIndex] ?: return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.detach_embedding_confirm_title)
                .setMessage(R.string.detach_embedding_confirm_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.detach_embedding_confirm_action) { _, _ ->
                    val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            repo.detachEmbeddingFromGroup(embId)
                            withContext(Dispatchers.Main) {
                                if (_binding != null) {
                                    Toast.makeText(requireContext(), R.string.detach_embedding_confirm_action, Toast.LENGTH_SHORT).show()
                                    dismiss()
                                }
                            }
                        } catch (t: Throwable) {
                            withContext(Dispatchers.Main) {
                                if (_binding != null) {
                                    Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
                .show()
        }

        // Start with detach hidden; will show when page loading resolves the embedding id
        binding.btnDetach.visibility = View.GONE
    }

    private fun updateCounter(index: Int, total: Int) {
        if (total > 1) {
            binding.photoCounter.visibility = View.VISIBLE
            binding.photoCounter.text = getString(R.string.photo_counter, index + 1, total)
        } else {
            binding.photoCounter.visibility = View.GONE
        }
    }

    /** Called by the adapter when the embedding id for a page has been resolved. */
    internal fun onEmbeddingIdResolved(position: Int, embId: Long?) {
        embeddingIdByPosition.put(position, embId)
        if (position == currentIndex) refreshDetachButton(position)
    }

    private fun refreshDetachButton(position: Int) {
        if (!embeddingIdByPosition.containsKey(position)) {
            // Not yet loaded – hide until resolved
            binding.btnDetach.visibility = View.GONE
            return
        }
        val embId = embeddingIdByPosition[position]
        binding.btnDetach.visibility = if (embId != null) View.VISIBLE else View.GONE
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

    private inner class AnnotatedPhotoPageAdapter(
        private val paths: List<String>,
        private val participantId: Long,
        private val raceId: String,
    ) : RecyclerView.Adapter<AnnotatedPhotoPageAdapter.VH>() {

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
            holder.bind(paths[position], position)
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            holder.recycle()
        }

        inner class VH(val photoView: PhotoView) : RecyclerView.ViewHolder(photoView) {
            private var loadJob: Job? = null
            private var loadedBitmap: Bitmap? = null

            fun bind(path: String, position: Int) {
                loadJob?.cancel()
                loadedBitmap?.recycle()
                loadedBitmap = null
                photoView.setImageBitmap(null)

                val appCtx = photoView.context.applicationContext
                val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository

                loadJob = lifecycleScope.launch(Dispatchers.Default) {
                    val embId = if (participantId > 0L) {
                        repo.findEmbeddingIdForParticipantSourcePhoto(participantId, path)
                    } else {
                        null
                    }
                    val bmp = PreviewImageLoader.loadThumbnailOriented(path, maxSidePx = 3200)
                    if (!isActive) { bmp?.recycle(); return@launch }

                    if (bmp == null) {
                        withContext(Dispatchers.Main) {
                            if (_binding == null) return@withContext
                            onEmbeddingIdResolved(position, embId)
                            Toast.makeText(requireContext(), R.string.race_event_photo_load_failed, Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    val manifestEntry = if (raceId.isNotBlank() && participantId > 0L) {
                        FaceCropManifestDisk.findEntryForPhoto(appCtx, raceId, path, participantId)
                    } else {
                        null
                    }
                    val validManifest = manifestEntry != null &&
                        manifestEntry.visionWidth > 0 &&
                        manifestEntry.visionHeight > 0 &&
                        manifestEntry.right > manifestEntry.left &&
                        manifestEntry.bottom > manifestEntry.top

                    val toShow = if (validManifest) {
                        val sx = bmp.width.toFloat() / manifestEntry!!.visionWidth
                        val sy = bmp.height.toFloat() / manifestEntry.visionHeight
                        annotateRectFromManifest(bmp, manifestEntry, sx, sy)
                    } else {
                        val storedVectors = if (participantId > 0L) {
                            repo.listParticipantEmbeddingFloatVectors(participantId)
                        } else {
                            emptyList()
                        }
                        val detector = MlKitFaceDetector()
                        try {
                            val faces = detector.detectFaces(bmp)
                            val faceToDraw = resolveFaceToHighlight(bmp, faces, storedVectors, detector, appCtx)
                            annotateFaceOrKeep(bmp, faceToDraw)
                        } finally {
                            detector.close()
                        }
                    }

                    if (!isActive) { toShow.recycle(); return@launch }
                    withContext(Dispatchers.Main) {
                        if (_binding == null) { toShow.recycle(); return@withContext }
                        onEmbeddingIdResolved(position, embId)
                        loadedBitmap = toShow
                        photoView.setImageBitmap(toShow)
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
        private const val ARG_PATH = "path"
        private const val ARG_PATHS = "paths"
        private const val ARG_INDEX = "index"
        private const val ARG_PARTICIPANT_ID = "participantId"
        private const val ARG_RACE_ID = "raceId"
        private const val TAG = "ParticipantProtocolPhotoViewer"

        /**
         * Open the viewer for a list of photos, starting at [startIndex].
         *
         * @param participantHashId protocol row id; used to pick the correct face box.
         * @param raceId when set with [participantHashId], the manifest supplies exact box coords.
         */
        fun show(
            fm: FragmentManager,
            paths: List<String>,
            startIndex: Int = 0,
            participantHashId: Long = 0L,
            raceId: String = "",
        ) {
            if (paths.isEmpty()) return
            ParticipantProtocolPhotoViewerDialogFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_PATHS, ArrayList(paths))
                    putInt(ARG_INDEX, startIndex.coerceIn(0, paths.size - 1))
                    putLong(ARG_PARTICIPANT_ID, participantHashId)
                    putString(ARG_RACE_ID, raceId)
                }
            }.show(fm, TAG)
        }
    }
}

// -----------------------------------------------------------------------------
// Face annotation helpers (file-private, unchanged from original)
// -----------------------------------------------------------------------------

private fun resolveFaceToHighlight(
    bmp: Bitmap,
    faces: List<Face>,
    storedVectors: List<FloatArray>,
    detector: MlKitFaceDetector,
    appContext: Context,
): Face? {
    if (faces.isEmpty()) return null
    if (faces.size == 1) return faces.first()
    if (storedVectors.isEmpty()) {
        return faces.maxBy { it.boundingBox.width() * it.boundingBox.height() }
    }
    val embedder = TfliteFaceEmbedder(appContext)
    try {
        return pickBestMatchingFace(bmp, faces, storedVectors, detector, embedder)
            ?: faces.maxBy { it.boundingBox.width() * it.boundingBox.height() }
    } finally {
        embedder.close()
    }
}

private fun pickBestMatchingFace(
    bmp: Bitmap,
    faces: List<Face>,
    storedVectors: List<FloatArray>,
    detector: MlKitFaceDetector,
    embedder: TfliteFaceEmbedder,
): Face? {
    var bestFace: Face? = null
    var bestScore = -1f
    for (face in faces) {
        val crop = detector.cropFace(bmp, face) ?: continue
        try {
            val q = embedder.embed(crop)
            var faceBest = -1f
            for (s in storedVectors) {
                if (s.size != q.size) continue
                val c = EmbeddingMath.cosineSimilarity(q, s)
                if (c > faceBest) faceBest = c
            }
            if (faceBest > bestScore) {
                bestScore = faceBest
                bestFace = face
            }
        } finally {
            crop.recycle()
        }
    }
    return bestFace
}

private fun annotateFaceOrKeep(bmp: Bitmap, face: Face?): Bitmap {
    if (face == null) return bmp
    val copy = bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return bmp
    val stroke = max(4f, bmp.width * 0.004f)
    val paint = faceOverlayPaint(stroke)
    Canvas(copy).drawRect(face.boundingBox, paint)
    bmp.recycle()
    return copy
}

private fun annotateRectFromManifest(
    bmp: Bitmap,
    entry: FaceCropManifestDisk.Entry,
    scaleX: Float,
    scaleY: Float,
): Bitmap {
    val copy = bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return bmp
    val stroke = max(4f, bmp.width * 0.004f)
    val paint = faceOverlayPaint(stroke)
    val rect = RectF(
        entry.left * scaleX,
        entry.top * scaleY,
        entry.right * scaleX,
        entry.bottom * scaleY,
    )
    Canvas(copy).drawRect(rect, paint)
    bmp.recycle()
    return copy
}

private fun faceOverlayPaint(stroke: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = stroke
    color = Color.argb(230, 46, 204, 113)
}
