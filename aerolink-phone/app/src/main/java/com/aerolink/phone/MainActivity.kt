package com.aerolink.phone

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var mode: TextView
    private lateinit var instruction: TextView
    private lateinit var telemetry: TextView
    private lateinit var sensitivityLabel: TextView
    private lateinit var selectFile: Button
    private lateinit var discover: Button
    private lateinit var cancel: Button
    private lateinit var trainGestures: Button
    private lateinit var sensitivity: SeekBar

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val profilePrefs by lazy { getSharedPreferences("aerolink_gesture_profile", MODE_PRIVATE) }

    private var role = ""
    private var selectedUri: Uri? = null
    private var landmarker: HandLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var pinchSince = 0L
    private var pinchOriginX = 0f
    private var pinchOriginY = 0f
    private var handMoved = false
    private var openPalmSince = 0L
    private var armed = false
    private var sending = false
    private var receiverPolling = false
    private var pendingFileName = "aerolink-received.bin"

    @Volatile private var trainingStage = TrainingStage.NONE
    private var trainingStartedAt = 0L
    private val trainingSamples = mutableListOf<Float>()
    private var lastHandSeenAt = 0L
    private var smoothedPinchMetric = Float.NaN
    private var lastAcceptedPinchMetric = Float.NaN
    private var lastAcceptedPalmMetric = Float.NaN

    private var pinchStats = RunningStats()
    private var palmStats = RunningStats()

    private enum class TrainingStage { NONE, PINCH, OPEN_PALM }

    private data class RunningStats(
        var count: Int = 0,
        var mean: Double = 0.0,
        var m2: Double = 0.0
    ) {
        fun add(value: Float) {
            if (!value.isFinite()) return
            count += 1
            val delta = value.toDouble() - mean
            mean += delta / count
            val deltaAfter = value.toDouble() - mean
            m2 += delta * deltaAfter
        }

        fun standardDeviation(): Double =
            if (count > 1) sqrt(m2 / (count - 1)) else 0.0
    }

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedUri = uri
        if (uri != null) {
            armed = false
            sending = false
            pinchSince = 0L
            instruction.text = "Pinch · move toward receiver · open your hand"
            telemetry.text = "Selected: ${displayName(uri)} · your trained gesture profile is active"
            startCamera()
        }
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                trainingStage = TrainingStage.NONE
                telemetry.text = "Camera permission is needed for gesture detection and training."
            }
        }

    private val saveFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
            if (target == null) {
                status.text = "FILE NOT SAVED"
                instruction.text = "You can enable catch mode again and retry."
                receiverPolling = false
                return@registerForActivityResult
            }
            lifecycleScope.launch { downloadReceivedFile(target) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        mode = findViewById(R.id.mode)
        instruction = findViewById(R.id.instruction)
        telemetry = findViewById(R.id.telemetry)
        sensitivityLabel = findViewById(R.id.sensitivityLabel)
        selectFile = findViewById(R.id.selectFile)
        discover = findViewById(R.id.discover)
        cancel = findViewById(R.id.cancel)
        trainGestures = findViewById(R.id.trainGestures)
        sensitivity = findViewById(R.id.sensitivity)

        loadGestureProfile()
        val savedSensitivity = profilePrefs.getInt("sensitivity", 65).coerceIn(0, 100)
        sensitivity.progress = savedSensitivity
        updateSensitivityLabel(savedSensitivity)

        findViewById<Button>(R.id.senderMode).setOnClickListener { chooseSender() }
        findViewById<Button>(R.id.receiverMode).setOnClickListener { chooseReceiver() }
        selectFile.setOnClickListener { picker.launch("*/*") }
        discover.setOnClickListener { enableDiscoverable() }
        cancel.setOnClickListener { resetWorkflow() }
        trainGestures.setOnClickListener { startGestureTraining() }

        sensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSensitivityLabel(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                profilePrefs.edit().putInt("sensitivity", sensitivity.progress).apply()
                telemetry.text = "Sensitivity saved on this phone · ${learnedExampleCount()} learned examples"
            }
        })

        updateTrainingButton()
    }

    private fun chooseSender() {
        role = "sender"
        receiverPolling = false
        armed = false
        sending = false
        selectFile.visibility = Button.VISIBLE
        discover.visibility = Button.GONE
        cancel.visibility = Button.VISIBLE
        mode.text = "SENDER · JOIN AEROLINK-TX"
        instruction.text = "Choose a file to begin."
        telemetry.text = "Phone A connects to ESP32-A Wi-Fi: AEROLINK-TX · password: aerolink123"
        status.text = "READY"
        stopCamera()
    }

    private fun chooseReceiver() {
        role = "receiver"
        receiverPolling = false
        selectFile.visibility = Button.GONE
        discover.visibility = Button.VISIBLE
        cancel.visibility = Button.VISIBLE
        mode.text = "RECEIVER · JOIN AEROLINK-RX"
        instruction.text = "Turn on discoverable mode."
        telemetry.text = "Phone B connects to ESP32-B Wi-Fi: AEROLINK-RX · password: aerolink123"
        status.text = "READY"
        stopCamera()
    }

    private fun enableDiscoverable() {
        if (role != "receiver") return
        receiverPolling = false
        openPalmSince = 0L
        lastAcceptedPalmMetric = Float.NaN
        instruction.text = "Show an open palm and hold it to catch."
        telemetry.text = "Discoverable · sensitivity ${sensitivity.progress}% · learned profile active"
        status.text = "DISCOVERABLE"
        startCamera()
    }

    private fun resetWorkflow() {
        receiverPolling = false
        armed = false
        sending = false
        pinchSince = 0L
        handMoved = false
        openPalmSince = 0L
        trainingStage = TrainingStage.NONE
        stopCamera()
        role = ""
        selectedUri = null
        selectFile.visibility = Button.GONE
        discover.visibility = Button.GONE
        cancel.visibility = Button.GONE
        mode.text = "TWO ESP32 NODES · TWO PHONES"
        instruction.text = "Choose a mode, or train your hand profile first."
        telemetry.text = "Connect this phone to its AEROLINK ESP32 Wi-Fi network."
        status.text = "READY"
    }

    private fun startGestureTraining() {
        if (sending) {
            telemetry.text = "Finish the current transfer before training."
            return
        }
        trainingSamples.clear()
        trainingStage = TrainingStage.PINCH
        trainingStartedAt = SystemClock.uptimeMillis()
        pinchSince = 0L
        openPalmSince = 0L
        armed = false
        handMoved = false
        status.text = "TRAINING · 1/2"
        instruction.text = "Hold a comfortable thumb–index pinch in front of the camera."
        telemetry.text = "Training step 1 of 2 · keep the pinch steady until the app advances."
        startCamera()
    }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        try {
            setupLandmarker()
        } catch (e: Exception) {
            status.text = "HAND MODEL ERROR"
            telemetry.text = e.message ?: "The bundled hand model could not be loaded."
            trainingStage = TrainingStage.NONE
            return
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val previewUseCase = Preview.Builder().build().also {
                    it.setSurfaceProvider(preview.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { image ->
                    try {
                        val bitmap: Bitmap = image.toBitmap()
                        val mpImage = BitmapImageBuilder(bitmap).build()
                        val imageOptions = ImageProcessingOptions.builder()
                            .setRotationDegrees(image.imageInfo.rotationDegrees)
                            .build()
                        landmarker?.detectAsync(mpImage, imageOptions, SystemClock.uptimeMillis())
                    } catch (_: Exception) {
                        // A dropped frame should not interrupt the live camera pipeline.
                    } finally {
                        image.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    previewUseCase,
                    analysis
                )
                runOnUiThread {
                    if (trainingStage == TrainingStage.NONE) {
                        status.text = if (role.isBlank()) "CAMERA ACTIVE" else "CAMERA ACTIVE"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "CAMERA ERROR"
                    telemetry.text = e.message ?: "Could not start the front camera."
                    trainingStage = TrainingStage.NONE
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        lastHandSeenAt = 0L
        smoothedPinchMetric = Float.NaN
    }

    private fun setupLandmarker() {
        if (landmarker != null) return
        val base = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            // Slightly lower confidence gates improve reacquisition; gesture checks below
            // provide the stricter confirmation to prevent casual false triggers.
            .setMinHandDetectionConfidence(0.42f)
            .setMinTrackingConfidence(0.45f)
            .setMinHandPresenceConfidence(0.42f)
            .setResultListener { result, _ -> onHandLandmarks(result) }
            .build()
        landmarker = HandLandmarker.createFromOptions(this, options)
    }

    private fun onHandLandmarks(result: HandLandmarkerResult) {
        val now = SystemClock.uptimeMillis()
        val hand = result.landmarks().firstOrNull()
        if (hand == null) {
            // Ignore a brief one-frame dropout, but clear the gesture if the hand disappears.
            if (lastHandSeenAt == 0L || now - lastHandSeenAt > 260L) {
                pinchSince = 0L
                openPalmSince = 0L
                armed = false
                handMoved = false
                smoothedPinchMetric = Float.NaN
            }
            if (trainingStage != TrainingStage.NONE && now - trainingStartedAt > 18_000L) {
                failTrainingStep()
            }
            return
        }
        lastHandSeenAt = now

        fun distance(a: Int, b: Int): Float =
            hypot(hand[a].x() - hand[b].x(), hand[a].y() - hand[b].y())

        val palmScale = distance(0, 9).coerceAtLeast(0.035f)
        val rawPinchMetric = distance(4, 8) / palmScale
        smoothedPinchMetric = if (smoothedPinchMetric.isNaN()) {
            rawPinchMetric
        } else {
            0.38f * rawPinchMetric + 0.62f * smoothedPinchMetric
        }
        val pinchMetric = smoothedPinchMetric

        val fingerAngles = listOf(
            angleDegrees(hand[5], hand[6], hand[8]),
            angleDegrees(hand[9], hand[10], hand[12]),
            angleDegrees(hand[13], hand[14], hand[16]),
            angleDegrees(hand[17], hand[18], hand[20])
        )
        val palmScore = fingerAngles.average().toFloat()
        val sensitivityValue = sensitivity.progress
        val sensitivityOffset = (sensitivityValue - 50) / 50f
        val pinchThreshold = (trainedPinchThreshold() + sensitivityOffset * 0.075f)
            .coerceIn(0.22f, 0.68f)
        val openAngleThreshold = (trainedPalmThreshold() - sensitivityOffset * 8.0f)
            .coerceIn(125f, 168f)
        val extendedPairs = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)
        val extendedFingers = extendedPairs.count { (tip, pip) ->
            val angle = fingerAngles[extendedPairs.indexOf(tip to pip)]
            angle >= openAngleThreshold && distance(0, tip) > distance(0, pip) * 1.015f
        }
        val openPalm = extendedFingers >= 3
        val movementThreshold = 0.13f - (sensitivityValue / 100f) * 0.075f
        val pinchHoldMs = (600L - sensitivityValue * 3L).coerceAtLeast(260L)
        val palmHoldMs = (800L - sensitivityValue * 3L).coerceAtLeast(420L)

        if (trainingStage != TrainingStage.NONE) {
            collectTrainingSample(pinchMetric, palmScore, extendedFingers, now)
            return
        }

        // Use the palm centre rather than the wrist so hand rotation is less likely
        // to look like a deliberate movement.
        val palmCenterX = (hand[0].x() + hand[5].x() + hand[9].x() + hand[13].x() + hand[17].x()) / 5f
        val palmCenterY = (hand[0].y() + hand[5].y() + hand[9].y() + hand[13].y() + hand[17].y()) / 5f

        if (role == "sender" && selectedUri != null && !sending) {
            if (pinchMetric < pinchThreshold) {
                openPalmSince = 0L
                if (pinchSince == 0L) {
                    pinchSince = now
                    pinchOriginX = palmCenterX
                    pinchOriginY = palmCenterY
                    handMoved = false
                }
                if (!armed && now - pinchSince >= pinchHoldMs) {
                    armed = true
                    lastAcceptedPinchMetric = pinchMetric
                    runOnUiThread {
                        status.text = "PINCH LOCKED"
                        instruction.text = "Move your pinched hand toward the receiver, then open it."
                        telemetry.text = "Personal profile active · pinch learned · release when ready"
                    }
                }
                if (armed && hypot(palmCenterX - pinchOriginX, palmCenterY - pinchOriginY) > movementThreshold) {
                    handMoved = true
                }
            } else {
                pinchSince = 0L
                if (armed && openPalm && handMoved) {
                    sending = true
                    lastAcceptedPalmMetric = palmScore
                    runOnUiThread {
                        status.text = "SENDING"
                        instruction.text = "Your file is travelling through the ESP32 bridge…"
                    }
                    lifecycleScope.launch { sendSelectedFile() }
                } else if (armed && openPalm && !handMoved) {
                    runOnUiThread {
                        instruction.text = "Move your pinched hand toward the receiver first."
                    }
                }
            }
        } else if (role == "receiver" && !receiverPolling) {
            if (openPalm) {
                if (openPalmSince == 0L) openPalmSince = now
                if (now - openPalmSince >= palmHoldMs) {
                    receiverPolling = true
                    lastAcceptedPalmMetric = palmScore
                    runOnUiThread {
                        status.text = "CATCH GESTURE"
                        instruction.text = "Checking for a received file…"
                        telemetry.text = "Catch accepted · checking the ESP32 receiver"
                    }
                    lifecycleScope.launch { waitForIncomingFile() }
                }
            } else {
                openPalmSince = 0L
            }
        }
    }

    private fun collectTrainingSample(pinchMetric: Float, palmScore: Float, extendedFingers: Int, now: Long) {
        if (now - trainingStartedAt > 18_000L) {
            failTrainingStep()
            return
        }
        when (trainingStage) {
            TrainingStage.PINCH -> {
                // Only keep frames that look like a real thumb-index pinch.
                if (pinchMetric < 0.82f) trainingSamples.add(pinchMetric)
                if (trainingSamples.size >= 24) {
                    trainingSamples.forEach { pinchStats.add(it) }
                    saveGestureProfile()
                    trainingSamples.clear()
                    trainingStage = TrainingStage.OPEN_PALM
                    trainingStartedAt = now
                    runOnUiThread {
                        status.text = "TRAINING · 2/2"
                        instruction.text = "Now spread your fingers into a relaxed open palm."
                        telemetry.text = "Pinch sample saved · hold your open palm steady to finish training."
                    }
                }
            }
            TrainingStage.OPEN_PALM -> {
                // The relaxed collection gate is intentionally wider than the live gesture gate.
                if (extendedFingers >= 2 && palmScore > 112f) trainingSamples.add(palmScore)
                if (trainingSamples.size >= 24) {
                    trainingSamples.forEach { palmStats.add(it) }
                    saveGestureProfile()
                    trainingSamples.clear()
                    trainingStage = TrainingStage.NONE
                    runOnUiThread {
                        status.text = "TRAINING COMPLETE"
                        instruction.text = "Your hand profile has been updated on this phone."
                        telemetry.text = "${learnedExampleCount()} learned examples · train again after more practice for better personal calibration."
                        updateTrainingButton()
                    }
                }
            }
            TrainingStage.NONE -> Unit
        }
    }

    private fun failTrainingStep() {
        trainingStage = TrainingStage.NONE
        trainingSamples.clear()
        runOnUiThread {
            status.text = "TRAINING PAUSED"
            instruction.text = "Not enough stable hand samples were captured."
            telemetry.text = "Try again with your full hand visible, good lighting, and a steady pose."
            updateTrainingButton()
        }
    }

    private fun angleDegrees(a: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                             b: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                             c: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Float {
        val ax = a.x() - b.x()
        val ay = a.y() - b.y()
        val cx = c.x() - b.x()
        val cy = c.y() - b.y()
        val denominator = (hypot(ax, ay) * hypot(cx, cy)).coerceAtLeast(0.00001f)
        val cosine = ((ax * cx + ay * cy) / denominator).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine).toDouble()).toFloat()
    }

    private fun trainedPinchThreshold(): Float {
        if (pinchStats.count < 8) return 0.38f
        val margin = max(0.045, pinchStats.standardDeviation() * 1.25).toFloat()
        return (pinchStats.mean.toFloat() + margin).coerceIn(0.22f, 0.62f)
    }

    private fun trainedPalmThreshold(): Float {
        if (palmStats.count < 8) return 150f
        val margin = max(12.0, palmStats.standardDeviation() * 1.15).toFloat()
        return (palmStats.mean.toFloat() - margin).coerceIn(125f, 166f)
    }

    private fun loadGestureProfile() {
        pinchStats = RunningStats(
            profilePrefs.getInt("pinch_count", 0),
            profilePrefs.getFloat("pinch_mean", 0f).toDouble(),
            profilePrefs.getFloat("pinch_m2", 0f).toDouble()
        )
        palmStats = RunningStats(
            profilePrefs.getInt("palm_count", 0),
            profilePrefs.getFloat("palm_mean", 0f).toDouble(),
            profilePrefs.getFloat("palm_m2", 0f).toDouble()
        )
    }

    private fun saveGestureProfile() {
        profilePrefs.edit()
            .putInt("pinch_count", pinchStats.count)
            .putFloat("pinch_mean", pinchStats.mean.toFloat())
            .putFloat("pinch_m2", pinchStats.m2.toFloat())
            .putInt("palm_count", palmStats.count)
            .putFloat("palm_mean", palmStats.mean.toFloat())
            .putFloat("palm_m2", palmStats.m2.toFloat())
            .apply()
    }

    private fun learnFromSuccessfulTransfer() {
        var changed = false
        if (lastAcceptedPinchMetric.isFinite()) {
            pinchStats.add(lastAcceptedPinchMetric)
            changed = true
        }
        if (lastAcceptedPalmMetric.isFinite()) {
            palmStats.add(lastAcceptedPalmMetric)
            changed = true
        }
        if (changed) {
            saveGestureProfile()
            runOnUiThread { updateTrainingButton() }
        }
        lastAcceptedPinchMetric = Float.NaN
        lastAcceptedPalmMetric = Float.NaN
    }

    private fun learnedExampleCount(): Int = pinchStats.count + palmStats.count

    private fun updateTrainingButton() {
        trainGestures.text = if (learnedExampleCount() > 0) {
            "TRAIN AGAIN · ${learnedExampleCount()} EXAMPLES SAVED"
        } else {
            "TRAIN MY GESTURES"
        }
    }

    private fun updateSensitivityLabel(value: Int) {
        val level = when {
            value < 30 -> "LOW"
            value < 55 -> "BALANCED"
            value < 80 -> "HIGH"
            else -> "MAX"
        }
        sensitivityLabel.text = "GESTURE SENSITIVITY · $level · $value%"
    }

    private suspend fun sendSelectedFile() {
        val uri = selectedUri ?: return
        try {
            withContext(Dispatchers.IO) {
                val name = displayName(uri)
                val size = fileSize(uri)
                if (size < 0L) error("This file provider did not report its file size.")
                val chunkSize = 1024
                val total = maxOf(1, ((size + chunkSize - 1) / chunkSize).toInt())
                val transferId = UUID.randomUUID().toString().replace("-", "").take(16)
                contentResolver.openInputStream(uri)?.use { input ->
                    for (seq in 0 until total) {
                        val wanted = minOf(chunkSize.toLong(), size - seq.toLong() * chunkSize).toInt().coerceAtLeast(0)
                        val chunk = ByteArray(wanted)
                        var offset = 0
                        while (offset < wanted) {
                            val n = input.read(chunk, offset, wanted - offset)
                            if (n < 0) error("The file ended before all bytes were read.")
                            offset += n
                        }
                        postChunk(transferId, seq, total, name, chunk)
                        withContext(Dispatchers.Main) {
                            val pct = ((seq + 1) * 100 / total)
                            status.text = "SENDING · $pct%"
                            telemetry.text = "$name · packet ${seq + 1} of $total"
                        }
                    }
                } ?: error("Could not open the selected file.")
            }
            learnFromSuccessfulTransfer()
            withContext(Dispatchers.Main) {
                status.text = "SENT"
                instruction.text = "Transfer reached the receiver ESP32."
                telemetry.text = "Sent successfully · gesture profile learned from this completed send."
                sending = false
                armed = false
                handMoved = false
                pinchSince = 0L
                updateTrainingButton()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                sending = false
                armed = false
                handMoved = false
                pinchSince = 0L
                status.text = "TRANSFER ERROR"
                instruction.text = "Could not send the selected file."
                telemetry.text = e.message ?: "Check that Phone A is connected to AEROLINK-TX."
            }
        }
    }

    private fun postChunk(id: String, seq: Int, total: Int, name: String, payload: ByteArray) {
        val query = "id=${enc(id)}&seq=$seq&total=$total&name=${enc(name)}"
        val url = URL("http://192.168.4.1/api/chunk?$query")
        val boundary = "AeroLink" + UUID.randomUUID().toString().replace("-", "")
        val prefix = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"chunk\"; filename=\"part.bin\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val suffix = "\r\n--$boundary--\r\n"
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 5000
        c.readTimeout = 10000
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setFixedLengthStreamingMode(prefix.toByteArray().size + payload.size + suffix.toByteArray().size)
        DataOutputStream(c.outputStream).use { out ->
            out.write(prefix.toByteArray())
            out.write(payload)
            out.write(suffix.toByteArray())
        }
        val response = c.responseCode
        c.disconnect()
        if (response !in 200..299) error("ESP32 returned HTTP $response for packet $seq.")
    }

    private suspend fun waitForIncomingFile() {
        withContext(Dispatchers.IO) {
            try {
                repeat(180) {
                    val c = URL("http://192.168.4.1/api/status").openConnection() as HttpURLConnection
                    c.connectTimeout = 2500
                    c.readTimeout = 2500
                    val body = c.inputStream.bufferedReader().use { it.readText() }
                    c.disconnect()
                    val json = JSONObject(body)
                    val packets = json.optInt("packets", 0)
                    val total = json.optInt("total", 0)
                    val complete = json.optBoolean("complete", false)
                    val name = json.optString("name", "aerolink-received.bin")
                    withContext(Dispatchers.Main) {
                        telemetry.text = "Receiver ESP32 · $packets / $total packets"
                    }
                    if (complete && total > 0) {
                        pendingFileName = name
                        learnFromSuccessfulTransfer()
                        withContext(Dispatchers.Main) {
                            status.text = "FILE READY"
                            instruction.text = "Received $name. Save it to this phone."
                            telemetry.text = "Complete file detected · gesture profile learned from this catch."
                            saveFile.launch(pendingFileName)
                        }
                        return@withContext
                    }
                    delay(900)
                }
                withContext(Dispatchers.Main) {
                    status.text = "WAITING"
                    instruction.text = "No complete file arrived yet."
                    telemetry.text = "Keep discoverable mode on and try again."
                    receiverPolling = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status.text = "ESP32 NOT REACHABLE"
                    instruction.text = "Check the AEROLINK-RX Wi-Fi connection."
                    telemetry.text = e.message ?: "Could not read receiver status."
                    receiverPolling = false
                }
            }
        }
    }

    private suspend fun downloadReceivedFile(target: Uri) {
        withContext(Dispatchers.IO) {
            try {
                val c = URL("http://192.168.4.1/api/file").openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 15000
                if (c.responseCode !in 200..299) error("ESP32 could not provide the received file.")
                contentResolver.openOutputStream(target)?.use { output ->
                    c.inputStream.use { input -> input.copyTo(output) }
                } ?: error("Could not open the destination on this phone.")
                c.disconnect()
                withContext(Dispatchers.Main) {
                    status.text = "SAVED"
                    instruction.text = "Your file is saved on this phone."
                    telemetry.text = pendingFileName
                    receiverPolling = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status.text = "SAVE FAILED"
                    instruction.text = "Try downloading the file again."
                    telemetry.text = e.message ?: "Unknown save error."
                    receiverPolling = false
                }
            }
        }
    }

    private fun fileSize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }

    private fun displayName(uri: Uri): String {
        var name = "aerolink-file.bin"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index) ?: name
            }
        }
        return name.replace("/", "_").replace("\\", "_")
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    override fun onDestroy() {
        stopCamera()
        landmarker?.close()
        landmarker = null
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
