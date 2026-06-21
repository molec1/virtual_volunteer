package com.virtualvolunteer.app.ui.camera

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.hardware.SensorManager
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.virtualvolunteer.app.R
import com.virtualvolunteer.app.VirtualVolunteerApp
import com.virtualvolunteer.app.databinding.FragmentCameraCaptureBinding
import com.virtualvolunteer.app.domain.RacePhotoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app CameraX capture: multiple stills without leaving the screen; runs existing ingest pipelines.
 */
class CameraCaptureFragment : Fragment() {

    private var _binding: FragmentCameraCaptureBinding? = null
    private val binding get() = _binding!!

    private var previousRequestedOrientation: Int? = null

    private val raceId: String
        get() = requireArguments().getString(ARG_RACE_ID) ?: error("raceId missing")

    private val captureMode: String
        get() = requireArguments().getString(ARG_CAPTURE_MODE) ?: MODE_START_PHOTO

    private var cameraExecutor: ExecutorService? = null
    private var imageCapture: ImageCapture? = null
    private var lastAppliedGuideHeightPx: Int? = null
    private var previewLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    // Retained across rebinds; cleared in onDestroyView.
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: androidx.camera.core.Camera? = null
    private var cameraOptions: List<RearCameraOption> = emptyList()
    private var selectedCameraIndex: Int = 0

    /**
     * Physical device rotation (from [OrientationEventListener]) for JPEG EXIF via CameraX
     * [ImageCapture.setTargetRotation]. The capture UI stays portrait-locked, so we cannot use
     * [android.view.Display.getRotation] for this.
     */
    private var captureTargetRotation: Int = Surface.ROTATION_0

    private var orientationListener: OrientationEventListener? = null

    /**
     * True from capture request through file write (and, for start photos, through ingest). Blocks
     * overlapping CameraX saves; finish photos enqueue analysis on the app and clear this as soon as
     * the file is saved.
     */
    private val capturePipelineBusy = AtomicBoolean(false)

    /**
     * True while the capture button receives a touch down without UP/CANCEL yet.
     * Main thread only. Used with [fingerDownAtElapsedMs] so a fast tap (save completes before UP)
     * does not look like a long press — see [scheduleNextCaptureIfFingerStillHeldForBurst].
     */
    private var fingerDownOnCaptureButton = false
    private var fingerDownAtElapsedMs: Long = 0L

    private val chainAfterHoldRunnable = Runnable {
        if (!fingerDownOnCaptureButton || !isAdded || imageCapture == null) return@Runnable
        capturePhoto()
    }

