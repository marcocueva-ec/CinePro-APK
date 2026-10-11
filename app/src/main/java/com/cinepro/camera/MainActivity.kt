package com.cinepro.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.*
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cinepro.camera.databinding.ActivityMainBinding
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private lateinit var displayManager: DisplayManager
    private var cameraDevice: CameraDevice? = null
    private var currentCharacteristics: CameraCharacteristics? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var mediaRecorder: MediaRecorder? = null

    // Lector para Fotografia RAW (.DNG) y JPEG en Maxima Resolucion del Sensor
    private var rawImageReader: ImageReader? = null
    private var jpegImageReader: ImageReader? = null
    private var isRawSupportedOnCurrentLens: Boolean = false
    private val rawLock = Any()
    private var pendingRawResult: TotalCaptureResult? = null
    private var pendingRawImage: Image? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Estado de Camara y Sensor
    private var currentCameraId: String = "0"
    private var availableCameraIds: List<String> = emptyList()
    private var currentCameraIndex = 0

    private var openGateMode: Boolean = true // Open Gate 4:3 sin recorte activo por defecto
    private var logModeEnabled: Boolean = true
    private var selectedFps: Int = 24
    private var isRecording: Boolean = false

    // Rangos del Sensor
    private var isoRange: Range<Int> = Range(100, 3200)
    private var exposureTimeRange: Range<Long> = Range(1_000_000L, 100_000_000L)
    private var aeCompRange: Range<Int> = Range(-6, 6)
    private var aeCompStep: Float = 0.5f
    private var minFocusDist: Float = 10f
    private var sensorActiveArray: Rect = Rect(0, 0, 4000, 3000)
    private var maxZoom: Float = 8.0f

    // Progreso guardado (0.0 a 1.0) para cada tarjeta
    private var focusProg: Float = 0f
    private var isoProg: Float = 0.38f
    private var shutterProg: Float = 0.20f
    private var wbProg: Float = 0.42f
    private var irisProg: Float = 0.50f
    private var peakingProg: Float = 0f // 0 = OFF, >0 = Intensidad de Focus Peaking

    // Estado Manual / Auto
    private var autoIsoAndShutter: Boolean = false
    private var autoWb: Boolean = false
    private var currentIso: Int = 400
    private var currentShutterNs: Long = 20_833_333L // 1/48s
    private var currentFocusDist: Float = 0f // 0f = AF-C
    private var currentKelvin: Int = 5600
    private var currentEvIndex: Int = 0
    private var currentZoomRatio: Float = 1.0f

    private var selectedVideoSize: Size = Size(3840, 2160)
    private var lastSavedMediaUri: Uri? = null
    private var currentParcelFd: ParcelFileDescriptor? = null

    private val isoSteps = listOf(50, 100, 160, 200, 320, 400, 640, 800, 1250, 1600, 2500, 3200, 6400)
    private val shutterStepsDenom = listOf(24, 30, 48, 50, 60, 100, 120, 250, 500, 1000, 2000, 4000)
    private val wbSteps = listOf(2800, 3200, 4000, 4800, 5600, 6500, 7500, 9000)
    private val fpsOptions = listOf(24, 30, 60)

    private var activePopupParam: String? = null
    private var isAnalyzingPeaking: Boolean = false
    private var lastPeakingTimestamp: Long = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (binding.viewfinder.isAvailable) {
                configureTransform(binding.viewfinder.width, binding.viewfinder.height)
            }
        }
    }

    private var recStartTimeMs = 0L
    private val uiHandler = Handler(Looper.getMainLooper())
    private val timecodeRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsedMs = SystemClock.elapsedRealtime() - recStartTimeMs
                val totalSeconds = elapsedMs / 1000
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                val frames = ((elapsedMs % 1000) * selectedFps) / 1000
                binding.txtTimecode.text = String.format("%02d:%02d:%02d:%02d", hours, minutes, seconds, frames)
                uiHandler.postDelayed(this, 40)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        setupUIControls()
        setupActionableCardsAndPopup()
        updateBatteryStatus()

        if (hasPermissions()) {
            initViewfinder()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                1001
            )
        }
    }

    private fun setupActionableCardsAndPopup() {
        binding.cardFocus.setOnClickListener { togglePopupMenu("FOCUS") }
        binding.cardIso.setOnClickListener { togglePopupMenu("ISO") }
        binding.cardShutter.setOnClickListener { togglePopupMenu("SHUTTER") }
        binding.cardWb.setOnClickListener { togglePopupMenu("WB") }
        binding.cardIris.setOnClickListener { togglePopupMenu("IRIS") }
        binding.btnPeaking.setOnClickListener { togglePopupMenu("PEAKING") }

        // Tocar el visor cierra la rueda emergente para despejar la pantalla
        binding.viewfinder.setOnClickListener {
            if (binding.popupParameterMenu.visibility == View.VISIBLE) {
                activePopupParam = null
                binding.popupParameterMenu.visibility = View.GONE
            }
        }

        binding.btnClosePopup.setOnClickListener {
            activePopupParam = null
            binding.popupParameterMenu.visibility = View.GONE
        }

        // Conectar la Rueda Unica Emergente (popupArcDial) al parametro que este abierto
        binding.popupArcDial.onValueChanged = { norm ->
            binding.popupSeekBar.progress = (norm * 100).roundToInt()
            applyProgressToActiveParameter(norm)
        }

        binding.popupSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val norm = progress / 100f
                binding.popupArcDial.progress = norm
                applyProgressToActiveParameter(norm)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun applyProgressToActiveParameter(norm: Float) {
        when (activePopupParam) {
            "FOCUS" -> updateFocusFromProgress(norm)
            "ISO" -> updateIsoFromProgress(norm)
            "SHUTTER" -> updateShutterFromProgress(norm)
            "WB" -> updateWbFromProgress(norm)
            "IRIS" -> updateIrisFromProgress(norm)
            "PEAKING" -> updatePeakingFromProgress(norm)
        }
    }

    private fun togglePopupMenu(param: String) {
        if (activePopupParam == param && binding.popupParameterMenu.visibility == View.VISIBLE) {
            activePopupParam = null
            binding.popupParameterMenu.visibility = View.GONE
            return
        }
        activePopupParam = param
        binding.popupParameterMenu.visibility = View.VISIBLE
        binding.presetButtonsContainer.removeAllViews()

        when (param) {
            "FOCUS" -> {
                binding.txtPopupTitle.text = "RUEDA DE ENFOQUE MANUAL / AF-C"
                binding.popupArcDial.accentMode = 0
                binding.popupArcDial.progress = focusProg
                binding.popupSeekBar.progress = (focusProg * 100).roundToInt()

                addPresetButton("AF-C (Auto)") { setPopupAndParam(0f) }
                listOf("Macro 0.1m" to 0.95f, "0.25m" to 0.70f, "0.5m" to 0.45f, "1.0m" to 0.25f, "Infinito ∞" to 0.10f).forEach { (label, prog) ->
                    addPresetButton(label) { setPopupAndParam(prog) }
                }
            }
            "ISO" -> {
                binding.txtPopupTitle.text = "RUEDA DE SENSIBILIDAD ISO"
                binding.popupArcDial.accentMode = 1
                binding.popupArcDial.progress = isoProg
                binding.popupSeekBar.progress = (isoProg * 100).roundToInt()

                addPresetButton("AUTO AE") {
                    autoIsoAndShutter = true
                    binding.valIso.text = "AUTO"
                    binding.valShutter.text = "AUTO"
                    applyParametersToSession()
                }
                isoSteps.forEachIndexed { index, isoVal ->
                    addPresetButton("ISO $isoVal") {
                        autoIsoAndShutter = false
                        setPopupAndParam(index.toFloat() / (isoSteps.size - 1))
                    }
                }
            }
            "SHUTTER" -> {
                binding.txtPopupTitle.text = "RUEDA DE SHUTTER SPEED (OBTURACIÓN)"
                binding.popupArcDial.accentMode = 0
                binding.popupArcDial.progress = shutterProg
                binding.popupSeekBar.progress = (shutterProg * 100).roundToInt()

                addPresetButton("AUTO AE") {
                    autoIsoAndShutter = true
                    binding.valShutter.text = "AUTO"
                    binding.valIso.text = "AUTO"
                    applyParametersToSession()
                }
                shutterStepsDenom.forEachIndexed { index, denom ->
                    addPresetButton("1/${denom}s") {
                        autoIsoAndShutter = false
                        setPopupAndParam(index.toFloat() / (shutterStepsDenom.size - 1))
                    }
                }
            }
            "WB" -> {
                binding.txtPopupTitle.text = "RUEDA DE BALANCE DE BLANCOS (KELVIN)"
                binding.popupArcDial.accentMode = 2
                binding.popupArcDial.progress = wbProg
                binding.popupSeekBar.progress = (wbProg * 100).roundToInt()

                addPresetButton("AWB (Auto)") {
                    autoWb = true
                    binding.valWb.text = "AWB"
                    applyParametersToSession()
                }
                wbSteps.forEach { kelvin ->
                    addPresetButton("${kelvin}K") {
                        autoWb = false
                        setPopupAndParam(((kelvin - 2500f) / 7500f).coerceIn(0f, 1f))
                    }
                }
            }
            "IRIS" -> {
                binding.txtPopupTitle.text = "RUEDA DE EXPOSICIÓN (EV / IRIS)"
                binding.popupArcDial.accentMode = 0
                binding.popupArcDial.progress = irisProg
                binding.popupSeekBar.progress = (irisProg * 100).roundToInt()

                listOf(-2.0f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f, 2.0f).forEach { ev ->
                    val label = if (ev >= 0) "+${ev} EV" else "${ev} EV"
                    addPresetButton(label) {
                        setPopupAndParam(((ev + 2.0f) / 4.0f).coerceIn(0f, 1f))
                    }
                }
            }
            "PEAKING" -> {
                binding.txtPopupTitle.text = "INTENSIDAD DE FOCUS PEAKING (BORDES ENFOCADOS)"
                binding.popupArcDial.accentMode = 1
                binding.popupArcDial.progress = peakingProg
                binding.popupSeekBar.progress = (peakingProg * 100).roundToInt()

                addPresetButton("APAGADO (OFF)") { setPopupAndParam(0f) }
                addPresetButton("SUAVE (25%)") { setPopupAndParam(0.25f) }
                addPresetButton("MEDIO (55%)") { setPopupAndParam(0.55f) }
                addPresetButton("ALTO (80%)") { setPopupAndParam(0.80f) }
                addPresetButton("MÁXIMO (100%)") { setPopupAndParam(1.0f) }
            }
        }
    }

    private fun setPopupAndParam(prog: Float) {
        binding.popupArcDial.progress = prog
        binding.popupSeekBar.progress = (prog * 100).roundToInt()
        applyProgressToActiveParameter(prog)
    }

    private fun addPresetButton(title: String, onClick: () -> Unit) {
        val btn = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#E0F7FA"))
            textSize = 11f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_glass_pill)
            setPadding(26, 12, 26, 12)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = 12
            layoutParams = params
            setOnClickListener { onClick() }
        }
        binding.presetButtonsContainer.addView(btn)
    }

    private fun updateFocusFromProgress(progress: Float) {
        focusProg = progress
        if (progress < 0.08f) {
            currentFocusDist = 0f
            binding.valFocus.text = "AF-C"
        } else {
            currentFocusDist = progress * minFocusDist
            val meters = (1f / currentFocusDist.coerceAtLeast(0.1f))
            binding.valFocus.text = String.format("%.2fm", meters)
        }
        applyParametersToSession()
    }

    private fun updateIsoFromProgress(progress: Float) {
        isoProg = progress
        autoIsoAndShutter = false
        val idx = (progress * (isoSteps.size - 1)).roundToInt()
        currentIso = isoSteps[idx].coerceIn(isoRange.lower, isoRange.upper)
        binding.valIso.text = currentIso.toString()
        binding.rgbHistogram.updateFromExposure(progress, shutterProg)
        applyParametersToSession()
    }

    private fun updateShutterFromProgress(progress: Float) {
        shutterProg = progress
        autoIsoAndShutter = false
        val idx = (progress * (shutterStepsDenom.size - 1)).roundToInt()
        val denom = shutterStepsDenom[idx]
        currentShutterNs = (1_000_000_000L / denom).coerceIn(exposureTimeRange.lower, exposureTimeRange.upper)
        binding.valShutter.text = "1/${denom}s"
        binding.rgbHistogram.updateFromExposure(isoProg, progress)
        applyParametersToSession()
    }

    private fun updateWbFromProgress(progress: Float) {
        wbProg = progress
        autoWb = false
        currentKelvin = (2500 + (progress * 7500).roundToInt() / 100 * 100)
        binding.valWb.text = "${currentKelvin}K"
        applyParametersToSession()
    }

    private fun updateIrisFromProgress(progress: Float) {
        irisProg = progress
        currentEvIndex = (aeCompRange.lower + progress * (aeCompRange.upper - aeCompRange.lower)).roundToInt()
        val evVal = currentEvIndex * aeCompStep
        binding.valIris.text = String.format("%+.1f EV", evVal)
        applyParametersToSession()
    }

    private fun updatePeakingFromProgress(progress: Float) {
        peakingProg = progress
        val level = (progress * 100).roundToInt()
        binding.cineOverlays.peakingSensitivity = level
        if (level == 0) {
            binding.btnPeaking.text = "PEAK"
            binding.btnPeaking.setTextColor(0xFFB0BEC5.toInt())
            binding.cineOverlays.peakingBitmap = null
        } else {
            binding.btnPeaking.text = "PK ${level}%"
            binding.btnPeaking.setTextColor(0xFF00E676.toInt())
        }
        binding.cineOverlays.invalidate()
    }

    private fun setupUIControls() {
        binding.curvedZoom.onZoomChanged = { progress ->
            currentZoomRatio = 1.0f + progress * (maxZoom - 1.0f).coerceAtMost(5.0f)
            binding.btnLensSwitch.text = String.format("%.1fx", currentZoomRatio)
            applyParametersToSession()
        }

        binding.btnCodecMode.setOnClickListener {
            if (isRecording) return@setOnClickListener
            openGateMode = !openGateMode
            reopenCamera()
        }

        binding.btnFps.setOnClickListener {
            if (isRecording) return@setOnClickListener
            val nextIdx = (fpsOptions.indexOf(selectedFps) + 1) % fpsOptions.size
            selectedFps = fpsOptions[nextIdx]
            binding.btnFps.text = "$selectedFps fps"
            reopenCamera()
        }

        binding.btnLutToggle.setOnClickListener {
            binding.cineOverlays.lutEnabled = !binding.cineOverlays.lutEnabled
            binding.cineOverlays.invalidate()
            binding.btnLutToggle.text = if (binding.cineOverlays.lutEnabled) "LUT REC709: ON" else "LUT REC709: OFF"
            binding.btnLutToggle.setTextColor(if (binding.cineOverlays.lutEnabled) 0xFF38E8FF.toInt() else 0xFFB0BEC5.toInt())
        }

        binding.btnLogProfile.setOnClickListener {
            logModeEnabled = !logModeEnabled
            binding.btnLogProfile.setTextColor(if (logModeEnabled) 0xFF00E676.toInt() else 0xFFB0BEC5.toInt())
            Toast.makeText(
                this,
                if (logModeEnabled) "CineLog ACTIVO (Curva Log + Nitidez OFF)" else "Perfil Rec.709 Estandar",
                Toast.LENGTH_SHORT
            ).show()
            applyParametersToSession()
        }

        binding.btnLensSwitch.setOnClickListener {
            if (isRecording || availableCameraIds.size <= 1) return@setOnClickListener
            currentCameraIndex = (currentCameraIndex + 1) % availableCameraIds.size
            currentCameraId = availableCameraIds[currentCameraIndex]
            currentZoomRatio = 1.0f
            binding.curvedZoom.zoomProgress = 0f
            reopenCamera()
        }

        // Boton GRID: Alterna entre Regla de Tercios -> Tercios + Cruz -> Apagado
        binding.btnGrid.setOnClickListener {
            binding.cineOverlays.gridMode = (binding.cineOverlays.gridMode + 1) % 3
            when (binding.cineOverlays.gridMode) {
                0 -> {
                    binding.btnGrid.text = "GRID"
                    binding.btnGrid.setTextColor(0xFFB0BEC5.toInt())
                }
                1 -> {
                    binding.btnGrid.text = "3x3"
                    binding.btnGrid.setTextColor(0xFF38E8FF.toInt())
                }
                2 -> {
                    binding.btnGrid.text = "3x3+"
                    binding.btnGrid.setTextColor(0xFF00E676.toInt())
                }
            }
            binding.cineOverlays.invalidate()
        }

        binding.btnLevel.setOnClickListener {
            binding.cineOverlays.showLevel = !binding.cineOverlays.showLevel
            binding.btnLevel.setTextColor(if (binding.cineOverlays.showLevel) 0xFF38E8FF.toInt() else 0xFFB0BEC5.toInt())
            binding.cineOverlays.invalidate()
        }

        // Boton GALERIA
        binding.btnOpenGallery.setOnClickListener {
            try {
                if (lastSavedMediaUri != null) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(lastSavedMediaUri, "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Abriendo Galeria...", Toast.LENGTH_SHORT).show()
            }
        }

        // NUEVO: Boton Fotografia RAW (.DNG) en Maxima Resolucion
        binding.btnCaptureRaw.setOnClickListener {
            captureRawOrMaxPhoto()
        }

        // Boton REC Video
        binding.btnRecord.setOnClickListener {
            if (isRecording) stopRecordingVideo() else startRecordingVideo()
        }
    }

    private fun initViewfinder() {
        discoverBackCameras()
        if (binding.viewfinder.isAvailable) {
            openCamera(binding.viewfinder.width, binding.viewfinder.height)
        } else {
            binding.viewfinder.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openCamera(width, height)
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    configureTransform(width, height)
                }
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    processLiveFocusPeakingFrame()
                }
            }
        }
    }

    /**
     * Calcula Focus Peaking en tiempo real analizando el gradiente de bordes (Sobel/Laplaciano)
     * con sensibilidad configurable por el usuario.
     */
    private fun processLiveFocusPeakingFrame() {
        val sensitivity = binding.cineOverlays.peakingSensitivity
        if (sensitivity <= 0 || isAnalyzingPeaking) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPeakingTimestamp < 55L) return

        val smallBmp = binding.viewfinder.getBitmap(220, 130) ?: return
        isAnalyzingPeaking = true
        lastPeakingTimestamp = now

        backgroundHandler?.post {
            try {
                val w = smallBmp.width
                val h = smallBmp.height
                val pixels = IntArray(w * h)
                val outPixels = IntArray(w * h)
                smallBmp.getPixels(pixels, 0, w, 0, 0, w, h)
                smallBmp.recycle()

                // Umbral inversamente proporcional a la sensibilidad elegida (mas sensibilidad = umbral mas bajo)
                val threshold = (135 - (sensitivity * 1.05f)).toInt().coerceIn(18, 125)

                for (y in 1 until h - 1) {
                    val rowOffset = y * w
                    for (x in 1 until w - 1) {
                        val idx = rowOffset + x
                        val c = pixels[idx]
                        val lumaC = ((c shr 16 and 0xFF) * 2 + (c shr 8 and 0xFF) * 5 + (c and 0xFF)) shr 3

                        val r = pixels[idx + 1]
                        val lumaR = ((r shr 16 and 0xFF) * 2 + (r shr 8 and 0xFF) * 5 + (r and 0xFF)) shr 3

                        val b = pixels[idx + w]
                        val lumaB = ((b shr 16 and 0xFF) * 2 + (b shr 8 and 0xFF) * 5 + (b and 0xFF)) shr 3

                        val grad = abs(lumaC - lumaR) + abs(lumaC - lumaB)
                        if (grad > threshold) {
                            // Color Verde Neon / Cian de alto contraste para los bordes en foco
                            outPixels[idx] = if (grad > threshold + 35) 0xFFFF1744.toInt() else 0xFF00E676.toInt()
                        }
                    }
                }

                val peakBmp = Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)
                runOnUiThread {
                    binding.cineOverlays.peakingBitmap = peakBmp
                    binding.cineOverlays.invalidate()
                    isAnalyzingPeaking = false
                }
            } catch (e: Exception) {
                isAnalyzingPeaking = false
            }
        }
    }

    private fun discoverBackCameras() {
        val backIds = mutableListOf<String>()
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                backIds.add(id)
            }
        }
        availableCameraIds = if (backIds.isNotEmpty()) backIds else cameraManager.cameraIdList.toList()
        currentCameraId = availableCameraIds.firstOrNull() ?: "0"
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        try {
            val chars = cameraManager.getCameraCharacteristics(currentCameraId)
            currentCharacteristics = chars
            isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
            exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(1_000_000L, 100_000_000L)
            aeCompRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(-6, 6)
            aeCompStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.5f
            minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10f
            sensorActiveArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
            maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 8.0f

            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // Configurar ImageReader para Fotografia RAW (.DNG) en Maxima Resolucion Nativa
            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
            isRawSupportedOnCurrentLens = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) && !rawSizes.isNullOrEmpty()

            rawImageReader?.close()
            jpegImageReader?.close()

            if (isRawSupportedOnCurrentLens && rawSizes != null) {
                val maxRawSize = rawSizes.maxByOrNull { it.width * it.height } ?: rawSizes[0]
                rawImageReader = ImageReader.newInstance(maxRawSize.width, maxRawSize.height, ImageFormat.RAW_SENSOR, 2).apply {
                    setOnImageAvailableListener({ reader ->
                        synchronized(rawLock) {
                            pendingRawImage?.close()
                            pendingRawImage = reader.acquireNextImage()
                            tryWriteDngToGallery()
                        }
                    }, backgroundHandler)
                }
            }

            // Configurar tambien JPEG en Maxima Resolucion del Sensor (por si el lente secundario no tiene DNG)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: arrayOf(Size(4000, 3000))
            val maxJpegSize = jpegSizes.maxByOrNull { it.width * it.height } ?: Size(3840, 2160)
            jpegImageReader = ImageReader.newInstance(maxJpegSize.width, maxJpegSize.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val img = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                    saveJpegToGallery(img)
                }, backgroundHandler)
            }

            // Seleccionar tamaño de video (Open Gate 4:3 Completo o 4K 16:9)
            val videoSizes = map?.getOutputSizes(MediaRecorder::class.java) ?: arrayOf(Size(1920, 1080))
            selectedVideoSize = chooseOptimalVideoSize(videoSizes, openGateMode)
            val modeLabel = if (openGateMode) "OPEN GATE ${selectedVideoSize.width}x${selectedVideoSize.height}" else "4K 16:9 ${selectedVideoSize.width}x${selectedVideoSize.height}"
            binding.btnCodecMode.text = modeLabel

            configureTransform(width, height)

            cameraManager.openCamera(currentCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreviewSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Toast.makeText(this, "Error abriendo sensor: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun chooseOptimalVideoSize(sizes: Array<Size>, preferOpenGate: Boolean): Size {
        if (preferOpenGate) {
            val openGateCandidate = sizes
                .filter {
                    val ratio = it.width.toFloat() / it.height.toFloat()
                    (ratio in 1.25f..1.55f) && it.width <= 4096
                }
                .maxByOrNull { it.width * it.height }
            if (openGateCandidate != null && openGateCandidate.width >= 1920) {
                return openGateCandidate
            }
        }
        return sizes.firstOrNull { it.width == 3840 && it.height == 2160 }
            ?: sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: sizes.maxByOrNull { it.width * it.height }
            ?: Size(1920, 1080)
    }

    private fun startPreviewSession() {
        val device = cameraDevice ?: return
        val texture = binding.viewfinder.surfaceTexture ?: return
        // Fijar exactamente el mismo buffer en Preview y en Grabacion para CERO CROP
        texture.setDefaultBufferSize(selectedVideoSize.width, selectedVideoSize.height)
        val previewSurface = Surface(texture)

        val surfaces = mutableListOf(previewSurface)
        if (isRawSupportedOnCurrentLens && rawImageReader != null) {
            surfaces.add(rawImageReader!!.surface)
        } else if (jpegImageReader != null) {
            surfaces.add(jpegImageReader!!.surface)
        }

        // Usamos TEMPLATE_PREVIEW o TEMPLATE_RECORD con EIS desactivado para que no exista salto de encuadre
        previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
        }

        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                applyParametersToSession()
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, backgroundHandler)
    }

    /**
     * Aplica parametros manuales y DESACTIVA la Estabilizacion Digital (EIS)
     * para evitar que Android haga un crop al iniciar la grabacion en Open Gate.
     */
    private fun applyParametersToSession() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        try {
            // CERO CROP EN OPEN GATE: Forzar apagado de estabilizacion digital de video (EIS)
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)

            if (autoIsoAndShutter) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEvIndex)
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentShutterNs)
            }
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(selectedFps, selectedFps))

            if (currentFocusDist <= 0.01f) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDist)
            }

            if (autoWb) {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            } else {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggb(currentKelvin))
            }

            // Mantener exactamente la matriz activa completa del sensor (100% FOV en 1.0x)
            val cropW = (sensorActiveArray.width() / currentZoomRatio).toInt()
            val cropH = (sensorActiveArray.height() / currentZoomRatio).toInt()
            val cropX = (sensorActiveArray.width() - cropW) / 2
            val cropY = (sensorActiveArray.height() - cropH) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))

            if (logModeEnabled) {
                builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                builder.set(CaptureRequest.TONEMAP_CURVE, buildCineLogTonemapCurve())
            } else {
                builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
            }

            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Captura Fotografia en formato RAW (.DNG de 16 bits sin comprimir) en la maxima resolucion del sensor.
     */
    private fun captureRawOrMaxPhoto() {
        if (isRecording) {
            Toast.makeText(this, "Detén la grabación para capturar en RAW DNG", Toast.LENGTH_SHORT).show()
            return
        }
        val device = cameraDevice ?: return
        val session = captureSession ?: return

        try {
            val targetSurface = if (isRawSupportedOnCurrentLens && rawImageReader != null) {
                rawImageReader!!.surface
            } else {
                jpegImageReader?.surface ?: return
            }

            val stillBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(targetSurface)
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)

                if (autoIsoAndShutter) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentEvIndex)
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentShutterNs)
                }

                if (currentFocusDist > 0.01f) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDist)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }

                if (!autoWb) {
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                    set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggb(currentKelvin))
                }
                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, getVideoOrientationHint())
            }

            Toast.makeText(this, if (isRawSupportedOnCurrentLens) "Capturando RAW (.DNG)..." else "Capturando Foto Max Res...", Toast.LENGTH_SHORT).show()

            session.capture(stillBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (isRawSupportedOnCurrentLens) {
                        synchronized(rawLock) {
                            pendingRawResult = result
                            tryWriteDngToGallery()
                        }
                    }
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Toast.makeText(this, "Error en captura RAW: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryWriteDngToGallery() {
        val result = pendingRawResult ?: return
        val image = pendingRawImage ?: return
        val chars = currentCharacteristics ?: return

        pendingRawResult = null
        pendingRawImage = null

        try {
            val fileName = "CINEPRO_RAW_${image.width}x${image.height}_${System.currentTimeMillis()}.dng"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                }
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { outStream ->
                    val dngCreator = DngCreator(chars, result)
                    dngCreator.writeImage(outStream, image)
                    dngCreator.close()
                }
                lastSavedMediaUri = uri
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                runOnUiThread {
                    Toast.makeText(this, "RAW DNG (${image.width}x${image.height}) guardado en Galería", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }

    private fun saveJpegToGallery(image: Image) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val fileName = "CINEPRO_MAX_${image.width}x${image.height}_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                lastSavedMediaUri = uri
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                runOnUiThread {
                    Toast.makeText(this, "Foto (${image.width}x${image.height}) guardada en Galería", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }

    private fun buildCineLogTonemapCurve(): TonemapCurve {
        val points = FloatArray(64)
        for (i in 0 until 32) {
            val x = i.toFloat() / 31f
            val y = (0.14f + 0.82f * (ln(1f + 18f * x) / ln(19f))).coerceIn(0f, 1f)
            points[i * 2] = x
            points[i * 2 + 1] = y
        }
        return TonemapCurve(points, points, points)
    }

    private fun kelvinToRggb(kelvin: Int): RggbChannelVector {
        val temp = kelvin / 100.0
        val r = if (temp <= 66) 1.0f else (1.292936 * (temp - 60).pow(-0.1332047)).toFloat().coerceIn(0.5f, 3.0f)
        val b = when {
            temp >= 66 -> 1.0f
            temp <= 19 -> 0.5f
            else -> (0.5432067 * ln(temp - 10) - 1.196254).toFloat().coerceIn(0.5f, 3.0f)
        }
        return RggbChannelVector(
            (1.6f / r).coerceIn(0.8f, 3.2f),
            1.0f,
            1.0f,
            (1.6f / b).coerceIn(0.8f, 3.2f)
        )
    }

    private fun startRecordingVideo() {
        val device = cameraDevice ?: return
        try {
            val pfd = createGalleryVideoFileDescriptor() ?: return
            currentParcelFd = pfd

            mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(pfd.fileDescriptor)
                setVideoEncodingBitRate(95_000_000)
                setVideoFrameRate(selectedFps)
                setVideoSize(selectedVideoSize.width, selectedVideoSize.height)
                setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(320_000)
                setAudioSamplingRate(48_000)
                setOrientationHint(getVideoOrientationHint())
                prepare()
            }

            val texture = binding.viewfinder.surfaceTexture!!
            // Mantener exactamente el mismo buffer size que en Preview para CERO CROP
            texture.setDefaultBufferSize(selectedVideoSize.width, selectedVideoSize.height)
            val previewSurface = Surface(texture)
            val recorderSurface = mediaRecorder!!.surface

            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recorderSurface)
            }

            device.createCaptureSession(listOf(previewSurface, recorderSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    applyParametersToSession()
                    runOnUiThread {
                        mediaRecorder?.start()
                        isRecording = true
                        recStartTimeMs = SystemClock.elapsedRealtime()
                        binding.btnRecord.text = "STOP"
                        binding.txtTimecode.setTextColor(0xFFFF3B30.toInt())
                        uiHandler.post(timecodeRunnable)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, backgroundHandler)

        } catch (e: Exception) {
            Toast.makeText(this, "Error al iniciar grabacion: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecordingVideo() {
        try {
            isRecording = false
            uiHandler.removeCallbacks(timecodeRunnable)
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
            currentParcelFd?.close()
            currentParcelFd = null

            lastSavedMediaUri?.let { uri ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, values, null, null)
                }
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
            }

            binding.btnRecord.text = "REC"
            binding.txtTimecode.setTextColor(0xFFFFFFFF.toInt())
            Toast.makeText(this, "Video guardado en Galería (DCIM/Camera)", Toast.LENGTH_LONG).show()
            startPreviewSession()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createGalleryVideoFileDescriptor(): ParcelFileDescriptor? {
        val tag = if (openGateMode) "OPENGATE" else "4K"
        val profile = if (logModeEnabled) "CINELOG" else "REC709"
        val fileName = "CINEPRO_${tag}_${profile}_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        lastSavedMediaUri = uri
        return contentResolver.openFileDescriptor(uri, "w")
    }

    /**
     * Escala la imagen de forma proporcional (FIT CENTER) para mostrar el 100% del sensor
     * sin recortar bordes ni al previsualizar ni al grabar en Open Gate, y ajusta el Grid de Tercios.
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_90
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, selectedVideoSize.height.toFloat(), selectedVideoSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)

            // Escala FIT exacta para ver todo el alto y ancho del sensor sin ningun crop
            val scale = minOf(
                viewHeight.toFloat() / selectedVideoSize.height.toFloat(),
                viewWidth.toFloat() / selectedVideoSize.width.toFloat()
            )

            matrix.postScale(scale, scale, centerX, centerY)
            val rotateDegrees = 90f * (rotation - 2)
            matrix.postRotate(rotateDegrees, centerX, centerY)

            // Informar al Grid de Tercios y al Focus Peaking el area exacta del fotograma
            val drawnW = selectedVideoSize.width * scale
            val drawnH = selectedVideoSize.height * scale
            binding.cineOverlays.activeImageRect.set(
                centerX - drawnW / 2f,
                centerY - drawnH / 2f,
                centerX + drawnW / 2f,
                centerY + drawnH / 2f
            )
            binding.cineOverlays.invalidate()
        }
        binding.viewfinder.setTransform(matrix)
    }

    private fun getVideoOrientationHint(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_90
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 0
            Surface.ROTATION_270 -> 180
            Surface.ROTATION_0 -> 90
            Surface.ROTATION_180 -> 270
            else -> 0
        }
    }

    private fun reopenCamera() {
        captureSession?.close()
        cameraDevice?.close()
        if (binding.viewfinder.isAvailable) {
            openCamera(binding.viewfinder.width, binding.viewfinder.height)
        }
    }

    private fun updateBatteryStatus() {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
        binding.txtBattery.text = "$level%"
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        displayManager.registerDisplayListener(displayListener, null)
        backgroundThread = HandlerThread("CineProCameraThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        if (binding.viewfinder.isAvailable && hasPermissions()) {
            openCamera(binding.viewfinder.width, binding.viewfinder.height)
        }
    }

    override fun onPause() {
        displayManager.unregisterDisplayListener(displayListener)
        if (isRecording) stopRecordingVideo()
        captureSession?.close()
        cameraDevice?.close()
        rawImageReader?.close()
        jpegImageReader?.close()
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
        super.onPause()
    }
}
