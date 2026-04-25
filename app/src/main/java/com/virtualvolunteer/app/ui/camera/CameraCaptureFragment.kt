package com.virtualvolunteer.app.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
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
import com.virtualvolunteer.app.domain.RacePhotoProcessorFactory
import com.virtualvolunteer.app.domain.face.MlKitFaceDetector
import com.virtualvolunteer.app.domain.face.TfliteFaceEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app CameraX capture: multiple stills without leaving the screen; runs existing ingest pipelines.
 */
class CameraCaptureFragment : Fragment() {

    private var _binding: FragmentCameraCaptureBinding? = null
    private val binding get() = _binding!!

    private val raceId: String
        get() = requireArguments().getString(ARG_RACE_ID) ?: error("raceId missing")

    private val captureMode: String
        get() = requireArguments().getString(ARG_CAPTURE_MODE) ?: MODE_START_PHOTO

    private var cameraExecutor: ExecutorService? = null
    private var imageCapture: ImageCapture? = null

    /** True from capture request through ingest; blocks overlapping captures and parallel TFLite use from this screen. */
    private val capturePipelineBusy = AtomicBoolean(false)

    private lateinit var photoProcessor: RacePhotoProcessor
    private lateinit var faceDetector: MlKitFaceDetector
    private lateinit var faceEmbedder: TfliteFaceEmbedder

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
        val appContext = requireActivity().applicationContext
        val repo = (requireActivity().application as VirtualVolunteerApp).raceRepository
        val stack = RacePhotoProcessorFactory.createStack(appContext, repo)
        faceDetector = stack.faceDetector
        faceEmbedder = stack.faceEmbedder
        photoProcessor = stack.processor
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCameraCaptureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.cameraHint.text = when (captureMode) {
            MODE_FINISH_PHOTO -> getString(R.string.camera_capture_finish_hint)
            else -> getString(R.string.camera_capture_start_hint)
        }
        binding.cameraStatus.text = getString(R.string.camera_capture_status_ready)

        binding.btnCameraDone.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnCameraCapture.setOnClickListener { capturePhoto() }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                val b = _binding ?: return@addListener
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(b.cameraPreview.getSurfaceProvider())
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        viewLifecycleOwner,
                        selector,
                        preview,
                        imageCapture,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "bind failed", e)
                    Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show()
                }
            },
            ContextCompat.getMainExecutor(requireContext()),
        )
    }

    private fun capturePhoto() {
        if (!capturePipelineBusy.compareAndSet(false, true)) return
        val capture = imageCapture ?: run {
            capturePipelineBusy.set(false)
            return
        }
        val file = when (captureMode) {
            MODE_FINISH_PHOTO -> RacePhotoProcessor.defaultOutputFinishPhotoFile(requireContext(), raceId)
            else -> RacePhotoProcessor.defaultOutputStartPhotoFile(requireContext(), raceId)
        }
        val appForIngest = requireActivity().application as VirtualVolunteerApp
        binding.cameraStatus.text = getString(R.string.camera_capture_status_saving)
        binding.btnCameraCapture.isEnabled = false

        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        val executor = cameraExecutor ?: ContextCompat.getMainExecutor(requireContext())
        capture.takePicture(
            opts,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // Callback runs on cameraExecutor; hop to main before viewLifecycleOwner / binding.
                    ContextCompat.getMainExecutor(appForIngest).execute {
                        if (!isAdded || view == null) {
                            capturePipelineBusy.set(false)
                            return@execute
                        }
                        // viewLifecycleOwner: cancel when the view is gone; never touch binding after destroyView.
                        viewLifecycleOwner.lifecycleScope.launch {
                            _binding?.cameraStatus?.text = getString(R.string.camera_capture_status_processing)
                            try {
                                withContext(Dispatchers.IO) {
                                    val app = appForIngest
                                    when (captureMode) {
                                        MODE_FINISH_PHOTO -> {
                                            app.appendPipelineLog("—— CameraX finish (${file.name}) ——")
                                            val ingest = photoProcessor.ingestFinishPhoto(raceId, file)
                                            ingest.onFailure { t ->
                                                Log.w(TAG, "ingestFinishPhoto failed", t)
                                                app.appendPipelineLog("CAMERA_FINISH_INGEST_FAILED err=${t.message}")
                                            }
                                            app.raceRepository.updateLastProcessedPhoto(raceId, file.absolutePath)
                                            app.appendPipelineLog("CameraX finish capture done")
                                        }
                                        else -> {
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
                                        v.btnCameraCapture.isEnabled = true
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
                            v.btnCameraCapture.isEnabled = true
                        }
                        if (_binding != null) {
                            Toast.makeText(appForIngest, R.string.import_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor?.shutdown()
        cameraExecutor = null
        imageCapture = null
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::faceDetector.isInitialized) faceDetector.close()
        if (this::faceEmbedder.isInitialized) faceEmbedder.close()
    }

    companion object {
        const val ARG_RACE_ID = "raceId"
        /** Must match [nav_graph] `<argument android:name="captureMode" />`. */
        const val ARG_CAPTURE_MODE = "captureMode"
        const val MODE_START_PHOTO = "START_PHOTO"
        const val MODE_FINISH_PHOTO = "FINISH_PHOTO"
        private const val TAG = "CameraCapture"
    }
}
