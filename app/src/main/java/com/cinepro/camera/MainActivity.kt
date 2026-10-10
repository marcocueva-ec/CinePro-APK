package com.cinepro.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.media.MediaRecorder
import android.os.*
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cinepro.camera.databinding.ActivityMainBinding
import java.io.FileDescriptor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var mediaRecorder: MediaRecorder? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Estado de Camara y Sensor
    private var currentCameraId: String = "0"
    private var availableCameraIds: List<String> = emptyList()
    private var currentCameraIndex = 0

    private var openGateMode: Boolean = true // true = Open Gate (Max 4:3 Sensor), false = 4K UHD 16:9
    private var logModeEnabled: Boolean = true // Curva Logaritmica + Edge Off + NR Off
    private var selectedFps: Int = 24
    private var isRecording: Boolean = false

    // Parametros Manuales del Sensor
    private var isoRange: Range<Int> = Range(100, 3200)
    private var exposureTimeRange: Range<Long> = Range(1_000_000L, 100_000_000L)
    private var minFocusDist: Float = 10f
    private var sensorActiveArray: Rect = Rect(0, 0, 4000, 3000)
    private var maxZoom: Float = 8.0f
    private var availableApertures: FloatArray = floatArrayOf(1.8f)

    private var currentIso: Int = 400
    private var currentShutterNs: Long = 20_833_333L // 1/48s en nanosegundos
    private var currentFocusDist: Float = 0f // 0f = Autofoco continuo, >0 = Manual
    private var currentKelvin: Int = 5600
    private var currentZoomRatio: Float = 1.0f
    private var currentAperture: Float = 1.8f

    private var selectedVideoSize: Size = Size(3840, 2160)

    private val isoSteps = listOf(50, 100, 160, 200, 320, 400, 640, 800, 1250, 1600, 2500, 3200, 6400)
    private val shutterStepsDenom = listOf(24, 30, 48, 50, 60, 100, 120, 250, 500, 1000, 2000, 4000)
    private val fpsOptions = listOf(24, 30, 60)

    // Timer de Timecode
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
        setupUIControls()
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

    private fun setupUIControls() {
        // Configurar estilos de los 5 diales en arco
        binding.dialIso.accentMode = 1 // Cyan + Rojo en ISOs altos
        binding.dialWb.accentMode = 2  // Calido / Frio

        // 1. Dial FOCUS
        binding.dialFocus.progress = 0f
        binding.dialFocus.onValueChanged = { progress ->
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

        // 2. Dial ISO
        binding.dialIso.progress = 0.35f
        binding.dialIso.onValueChanged = { progress ->
            val idx = (progress * (isoSteps.size - 1)).roundToInt()
            val rawIso = isoSteps[idx]
            currentIso = rawIso.coerceIn(isoRange.lower, isoRange.upper)
            binding.valIso.text = currentIso.toString()
            binding.rgbHistogram.updateFromExposure(progress, binding.dialShutter.progress)
            applyParametersToSession()
        }

        // 3. Dial SHUTTER (Regla de 180 grados incluida: 1/48s, 1/60s, etc.)
        binding.dialShutter.progress = 0.2f
        binding.dialShutter.onValueChanged = { progress ->
            val idx = (progress * (shutterStepsDenom.size - 1)).roundToInt()
            val denom = shutterStepsDenom[idx]
            currentShutterNs = (1_000_000_000L / denom).coerceIn(exposureTimeRange.lower, exposureTimeRange.upper)
            binding.valShutter.text = "1/${denom}s"
            binding.rgbHistogram.updateFromExposure(binding.dialIso.progress, progress)
            applyParametersToSession()
        }

        // 4. Dial WB (2500K a 10000K con matriz RGGB)
        binding.dialWb.progress = 0.42f
        binding.dialWb.onValueChanged = { progress ->
            currentKelvin = (2500 + (progress * 7500).roundToInt() / 100 * 100)
            binding.valWb.text = "${currentKelvin}K"
            applyParametersToSession()
        }

        // 5. Dial IRIS
        binding.dialIris.progress = 0.5f
        binding.dialIris.onValueChanged = { progress ->
            if (availableApertures.size > 1) {
                val idx = (progress * (availableApertures.size - 1)).roundToInt()
                currentAperture = availableApertures[idx]
                binding.valIris.text = "f/${currentAperture}"
                applyParametersToSession()
            } else {
                binding.valIris.text = "f/${availableApertures.firstOrNull() ?: 1.8f}"
            }
        }

        // Dial Curvo de Zoom Derecho
        binding.curvedZoom.onZoomChanged = { progress ->
            currentZoomRatio = 1.0f + progress * (maxZoom - 1.0f).coerceAtMost(5.0f)
            binding.btnLensSwitch.text = String.format("%.1fx", currentZoomRatio)
            applyParametersToSession()
        }

        // Selector Open Gate vs 4K 16:9
        binding.btnCodecMode.setOnClickListener {
            if (isRecording) return@setOnClickListener
            openGateMode = !openGateMode
            reopenCamera()
        }

        // Selector FPS
        binding.btnFps.setOnClickListener {
            if (isRecording) return@setOnClickListener
            val nextIdx = (fpsOptions.indexOf(selectedFps) + 1) % fpsOptions.size
            selectedFps = fpsOptions[nextIdx]
            binding.btnFps.text = "$selectedFps fps"
            reopenCamera()
        }

        // Toggle LUT Rec.709 Preview (Sin quemar el LUT en el archivo grabado)
        binding.btnLutToggle.setOnClickListener {
            binding.cineOverlays.lutEnabled = !binding.cineOverlays.lutEnabled
            binding.cineOverlays.invalidate()
            binding.btnLutToggle.text = if (binding.cineOverlays.lutEnabled) "LUT REC709: ON" else "LUT REC709: OFF"
            binding.btnLutToggle.setTextColor(if (binding.cineOverlays.lutEnabled) 0xFF38E8FF.toInt() else 0xFFB0BEC5.toInt())
        }

        // Toggle Perfil CineLog (Curva Logaritmica + Sin sobrenitidez)
        binding.btnLogProfile.setOnClickListener {
            logModeEnabled = !logModeEnabled
            binding.btnLogProfile.setTextColor(if (logModeEnabled) 0xFF00E676.toInt() else 0xFFB0BEC5.toInt())
            Toast.makeText(
                this,
                if (logModeEnabled) "CineLog ACTIVO (Curva Log + Nitidez artificial OFF)" else "Perfil Estandar Rec.709",
                Toast.LENGTH_SHORT
            ).show()
            applyParametersToSession()
        }

        // Cambio de Lente Fisica (Ultra-Wide / Wide / Tele)
        binding.btnLensSwitch.setOnClickListener {
            if (isRecording || availableCameraIds.size <= 1) return@setOnClickListener
            currentCameraIndex = (currentCameraIndex + 1) % availableCameraIds.size
            currentCameraId = availableCameraIds[currentCameraIndex]
            currentZoomRatio = 1.0f
            binding.curvedZoom.zoomProgress = 0f
            reopenCamera()
        }

        // Botones de Herramientas Izquierda
        binding.btnGrid.setOnClickListener {
            binding.cineOverlays.showGrid = !binding.cineOverlays.showGrid
            binding.btnGrid.setTextColor(if (binding.cineOverlays.showGrid) 0xFF38E8FF.toInt() else 0xFFB0BEC5.toInt())
            binding.cineOverlays.invalidate()
        }
        binding.btnLevel.setOnClickListener {
            binding.cineOverlays.showLevel = !binding.cineOverlays.showLevel
            binding.btnLevel.setTextColor(if (binding.cineOverlays.showLevel) 0xFF38E8FF.toInt() else 0xFFB0BEC5.toInt())
            binding.cineOverlays.invalidate()
        }

        // BOTON REC
        binding.btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecordingVideo()
            } else {
                startRecordingVideo()
            }
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
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
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
            isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
            exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(1_000_000L, 100_000_000L)
            minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10f
            sensorActiveArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
            maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 8.0f
            availableApertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(1.8f)

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val videoSizes = map?.getOutputSizes(MediaRecorder::class.java) ?: arrayOf(Size(1920, 1080))

            // Seleccionar Open Gate (Maxima resolucion 4:3 / 3:2 completa del sensor) o 4K UHD 16:9
            selectedVideoSize = chooseOptimalVideoSize(videoSizes, openGateMode)
            val modeLabel = if (openGateMode) "OPEN GATE ${selectedVideoSize.width}x${selectedVideoSize.height}" else "4K UHD ${selectedVideoSize.width}x${selectedVideoSize.height}"
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
            // Buscar resolucion 4:3 o 3:2 de mayor area soportada por el codificador de hardware (Open Gate)
            val openGateCandidate = sizes
                .filter {
                    val ratio = it.width.toFloat() / it.height.toFloat()
                    (ratio in 1.25f..1.55f) && it.width <= 4096
                }
                .maxByOrNull { it.width * it.height }
            if (openGateCandidate != null && openGateCandidate.width >= 2560) {
                return openGateCandidate
            }
        }
        // 4K UHD (3840x2160) o maxima resolucion 16:9 disponible
        return sizes.firstOrNull { it.width == 3840 && it.height == 2160 }
            ?: sizes.maxByOrNull { it.width * it.height }
            ?: Size(1920, 1080)
    }

    private fun startPreviewSession() {
        val device = cameraDevice ?: return
        val texture = binding.viewfinder.surfaceTexture ?: return
        texture.setDefaultBufferSize(selectedVideoSize.width, selectedVideoSize.height)
        val previewSurface = Surface(texture)

        previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
        }

        device.createCaptureSession(listOf(previewSurface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                applyParametersToSession()
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, backgroundHandler)
    }

    /**
     * Aplica todos los controles manuales del sensor y desactiva el procesado artificial (ISP Bypass)
     * para lograr calidad cinematografica real competitiva con Blackmagic Camera.
     */
    private fun applyParametersToSession() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        try {
            // 1. Exposicion 100% Manual (ISO + Shutter Speed en nanosegundos)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentShutterNs)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(selectedFps, selectedFps))

            // 2. Enfoque Manual o Continuo de Cine
            if (currentFocusDist <= 0.01f) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDist)
            }

            // 3. Balance de Blancos Manual en grados Kelvin (Matriz RGGB)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggb(currentKelvin))

            // 4. Apertura Fisica (si el sensor tiene iris variable)
            builder.set(CaptureRequest.LENS_APERTURE, currentAperture)

            // 5. Zoom por Recorte de Matriz Activa del Sensor
            val cropW = (sensorActiveArray.width() / currentZoomRatio).toInt()
            val cropH = (sensorActiveArray.height() / currentZoomRatio).toInt()
            val cropX = (sensorActiveArray.width() - cropW) / 2
            val cropY = (sensorActiveArray.height() - cropH) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))

            // 6. MAXIMA CALIDAD DEL SENSOR: Desactivar Nitidez Digital (Edge Off) y Reduccion de Ruido Plastica + Curva CineLog
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
     * Genera una curva logaritmica de 32 puntos (CineLog) que levanta las sombras,
     * preserva el rango dinamico en altas luces y entrega una señal plana ideal para DaVinci Resolve.
     */
    private fun buildCineLogTonemapCurve(): TonemapCurve {
        val points = FloatArray(64)
        for (i in 0 until 32) {
            val x = i.toFloat() / 31f
            // Formula logaritmica suave inspirada en S-Log3 / Blackmagic Film Gen 5
            val y = (0.14f + 0.82f * (ln(1f + 18f * x) / ln(19f))).coerceIn(0f, 1f)
            points[i * 2] = x
            points[i * 2 + 1] = y
        }
        return TonemapCurve(points, points, points)
    }

    /**
     * Convierte temperatura de color Kelvin (2500K - 10000K) en ganancias de canal RGGB del sensor.
     */
    private fun kelvinToRggb(kelvin: Int): RggbChannelVector {
        val temp = kelvin / 100.0
        val r = if (temp <= 66) 1.0f else (1.292936 * (temp - 60).pow(-0.1332047)).toFloat().coerceIn(0.5f, 3.0f)
        val g = if (temp <= 66) {
            (0.3900815 * ln(temp) - 0.6318414).toFloat().coerceIn(0.5f, 3.0f)
        } else {
            (1.1298908 * (temp - 60).pow(-0.0755148)).toFloat().coerceIn(0.5f, 3.0f)
        }
        val b = when {
            temp >= 66 -> 1.0f
            temp <= 19 -> 0.5f
            else -> (0.5432067 * ln(temp - 10) - 1.196254).toFloat().coerceIn(0.5f, 3.0f)
        }
        // Invertir para compensar iluminante hacia el punto blanco deseado
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
            val fileDescriptor = createMediaStoreVideoFile() ?: return
            mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()).apply {
                // Usar fuente de audio sin procesar (UNPROCESSED) para evitar compresion automatica AGC
                setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(fileDescriptor)
                // Alto Bitrate Cinematografico: 150 Mbps en HEVC (H.265)
                setVideoEncodingBitRate(150_000_000)
                setVideoFrameRate(selectedFps)
                setVideoSize(selectedVideoSize.width, selectedVideoSize.height)
                setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(320_000)
                setAudioSamplingRate(48_000)
                prepare()
            }

            val texture = binding.viewfinder.surfaceTexture!!
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
            Toast.makeText(this, "Error iniciando grabacion: ${e.message}", Toast.LENGTH_LONG).show()
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
            binding.btnRecord.text = "REC"
            binding.txtTimecode.setTextColor(0xFFFFFFFF.toInt())
            Toast.makeText(this, "Clip guardado en Galeria (Movies/CinePro_Log)", Toast.LENGTH_SHORT).show()
            startPreviewSession()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createMediaStoreVideoFile(): FileDescriptor? {
        val tag = if (openGateMode) "OPENGATE" else "4K"
        val profile = if (logModeEnabled) "CINELOG" else "REC709"
        val fileName = "CINEPRO_${tag}_${profile}_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CinePro_Log")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return contentResolver.openFileDescriptor(uri, "w")?.fileDescriptor
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val matrix = Matrix()
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        // Mantener relacion de aspecto exacta (4:3 en Open Gate con barras laterales o 16:9 en 4K)
        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()
        val videoRatio = selectedVideoSize.width.toFloat() / selectedVideoSize.height.toFloat()
        if (videoRatio < viewRatio) {
            // Open Gate (ej. 4:3 dentro de pantalla 16:9): escalar ancho para ver el sensor completo sin recorte
            val scaleX = videoRatio / viewRatio
            matrix.postScale(scaleX, 1f, centerX, centerY)
        }
        binding.viewfinder.setTransform(matrix)
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
        backgroundThread = HandlerThread("CineProCameraThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        if (binding.viewfinder.isAvailable && hasPermissions()) {
            openCamera(binding.viewfinder.width, binding.viewfinder.height)
        }
    }

    override fun onPause() {
        if (isRecording) stopRecordingVideo()
        captureSession?.close()
        cameraDevice?.close()
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
        super.onPause()
    }
}
