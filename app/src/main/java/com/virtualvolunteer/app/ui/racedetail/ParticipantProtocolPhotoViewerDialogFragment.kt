package com.virtualvolunteer.app.ui.racedetail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Full-screen pinch-zoom for one participant protocol photo, with share and optional face box overlay.
 */
class ParticipantProtocolPhotoViewerDialogFragment : DialogFragment() {

    private var _binding: DialogParticipantProtocolPhotoViewerBinding? = null
    private val binding get() = _binding!!

    private var displayedBitmap: Bitmap? = null

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
        val path = requireArguments().getString(ARG_PATH) ?: return dismiss()

        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnShare.setOnClickListener {
            val f = File(path)
            if (f.exists()) {
                RaceDetailShareHelper.shareImage(requireContext(), f)
            } else {
                Toast.makeText(requireContext(), R.string.race_event_photo_share_failed, Toast.LENGTH_SHORT).show()
            }
        }

        loadPhoto(path)
    }

    private fun loadPhoto(path: String) {
        binding.photoView.setImageBitmap(null)
        displayedBitmap?.recycle()
        displayedBitmap = null
        val appCtx = requireContext().applicationContext
        val participantId = requireArguments().getLong(ARG_PARTICIPANT_ID, 0L)
        val raceId = requireArguments().getString(ARG_RACE_ID).orEmpty()
        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
        lifecycleScope.launch(Dispatchers.Default) {
            val bmp = PreviewImageLoader.loadThumbnailOriented(path, maxSidePx = 3200)
            if (bmp == null) {
                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        Toast.makeText(requireContext(), R.string.race_event_photo_load_failed, Toast.LENGTH_SHORT).show()
                    }
                    dismiss()
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
                val sx = bmp.width.toFloat() / manifestEntry.visionWidth
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
            withContext(Dispatchers.Main) {
                if (_binding == null) {
                    toShow.recycle()
                    return@withContext
                }
                displayedBitmap = toShow
                binding.photoView.setImageBitmap(toShow)
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
        private const val ARG_PATH = "path"
        private const val ARG_PARTICIPANT_ID = "participantId"
        private const val ARG_RACE_ID = "raceId"
        private const val TAG = "ParticipantProtocolPhotoViewer"

        /**
         * @param participantHashId protocol row id in this race when known; used to pick the correct face
         * among several on the same frame via embedding match. Pass 0 when unknown (legacy: largest face).
         * @param raceId when set with [participantHashId], [face_crop_manifest.xml] supplies an exact box
         * without re-detecting faces.
         */
        fun show(
            fm: FragmentManager,
            absolutePath: String,
            participantHashId: Long = 0L,
            raceId: String = "",
        ) {
            if (absolutePath.isBlank()) return
            ParticipantProtocolPhotoViewerDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PATH, absolutePath)
                    putLong(ARG_PARTICIPANT_ID, participantHashId)
                    putString(ARG_RACE_ID, raceId)
                }
            }.show(fm, TAG)
        }
    }
}

/**
 * When several faces are present and we have stored participant vectors, embed each crop and pick
 * the face with the best cosine match to any stored vector; otherwise the largest face by bbox area.
 */
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