    private lateinit var photoProcessor: RacePhotoProcessor

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(requireContext(), R.string.permission_camera_required, Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        photoProcessor = (requireActivity().application as VirtualVolunteerApp).racePhotoProcessor
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCameraCaptureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    findNavController().popBackStack()
                }
            },
        )

        binding.cameraHint.text = when (captureMode) {
            MODE_FINISH_PHOTO -> getString(R.string.camera_capture_finish_hint)
            MODE_VOLUNTEER_PHOTO -> getString(R.string.camera_capture_volunteer_hint)
            else -> getString(R.string.camera_capture_start_hint)
        }
        binding.cameraStatus.text = getString(R.string.camera_capture_status_ready)

        binding.btnCameraDone.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnCameraCapture.setOnClickListener {
            fingerDownOnCaptureButton = false
            cancelDeferredContinuousChain()
            capturePhoto()
        }
        binding.btnCameraCapture.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fingerDownOnCaptureButton = true
                    fingerDownAtElapsedMs = SystemClock.elapsedRealtime()
                    cancelDeferredContinuousChain()
                    capturePhoto()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    fingerDownOnCaptureButton = false
                    cancelDeferredContinuousChain()
                }
            }
            true
        }

        binding.btnLensSelect.setOnClickListener {
            switchToNextCamera()
        }

        orientationListener = object : OrientationEventListener(
            requireContext(),
            SensorManager.SENSOR_DELAY_NORMAL,
        ) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                // Must match Android CameraX docs — OrientationEventListener degrees map to
                // Surface rotation differently than naive buckets; swapping 90↔270 breaks landscape EXIF.
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (rotation == captureTargetRotation) return
                captureTargetRotation = rotation
                imageCapture?.targetRotation = rotation
            }
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        previewLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            updateHeadSizeGuide()
        }.also {
            binding.cameraPreview.viewTreeObserver.addOnGlobalLayoutListener(it)
        }
    }

    override fun onResume() {
        super.onResume()
        val host = requireActivity()
        if (previousRequestedOrientation == null) {
            previousRequestedOrientation = host.requestedOrientation
        }
        host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        orientationListener?.takeIf { it.canDetectOrientation() }?.enable()
    }

    override fun onPause() {
        orientationListener?.disable()
        val host = activity
        val prev = previousRequestedOrientation
        if (host != null && prev != null) {
            host.requestedOrientation = prev
        }
        super.onPause()
    }

    /**
     * Acquires [ProcessCameraProvider], enumerates rear cameras on first call (populating
     * [cameraOptions] and [selectedCameraIndex]), then delegates to [bindCamera].
     */
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener(
            {
                val provider = future.get()
                cameraProvider = provider
                if (cameraOptions.isEmpty()) {
                    val app = requireActivity().application as VirtualVolunteerApp
                    cameraOptions = RearCameraSelector.buildOptions(
                        context = requireContext(),
                        cameraProvider = provider,
                        pipelineLog = { app.appendPipelineLog(it) },
                    )
                    selectedCameraIndex = RearCameraSelector.defaultIndex(cameraOptions)
                    updateLensButton()
                }
                bindCamera(provider)
            },
            ContextCompat.getMainExecutor(requireContext()),
        )
    }

    /**
     * Binds Preview + ImageCapture to [provider] using the currently selected [RearCameraOption].
     *
     * When the selected option's selector fails to bind (e.g. the camera is not accessible on this
     * device through CameraX), it is silently dropped from [cameraOptions] and the next available
     * real option is tried. If all real options are exhausted the system default back camera is
     * used as the final fallback.
     */
    private fun bindCamera(provider: ProcessCameraProvider) {
        val b = _binding ?: return
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(b.cameraPreview.surfaceProvider)
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(captureTargetRotation)
            .build()
        val ic = imageCapture!!

        // Build an ordered list of selectors to try: selected option first, then the rest, then
        // DEFAULT_BACK_CAMERA as a guaranteed last resort.
        val orderedOptions = buildList {
            cameraOptions.getOrNull(selectedCameraIndex)?.let { add(it) }
            cameraOptions.forEachIndexed { i, opt ->
                if (i != selectedCameraIndex && opt.zoomRatio == null) add(opt)
            }
        }
        val failedIds = mutableListOf<String>()

        for (option in orderedOptions) {
            try {
                provider.unbindAll()
                val cam = provider.bindToLifecycle(viewLifecycleOwner, option.selector, preview, ic)
                boundCamera = cam
                updateHeadSizeGuide()
                setupZoomOptionsIfNeeded(cam)
                return
            } catch (e: Exception) {
                Log.w(TAG, "bind failed for ${option.label}: ${e.message}")
                failedIds += option.label
            }
        }

        // All enumerated options failed — remove them from the list, fall back to DEFAULT.
        if (failedIds.isNotEmpty()) {
            Log.w(TAG, "dropping unbindable options: $failedIds")
            cameraOptions = cameraOptions.filter { it.label !in failedIds }
            selectedCameraIndex = 0
            updateLensButton()
        }

        try {
            provider.unbindAll()
            val cam = provider.bindToLifecycle(
                viewLifecycleOwner,
                androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                ic,
            )
            boundCamera = cam
            updateHeadSizeGuide()
            setupZoomOptionsIfNeeded(cam)
        } catch (e: Exception) {
            Log.e(TAG, "DEFAULT_BACK_CAMERA bind also failed", e)
            Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Cycles [selectedCameraIndex] to the next option.
     *
     * For **synthetic** zoom options the camera stays bound; only the zoom ratio changes.
     * For **real** selector options the camera is fully rebound.
     */
    private fun switchToNextCamera() {
        if (cameraOptions.size <= 1) return
        selectedCameraIndex = (selectedCameraIndex + 1) % cameraOptions.size
        updateLensButton()
        val option = cameraOptions[selectedCameraIndex]
        if (option.zoomRatio != null) {
            boundCamera?.cameraControl?.setZoomRatio(option.zoomRatio)
        } else {
            val provider = cameraProvider ?: return
            bindCamera(provider)
        }
    }

    /**
     * When Camera2 enumeration found only one rear camera, observes the zoom-state LiveData once
     * to discover the device's max zoom ratio and, if it is ≥ 2×, replaces [cameraOptions] with
     * synthetic zoom options (1×/2×/5×) backed by the already-bound selector.
     *
     * No-op when multiple real options already exist.
     */
    private fun setupZoomOptionsIfNeeded(camera: androidx.camera.core.Camera) {
        if (cameraOptions.size >= 2) return
        camera.cameraInfo.zoomState.observe(viewLifecycleOwner) { zoomState ->
            if (cameraOptions.size >= 2) return@observe
            val maxZoom = zoomState?.maxZoomRatio ?: return@observe
            val synthetic = RearCameraSelector.buildZoomOptions(cameraOptions, maxZoom)
            if (synthetic.size < 2) return@observe
            cameraOptions = synthetic
            selectedCameraIndex = 0
            camera.cameraControl.setZoomRatio(1.0f)
            updateLensButton()
        }
    }

    /**
     * Shows the lens selector button when at least two options are available (real or synthetic),
     * and updates its label to match the currently selected option.
     */
    private fun updateLensButton() {
        val b = _binding ?: return
        val options = cameraOptions
        val hasMultiple = options.size >= 2 && options.any { it.equivMm > 0 || it.zoomRatio != null }
        if (!hasMultiple) {
            b.btnLensSelect.visibility = View.GONE
            return
        }
        b.btnLensSelect.visibility = View.VISIBLE
        b.btnLensSelect.text = options.getOrNull(selectedCameraIndex)?.label ?: "—"
        b.btnLensSelect.contentDescription =
            getString(R.string.camera_lens_select_cd, b.btnLensSelect.text)
    }

    private fun cancelDeferredContinuousChain() {
        _binding?.btnCameraCapture?.removeCallbacks(chainAfterHoldRunnable)
    }

    /**
     * After a successful save, take another shot only if the finger is still down *and* it has been
     * down long enough to count as an intentional hold (not a tap whose UP is still in the queue).
     */
    private fun scheduleNextCaptureIfFingerStillHeldForBurst() {
        if (!fingerDownOnCaptureButton || !isAdded || imageCapture == null) return
        val host = _binding?.btnCameraCapture ?: return
        host.removeCallbacks(chainAfterHoldRunnable)
        val heldMs = SystemClock.elapsedRealtime() - fingerDownAtElapsedMs
        if (heldMs >= HOLD_MS_BEFORE_CONTINUOUS_CHAIN) {
            capturePhoto()
        } else {
            host.postDelayed(chainAfterHoldRunnable, HOLD_MS_BEFORE_CONTINUOUS_CHAIN - heldMs)
        }
    }

    private fun capturePhoto() {
        cancelDeferredContinuousChain()
        if (!capturePipelineBusy.compareAndSet(false, true)) return
        val capture = imageCapture ?: run {
            capturePipelineBusy.set(false)
            return
        }
        val file = when (captureMode) {
            MODE_FINISH_PHOTO -> RacePhotoProcessor.defaultOutputFinishPhotoFile(requireContext(), raceId)
            MODE_VOLUNTEER_PHOTO -> RacePhotoProcessor.defaultOutputVolunteerPhotoFile(requireContext(), raceId)
            else -> RacePhotoProcessor.defaultOutputStartPhotoFile(requireContext(), raceId)
        }
        val appForIngest = requireActivity().application as VirtualVolunteerApp
        binding.cameraStatus.text = getString(R.string.camera_capture_status_saving)
        // Do not disable the button here: disabling can prevent ACTION_UP/ACTION_CANCEL delivery,
        // leaving the "finger down" state stuck and causing unintended continuous shooting.

        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        val executor = cameraExecutor ?: ContextCompat.getMainExecutor(requireContext())
        capture.takePicture(
            opts,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    ContextCompat.getMainExecutor(appForIngest).execute {
                        if (!isAdded || view == null) {
                            capturePipelineBusy.set(false)
                            return@execute
                        }
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                when (captureMode) {
                                    MODE_FINISH_PHOTO -> {
                                        appForIngest.appendPipelineLog("—— CameraX finish (${file.name}) ——")
                                        appForIngest.finishPhotoAnalysisQueue.enqueue(raceId, file)
                                        appForIngest.appendPipelineLog("CameraX finish saved; analysis queued")
                                    }
                                    MODE_VOLUNTEER_PHOTO -> {
                                        _binding?.cameraStatus?.text =
                                            getString(R.string.camera_capture_status_processing)
                                        withContext(Dispatchers.IO) {
                                            photoProcessor.ingestVolunteerPhoto(raceId, file).onFailure { t ->
                                                Log.w(TAG, "ingestVolunteerPhoto failed", t)
                                            }
                                        }
                                    }
                                    else -> {
                                        _binding?.cameraStatus?.text =
                                            getString(R.string.camera_capture_status_processing)
                                        withContext(Dispatchers.IO) {
                                            photoProcessor.ingestStartPhoto(raceId, file).onFailure { t ->
                                                Log.w(TAG, "ingestStartPhoto failed", t)
                                            }
                                        }
                                    }
                                }
                            } finally {
                                capturePipelineBusy.set(false)
                                withContext(Dispatchers.Main) {
                                    _binding?.let { v ->
                                        v.cameraStatus.text = getString(R.string.camera_capture_status_saved)
                                        scheduleNextCaptureIfFingerStillHeldForBurst()
                                    }
                                }
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "capture failed", exception)
                    ContextCompat.getMainExecutor(appForIngest).execute {
                        capturePipelineBusy.set(false)
                        _binding?.let { v ->
                            v.cameraStatus.text = getString(R.string.camera_capture_status_ready)
                        }
                        if (_binding != null) {
                            Toast.makeText(appForIngest, R.string.import_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
        )
    }

    /**
     * Shows a minimal head outline in the top-right corner, sized so that its height corresponds
     * to [MIN_FACE_HEIGHT_IN_OUTPUT_PX] pixels in the *final* captured image.
     *
     * Computation:
     *  - coef = previewHeightPx / photoHeightPx
     *  - guideHeightOnScreenPx = MIN_FACE_HEIGHT_IN_OUTPUT_PX * coef
     */
    private fun updateHeadSizeGuide() {
        val b = _binding ?: return
        val capture = imageCapture ?: return
        val previewHeightPx = b.cameraPreview.height
        if (previewHeightPx <= 0) return

        val outputSize = capture.resolutionInfo?.resolution ?: run {
            b.headSizeGuide.visibility = View.GONE
            return
        }

        val rotationDegrees = capture.targetRotation.let { rot ->
            when (rot) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        }
        val photoHeightPx = if (rotationDegrees % 180 == 0) outputSize.height else outputSize.width
        if (photoHeightPx <= 0) return

        val coef = previewHeightPx.toFloat() / photoHeightPx.toFloat()
        var guideHeightPx = (MIN_FACE_HEIGHT_IN_OUTPUT_PX * coef).roundToInt()

        val minPx = (16f * resources.displayMetrics.density).roundToInt()
        val maxPx = (previewHeightPx * 0.35f).roundToInt()
        guideHeightPx = guideHeightPx.coerceIn(minPx, maxPx)

        if (lastAppliedGuideHeightPx == guideHeightPx) {
            if (b.headSizeGuide.visibility != View.VISIBLE) b.headSizeGuide.visibility = View.VISIBLE
            return
        }
        lastAppliedGuideHeightPx = guideHeightPx

        b.headSizeGuide.visibility = View.VISIBLE
        b.headSizeGuide.adjustViewBounds = true
        b.headSizeGuide.maxHeight = guideHeightPx
        b.headSizeGuide.layoutParams = b.headSizeGuide.layoutParams.apply {
            height = guideHeightPx
            width = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        b.headSizeGuide.requestLayout()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        orientationListener?.disable()
        orientationListener = null
        fingerDownOnCaptureButton = false
        cancelDeferredContinuousChain()
        previewLayoutListener?.let { listener ->
            _binding?.cameraPreview?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
        }
        previewLayoutListener = null
        cameraExecutor?.shutdown()
        cameraExecutor = null
        imageCapture = null
        boundCamera = null
        cameraProvider = null
        cameraOptions = emptyList()
        lastAppliedGuideHeightPx = null
        previousRequestedOrientation = null
        _binding = null
    }

    companion object {
        const val ARG_RACE_ID = "raceId"
        /** Must match [nav_graph] `<argument android:name="captureMode" />`. */
        const val ARG_CAPTURE_MODE = "captureMode"
        const val MODE_START_PHOTO = "START_PHOTO"
        const val MODE_FINISH_PHOTO = "FINISH_PHOTO"
        const val MODE_VOLUNTEER_PHOTO = "VOLUNTEER_PHOTO"
        private const val TAG = "CameraCapture"
        /** Shorter taps must not chain; finish saves often complete before ACTION_UP. */
        private const val HOLD_MS_BEFORE_CONTINUOUS_CHAIN = 400L
        private const val MIN_FACE_HEIGHT_IN_OUTPUT_PX = 300
    }
}
