package com.kylescholz.otterspotter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kylescholz.otterspotter.databinding.ActivityMainBinding
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.kylescholz.otterspotter.databinding.SheetSettingsMenuBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.io.File
import com.kylescholz.otterspotter.location.ExifLocationHelper
import com.kylescholz.otterspotter.location.OtterLocationManager
import com.kylescholz.otterspotter.sync.SyncRepository
import com.kylescholz.otterspotter.sync.SyncScheduler

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_ALL = 101

        private val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
        }.toTypedArray()
    }

    private lateinit var binding: ActivityMainBinding
    private val locationManager by lazy { OtterLocationManager.getInstance(this) }
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var isTorchOn: Boolean = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private lateinit var otterDetector: OtterDetector
    private var isAiEnabled: Boolean = true

    // Auto-Capture (Auto Photo Mode) & Shutter State
    private var isAutoCaptureArmed: Boolean = true
    private var isCapturing: Boolean = false
    private var lastAutoCaptureTimestamp: Long = 0L

    // FPS calculation
    private var frameCount = 0
    private var lastFpsTimestamp = SystemClock.uptimeMillis()

    // Throat pattern detection haptic & shutter color tracking
    private var wasThroatVisible: Boolean = false
    private var lastThroatHapticTimestamp = 0L

    // Dynamic Zoom Presets
    data class ZoomPreset(
        val button: MaterialButton,
        val targetRatio: Float?, // null represents MAX
        val label: String
    )

    private lateinit var zoomPresets: List<ZoomPreset>
    private var hasInitializedZoom = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check authentication / guest mode before initializing camera
        if (!AuthManager.isLoggedIn(this)) {
            startActivity(Intent(this, SignInActivity::class.java))
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideNavigationBars()

        cameraExecutor = Executors.newSingleThreadExecutor()

        otterDetector = OtterDetector(this) { detections, wholeBodyBoxes, allDetections, inferMs, engineLabel, frameWidth, frameHeight ->
            runOnUiThread {
                handleDetectionResults(detections, wholeBodyBoxes, allDetections, inferMs, engineLabel, frameWidth, frameHeight)
            }
        }

        // Load saved confidence thresholds
        val prefs = getSharedPreferences("otter_cam_prefs", Context.MODE_PRIVATE)
        otterDetector.otterConfidence = prefs.getFloat("otter_conf", 0.25f)
        otterDetector.throatConfidence = prefs.getFloat("throat_conf", 0.25f)

        setupControls()
        setupLocationTracking()

        // Initialize background photo synchronization
        SyncScheduler.schedulePeriodicSync(this)

        if (allPermissionsGranted()) {
            startCamera()
            locationManager.startTracking()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                PERMISSION_REQUEST_ALL
            )
        }
    }

    private fun setupLocationTracking() {
        locationManager.onLocationChanged = { _ ->
            // High-precision GNSS location received and cached in OtterLocationManager
        }
    }

    override fun onResume() {
        super.onResume()
        hideNavigationBars()
        if (locationManager.hasLocationPermission()) {
            locationManager.startTracking()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideNavigationBars()
        }
    }

    private fun hideNavigationBars() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.navigationBars())
    }

    override fun onPause() {
        super.onPause()
        locationManager.stopTracking()
    }

    private fun setupControls() {
        // Menu Button (Hamburger)
        binding.btnMenu.setOnClickListener { view ->
            showOptionsMenu(view)
        }

        // Torch Button
        binding.btnTorch.setOnClickListener {
            camera?.let { cam ->
                if (cam.cameraInfo.hasFlashUnit()) {
                    isTorchOn = !isTorchOn
                    cam.cameraControl.enableTorch(isTorchOn)
                    binding.btnTorch.setColorFilter(
                        ContextCompat.getColor(
                            this,
                            if (isTorchOn) R.color.color_amber else R.color.text_white
                        )
                    )
                }
            }
        }

        // Initialize Zoom Presets: 1x, 2x, 5x, 10x, MAX
        zoomPresets = listOf(
            ZoomPreset(binding.btnZoom1x, 1.0f, "1x"),
            ZoomPreset(binding.btnZoom2x, 2.0f, "2x"),
            ZoomPreset(binding.btnZoom5x, 5.0f, "5x"),
            ZoomPreset(binding.btnZoom10x, 10.0f, "10x"),
            ZoomPreset(binding.btnZoomMax, null, "MAX")
        )

        for (preset in zoomPresets) {
            preset.button.setOnClickListener {
                camera?.cameraInfo?.zoomState?.value?.let { state ->
                    val ratio = preset.targetRatio ?: state.maxZoomRatio
                    camera?.cameraControl?.setZoomRatio(ratio)
                    updateZoomButtonsUI(ratio, state.maxZoomRatio)
                }
            }
        }

        // Pinch-to-zoom gesture support on viewfinder
        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                val delta = detector.scaleFactor
                camera?.cameraControl?.setZoomRatio(currentZoomRatio * delta)
                return true
            }
        })
        binding.previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }

        // Instant Shutter Button
        binding.btnShutterInstant.setOnClickListener {
            takePhoto(isAutoTriggered = false)
        }

        // Gallery Button (left of shutter)
        binding.btnGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        // Top Status Pill clicks toggle Auto Photo
        binding.tvStatusPill.setOnClickListener {
            toggleAutoPhoto()
        }
    }

    private fun toggleAutoPhoto() {
        if (!isAiEnabled) {
            Toast.makeText(this, "AI Inference is paused", Toast.LENGTH_SHORT).show()
            return
        }
        isAutoCaptureArmed = !isAutoCaptureArmed
        updateAutoPhotoPillUI()
        Toast.makeText(this, if (isAutoCaptureArmed) "Auto Photo: ON" else "Auto Photo: OFF", Toast.LENGTH_SHORT).show()
    }

    private fun updateAutoPhotoPillUI() {
        if (!isAiEnabled) {
            binding.tvStatusPill.text = "● AI Paused"
            binding.tvStatusPill.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.tvStatusPill.setBackgroundColor(Color.parseColor("#3394A3B8"))
            return
        }

        if (isAutoCaptureArmed) {
            binding.tvStatusPill.text = "🎯 Auto Photo"
            binding.tvStatusPill.setTextColor(ContextCompat.getColor(this, R.color.color_amber))
            binding.tvStatusPill.setBackgroundColor(Color.parseColor("#33FF9100"))
        } else {
            binding.tvStatusPill.text = "● Auto Off"
            binding.tvStatusPill.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.tvStatusPill.setBackgroundColor(Color.parseColor("#3394A3B8"))
        }
    }

    private fun showOptionsMenu(anchor: View) {
        val dialog = BottomSheetDialog(this)
        val sheetBinding = SheetSettingsMenuBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        // User Account Profile Card
        val user = AuthManager.getUser(this)
        if (user != null && !user.isGuest) {
            sheetBinding.tvAccountName.text = user.displayName
            sheetBinding.tvAccountEmail.text = user.email
            val initial = user.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "👤"
            sheetBinding.tvAccountAvatarText.text = initial
            sheetBinding.btnAccountAction.text = "Sign Out"
            sheetBinding.btnAccountAction.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            sheetBinding.btnAccountAction.setOnClickListener {
                AuthManager.signOut(this)
                Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                val intent = Intent(this, SignInActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
        } else {
            sheetBinding.tvAccountName.text = "Guest Researcher"
            sheetBinding.tvAccountEmail.text = "Offline Field Mode"
            sheetBinding.tvAccountAvatarText.text = "👤"
            sheetBinding.btnAccountAction.text = "Sign In"
            sheetBinding.btnAccountAction.setTextColor(ContextCompat.getColor(this, R.color.color_cyan))
            sheetBinding.btnAccountAction.setOnClickListener {
                dialog.dismiss()
                val intent = Intent(this, SignInActivity::class.java).apply {
                    putExtra(SignInActivity.EXTRA_ALLOW_CANCEL, true)
                }
                startActivity(intent)
            }
        }

        // Cloud Sync Status Card
        val syncSummary = SyncRepository.getInstance(this).getSyncSummary()
        if (user != null && !user.isGuest) {
            val syncDesc = when {
                syncSummary.pending > 0 -> "${syncSummary.uploaded} uploaded  •  ${syncSummary.pending} pending sync"
                syncSummary.uploaded > 0 -> "All ${syncSummary.uploaded} photos synchronized with cloud"
                else -> "Ready to sync photos"
            }
            sheetBinding.tvSettingsSyncStatus.text = syncDesc
            sheetBinding.btnSettingsSyncNow.text = "Sync Now"
            sheetBinding.btnSettingsSyncNow.setOnClickListener {
                SyncScheduler.triggerImmediateSync(this)
                Toast.makeText(this, "Syncing photos with cloud server...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        } else {
            sheetBinding.tvSettingsSyncStatus.text = "Operating offline • Sign in to sync ${syncSummary.total} photos"
            sheetBinding.btnSettingsSyncNow.text = "Sign In"
            sheetBinding.btnSettingsSyncNow.setOnClickListener {
                dialog.dismiss()
                val intent = Intent(this, SignInActivity::class.java).apply {
                    putExtra(SignInActivity.EXTRA_ALLOW_CANCEL, true)
                }
                startActivity(intent)
            }
        }

        // GPS Location Status Card
        fun updateGpsUI() {
            val loc = locationManager.getCurrentLocation()
            if (!locationManager.hasLocationPermission()) {
                sheetBinding.tvSettingsGpsStatus.text = "Location permission not granted"
                sheetBinding.tvSettingsGpsBadge.text = "No Perm"
                sheetBinding.tvSettingsGpsBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                sheetBinding.cardGpsStatus.isClickable = true
                sheetBinding.cardGpsStatus.setOnClickListener {
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ),
                        PERMISSION_REQUEST_ALL
                    )
                }
            } else if (loc != null) {
                val latDir = if (loc.latitude >= 0) "N" else "S"
                val lonDir = if (loc.longitude >= 0) "E" else "W"
                val latStr = String.format(java.util.Locale.US, "%.4f° %s", Math.abs(loc.latitude), latDir)
                val lonStr = String.format(java.util.Locale.US, "%.4f° %s", Math.abs(loc.longitude), lonDir)
                val acc = Math.round(loc.accuracy)
                sheetBinding.tvSettingsGpsStatus.text = "$latStr, $lonStr  •  ±${acc}m accuracy"
                sheetBinding.tvSettingsGpsBadge.text = "GPS ±${acc}m"
                sheetBinding.tvSettingsGpsBadge.setTextColor(
                    ContextCompat.getColor(this, if (acc <= 15) R.color.color_emerald else R.color.color_amber)
                )
                sheetBinding.cardGpsStatus.isClickable = false
            } else {
                sheetBinding.tvSettingsGpsStatus.text = "Acquiring GNSS satellites..."
                sheetBinding.tvSettingsGpsBadge.text = "Searching"
                sheetBinding.tvSettingsGpsBadge.setTextColor(ContextCompat.getColor(this, R.color.color_amber))
                sheetBinding.cardGpsStatus.isClickable = false
            }
        }
        updateGpsUI()

        val prevLocationListener = locationManager.onLocationChanged
        locationManager.onLocationChanged = { location ->
            prevLocationListener?.invoke(location)
            runOnUiThread {
                if (dialog.isShowing) {
                    updateGpsUI()
                }
            }
        }
        dialog.setOnDismissListener {
            locationManager.onLocationChanged = prevLocationListener
        }

        // Set Otter slider
        sheetBinding.sliderOtterConf.value = otterDetector.otterConfidence.coerceIn(0.05f, 0.95f)
        sheetBinding.tvValOtterConf.text = "${Math.round(otterDetector.otterConfidence * 100)}%"
        sheetBinding.sliderOtterConf.addOnChangeListener { _, value, _ ->
            otterDetector.otterConfidence = value
            sheetBinding.tvValOtterConf.text = "${Math.round(value * 100)}%"
            getSharedPreferences("otter_cam_prefs", Context.MODE_PRIVATE)
                .edit().putFloat("otter_conf", value).apply()
        }

        // Set Throat Pattern slider
        sheetBinding.sliderThroatConf.value = otterDetector.throatConfidence.coerceIn(0.05f, 0.95f)
        sheetBinding.tvValThroatConf.text = "${Math.round(otterDetector.throatConfidence * 100)}%"
        sheetBinding.sliderThroatConf.addOnChangeListener { _, value, _ ->
            otterDetector.throatConfidence = value
            sheetBinding.tvValThroatConf.text = "${Math.round(value * 100)}%"
            getSharedPreferences("otter_cam_prefs", Context.MODE_PRIVATE)
                .edit().putFloat("throat_conf", value).apply()
        }

        fun updateTogglesUI() {
            sheetBinding.tvStatusAi.text = if (isAiEnabled) "ON" else "OFF"
            sheetBinding.tvStatusAi.setTextColor(ContextCompat.getColor(this, if (isAiEnabled) R.color.color_emerald else R.color.text_muted))

            sheetBinding.tvStatusModel.text = otterDetector.engine.label
            sheetBinding.tvStatusModel.setTextColor(ContextCompat.getColor(this, R.color.color_cyan))

            sheetBinding.tvStatusCamera.text = if (lensFacing == CameraSelector.LENS_FACING_BACK) "Back Camera" else "Front Camera"

            val showBoxes = binding.overlayView.showDetectionBoxes
            sheetBinding.tvStatusBoxes.text = if (showBoxes) "ON" else "OFF"
            sheetBinding.tvStatusBoxes.setTextColor(ContextCompat.getColor(this, if (showBoxes) R.color.color_emerald else R.color.text_muted))

            val showDebug = binding.overlayView.showDebugOutlines
            sheetBinding.tvStatusDebug.text = if (showDebug) "ON" else "OFF"
            sheetBinding.tvStatusDebug.setTextColor(ContextCompat.getColor(this, if (showDebug) R.color.color_emerald else R.color.text_muted))
        }

        updateTogglesUI()

        sheetBinding.rowToggleAi.setOnClickListener {
            toggleAi()
            updateTogglesUI()
        }

        sheetBinding.rowToggleModel.setOnClickListener {
            toggleModel()
            updateTogglesUI()
        }

        sheetBinding.rowToggleCamera.setOnClickListener {
            toggleCamera()
            updateTogglesUI()
        }

        sheetBinding.rowToggleBoxes.setOnClickListener {
            toggleDetectionBoxes()
            updateTogglesUI()
        }

        sheetBinding.rowToggleDebug.setOnClickListener {
            toggleDebug()
            updateTogglesUI()
        }

        dialog.show()
    }

    private fun toggleAi() {
        isAiEnabled = !isAiEnabled
        otterDetector.isEnabled = isAiEnabled
        binding.overlayView.isAiEnabled = isAiEnabled

        if (isAiEnabled) {
            updateAutoPhotoPillUI()
            Toast.makeText(this, "AI Inference: ON", Toast.LENGTH_SHORT).show()
        } else {
            updateAutoPhotoPillUI()
            binding.overlayView.clear()
            binding.btnShutterInstant.backgroundTintList = ContextCompat.getColorStateList(this, R.color.text_white)
            wasThroatVisible = false
            binding.tvHudLatency.text = "Latency: --"
            binding.tvHudLatency.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.tvHudFps.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.layoutHudDetections.visibility = View.GONE
            Toast.makeText(this, "AI Inference: OFF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleModel() {
        otterDetector.engine = if (otterDetector.engine == ModelEngine.TFLITE_640) {
            ModelEngine.ONNX_256
        } else {
            ModelEngine.TFLITE_640
        }
        Toast.makeText(this, "Active Model: ${otterDetector.engine.label}", Toast.LENGTH_SHORT).show()
    }

    private fun toggleCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        hasInitializedZoom = false
        startCamera()
        val name = if (lensFacing == CameraSelector.LENS_FACING_BACK) "Back" else "Front"
        Toast.makeText(this, "Camera: $name", Toast.LENGTH_SHORT).show()
    }

    private fun toggleDetectionBoxes() {
        binding.overlayView.showDetectionBoxes = !binding.overlayView.showDetectionBoxes
        val state = if (binding.overlayView.showDetectionBoxes) "ON" else "OFF"
        Toast.makeText(this, "Detection Boxes: $state", Toast.LENGTH_SHORT).show()
    }

    private fun toggleDebug() {
        binding.overlayView.showDebugOutlines = !binding.overlayView.showDebugOutlines
        val state = if (binding.overlayView.showDebugOutlines) "ON" else "OFF"
        Toast.makeText(this, "Debug Outlines: $state", Toast.LENGTH_SHORT).show()
    }

    private fun computeNonOverlappingOtters(boxes: List<android.graphics.RectF>, iouThreshold: Float = 0.40f): Int {
        if (boxes.isEmpty()) return 0
        val sorted = boxes.sortedByDescending { it.width() * it.height() }
        val kept = mutableListOf<android.graphics.RectF>()
        for (box in sorted) {
            val overlaps = kept.any { computeIoU(box, it) >= iouThreshold }
            if (!overlaps) {
                kept.add(box)
            }
        }
        return kept.size
    }

    private fun computeIoU(a: android.graphics.RectF, b: android.graphics.RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        val interWidth = maxOf(0f, interRight - interLeft)
        val interHeight = maxOf(0f, interBottom - interTop)
        val interArea = interWidth * interHeight

        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        val unionArea = areaA + areaB - interArea

        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    private fun handleDetectionResults(
        detections: List<Detection>,
        wholeBodyBoxes: List<android.graphics.RectF>,
        allDetections: List<Detection>,
        inferMs: Long,
        engineLabel: String,
        frameWidth: Int,
        frameHeight: Int
    ) {
        if (!isAiEnabled) return

        val now = SystemClock.uptimeMillis()

        // Auto Photo Trigger: if armed and a throat portrait is spotted (strictly enclosed by whole body)
        // The toggle switch remains ON until the user turns it off
        if (isAutoCaptureArmed && detections.isNotEmpty() && !isCapturing && (now - lastAutoCaptureTimestamp >= 2500L)) {
            Log.i(TAG, "🎯 Auto Photo triggered by throat portrait! Enclosing detections: ${detections.size}")
            lastAutoCaptureTimestamp = now
            takePhoto(isAutoTriggered = true)
        }

        // Update Overlay
        binding.overlayView.setResults(detections, wholeBodyBoxes, allDetections, frameWidth, frameHeight)

        // Update Telemetry HUD (FPS & Latency always gray)
        binding.tvHudLatency.text = "${inferMs}ms ($engineLabel)"
        binding.tvHudLatency.setTextColor(ContextCompat.getColor(this, R.color.text_muted))

        frameCount++
        if (now - lastFpsTimestamp >= 1000) {
            val fps = frameCount * 1000f / (now - lastFpsTimestamp)
            binding.tvHudFps.text = "FPS: ${Math.round(fps)}"
            binding.tvHudFps.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            frameCount = 0
            lastFpsTimestamp = now
        }

        val ottersCount = computeNonOverlappingOtters(wholeBodyBoxes)
        val throatPatternsCount = detections.size

        // Line 2: Otters & Throat pattern counts only show when > 0
        if (ottersCount > 0) {
            binding.tvHudOtters.text = "Otters: $ottersCount"
            binding.tvHudOtters.visibility = View.VISIBLE
        } else {
            binding.tvHudOtters.visibility = View.GONE
        }

        if (throatPatternsCount > 0) {
            binding.tvHudThroats.text = "Throat patterns: $throatPatternsCount"
            binding.tvHudThroats.visibility = View.VISIBLE
        } else {
            binding.tvHudThroats.visibility = View.GONE
        }

        binding.tvHudDividerDetections.visibility = if (ottersCount > 0 && throatPatternsCount > 0) View.VISIBLE else View.GONE
        binding.layoutHudDetections.visibility = if (ottersCount > 0 || throatPatternsCount > 0) View.VISIBLE else View.GONE

        // Shutter button turns green when a throat pattern is visible
        if (throatPatternsCount > 0) {
            binding.btnShutterInstant.backgroundTintList = ContextCompat.getColorStateList(this, R.color.color_emerald)
            if (!wasThroatVisible || now - lastThroatHapticTimestamp > 2500) {
                vibrateThroatSpotted()
                lastThroatHapticTimestamp = now
            }
            wasThroatVisible = true
        } else {
            binding.btnShutterInstant.backgroundTintList = ContextCompat.getColorStateList(this, R.color.text_white)
            wasThroatVisible = false
        }
    }

    private fun takePhoto(isAutoTriggered: Boolean) {
        val capture = imageCapture ?: run {
            Log.w(TAG, "ImageCapture use case not ready yet")
            Toast.makeText(this, "Camera not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        if (isCapturing) return
        isCapturing = true

        // Visual flash feedback
        triggerShutterFlash()

        // Haptic feedback (distinctive, longer camera shutter haptic)
        vibratePhotoCaptured()

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(System.currentTimeMillis())
        val fileName = if (isAutoTriggered) "Otter_AutoPhoto_${timeStamp}.jpg" else "Otter_${timeStamp}.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Otter Spotter")
        }

        // Attach high-accuracy GPS fix to CameraX metadata
        val currentLocation = locationManager.getCurrentLocation()
        if (currentLocation != null) {
            Log.i(TAG, "Attaching GPS fix to photo $fileName: (${currentLocation.latitude}, ${currentLocation.longitude}, acc=${currentLocation.accuracy}m)")
        } else {
            Log.w(TAG, "No GPS location fix available at capture time for $fileName")
        }

        val metadata = ImageCapture.Metadata().apply {
            location = currentLocation
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )
        .setMetadata(metadata)
        .build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    isCapturing = false
                    val uri = outputFileResults.savedUri
                    Log.i(TAG, "Photo saved successfully to DCIM/Otter Spotter: $uri ($fileName)")
                    if (uri != null) {
                        // Ensure high-accuracy GPS tags and GPSHPositioningError are written to EXIF
                        if (currentLocation != null) {
                            try {
                                ExifLocationHelper.writeLocationToExif(
                                    context = this@MainActivity,
                                    uri = uri,
                                    fileName = fileName,
                                    location = currentLocation
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "ExifLocationHelper write failed: ${e.message}")
                            }
                        }

                        SyncRepository.getInstance(this@MainActivity).indexPhoto(
                            uri = uri,
                            fileName = fileName,
                            filePath = null,
                            dateTaken = System.currentTimeMillis(),
                            size = 0L
                        )
                        SyncScheduler.triggerImmediateSync(this@MainActivity)
                    }
                    showSavedBanner(fileName, isAutoTriggered)
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturing = false
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    Toast.makeText(this@MainActivity, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun triggerShutterFlash() {
        binding.vShutterFlash.apply {
            visibility = View.VISIBLE
            alpha = 0.75f
            animate()
                .alpha(0f)
                .setDuration(180)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        visibility = View.GONE
                    }
                })
                .start()
        }
    }

    private fun showSavedBanner(fileName: String, isAutoTriggered: Boolean) {
        val prefix = if (isAutoTriggered) "🎯 Auto Photo" else "📸 Captured"
        binding.tvSavedText.text = "$prefix: $fileName\nSaved to DCIM/Otter Spotter"
        binding.layoutSavedBanner.apply {
            visibility = View.VISIBLE
            alpha = 0f
            animate()
                .alpha(1f)
                .setDuration(200)
                .start()

            postDelayed({
                animate()
                    .alpha(0f)
                    .setDuration(300)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            visibility = View.GONE
                        }
                    })
                    .start()
            }, 3000)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val provider: ProcessCameraProvider = cameraProviderFuture.get()
            this@MainActivity.cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        otterDetector.processImage(imageProxy)
                    }
                }

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            imageCapture = capture

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer,
                    capture
                )

                // Dynamically setup zoom presets supported by this camera and default to middle
                camera?.cameraInfo?.zoomState?.observe(this) { zoomState ->
                    val minRatio = zoomState.minZoomRatio
                    val maxRatio = zoomState.maxZoomRatio
                    val currentRatio = zoomState.zoomRatio

                    val supportedPresets = mutableListOf<ZoomPreset>()
                    for (preset in zoomPresets) {
                        val isSupported = if (preset.targetRatio != null) {
                            preset.targetRatio in minRatio..maxRatio
                        } else {
                            maxRatio > 1.0f
                        }

                        if (isSupported) {
                            preset.button.visibility = View.VISIBLE
                            supportedPresets.add(preset)
                        } else {
                            preset.button.visibility = View.GONE
                        }
                    }

                    // Default to the middle option among supported presets
                    if (!hasInitializedZoom && supportedPresets.isNotEmpty()) {
                        hasInitializedZoom = true
                        val middleIndex = supportedPresets.size / 2
                        val defaultPreset = supportedPresets[middleIndex]
                        val defaultRatio = defaultPreset.targetRatio ?: maxRatio
                        Log.i(TAG, "Supported zoom options: ${supportedPresets.map { it.label }}. Defaulting to middle: ${defaultPreset.label} (${defaultRatio}x)")
                        camera?.cameraControl?.setZoomRatio(defaultRatio)
                        updateZoomButtonsUI(defaultRatio, maxRatio)
                    } else {
                        updateZoomButtonsUI(currentRatio, maxRatio)
                    }
                }

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateZoomButtonsUI(currentRatio: Float, maxRatio: Float) {
        val density = resources.displayMetrics.density
        val strokeSelected = (2 * density).toInt()
        val strokeUnselected = (1 * density).toInt()

        for (preset in zoomPresets) {
            val targetRatio = preset.targetRatio ?: maxRatio
            val isSelected = Math.abs(currentRatio - targetRatio) < 0.25f ||
                (preset.targetRatio == null && Math.abs(currentRatio - maxRatio) < 0.1f)

            if (isSelected) {
                preset.button.strokeColor = ContextCompat.getColorStateList(this, R.color.color_emerald)
                preset.button.strokeWidth = strokeSelected
                preset.button.setTextColor(ContextCompat.getColor(this, R.color.color_emerald))
                preset.button.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3300E676"))
            } else {
                val isMax = preset.targetRatio == null
                val defaultColor = if (isMax) R.color.color_cyan else R.color.text_muted
                preset.button.strokeColor = ContextCompat.getColorStateList(this, defaultColor)
                preset.button.strokeWidth = strokeUnselected
                preset.button.setTextColor(ContextCompat.getColor(this, if (isMax) R.color.color_cyan else R.color.text_white))
                preset.button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            }
        }
    }

    private fun vibrateThroatSpotted() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(35)
        }
    }

    private fun vibratePhotoCaptured() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Distinctive camera shutter haptic: double-pulse (80ms pulse, 50ms pause, 150ms pulse)
            val timings = longArrayOf(0, 80, 50, 150)
            val amplitudes = intArrayOf(0, 200, 0, 255)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(250)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_ALL) {
            val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (cameraGranted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
                finish()
            }

            if (locationManager.hasLocationPermission()) {
                locationManager.startTracking()
            }
        }
    }

    override fun onDestroy() {
        try {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
            otterDetector.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up camera or detector", e)
        }
        super.onDestroy()
    }
}
