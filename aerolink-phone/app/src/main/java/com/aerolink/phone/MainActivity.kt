package com.aerolink.phone

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.widget.Button
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
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var mode: TextView
    private lateinit var instruction: TextView
    private lateinit var telemetry: TextView
    private lateinit var selectFile: Button
    private lateinit var discover: Button
    private lateinit var cancel: Button

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
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

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedUri = uri
        if (uri != null) {
            armed = false
            sending = false
            instruction.text = "Pinch · move toward receiver · open your hand"
            telemetry.text = "Selected: ${displayName(uri)} · release your hand to send"
            startCamera()
        }
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else telemetry.text = "Camera permission is needed for gesture detection."
        }

    private val saveFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
            if (target == null) {
                status.text = "FILE NOT SAVED"
                instruction.text = "You can receive again or retry."
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
        selectFile = findViewById(R.id.selectFile)
        discover = findViewById(R.id.discover)
        cancel = findViewById(R.id.cancel)

        findViewById<Button>(R.id.senderMode).setOnClickListener { chooseSender() }
        findViewById<Button>(R.id.receiverMode).setOnClickListener { chooseReceiver() }
        selectFile.setOnClickListener { picker.launch("*/*") }
        discover.setOnClickListener { enableDiscoverable() }
        cancel.setOnClickListener { resetWorkflow() }
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
        instruction.text = "Show an open palm and hold it to catch."
        telemetry.text = "Discoverable · waiting for the catch gesture"
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
        stopCamera()
        role = ""
        selectedUri = null
        selectFile.visibility = Button.GONE
        discover.visibility = Button.GONE
        cancel.visibility = Button.GONE
        mode.text = "TWO ESP32 NODES · TWO PHONES"
        instruction.text = "Choose how this phone will take part."
        telemetry.text = "Connect to this phone's AEROLINK ESP32 Wi-Fi network."
        status.text = "READY"
    }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        setupLandmarker()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
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
                    // A dropped frame should not stop the live camera pipeline.
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
            runOnUiThread { status.text = "CAMERA ACTIVE" }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
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
            .setMinHandDetectionConfidence(0.55f)
            .setMinTrackingConfidence(0.55f)
            .setMinHandPresenceConfidence(0.55f)
            .setResultListener { result, _ -> onHandLandmarks(result) }
            .build()
        landmarker = HandLandmarker.createFromOptions(this, options)
    }

    private fun onHandLandmarks(result: HandLandmarkerResult) {
        val hand = result.landmarks().firstOrNull()
        if (hand == null) {
            pinchSince = 0L
            openPalmSince = 0L
            return
        }

        fun distance(a: Int, b: Int): Float =
            hypot(hand[a].x() - hand[b].x(), hand[a].y() - hand[b].y())

        val palmScale = distance(0, 9).coerceAtLeast(0.04f)
        val pinching = distance(4, 8) / palmScale < 0.38f

        // Count fingers whose tips are farther from the wrist than their PIP joints.
        val extendedPairs = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)
        val extendedFingers = extendedPairs.count { (tip, pip) ->
            distance(0, tip) > distance(0, pip) * 1.12f
        }
        val openPalm = extendedFingers >= 3

        if (role == "sender" && selectedUri != null && !sending) {
            if (pinching) {
                openPalmSince = 0L
                if (pinchSince == 0L) {
                    pinchSince = SystemClock.uptimeMillis()
                    pinchOriginX = hand[0].x()
                    pinchOriginY = hand[0].y()
                    handMoved = false
                }
                if (!armed && SystemClock.uptimeMillis() - pinchSince >= 450L) {
                    armed = true
                    runOnUiThread {
                        status.text = "PINCH LOCKED"
                        instruction.text = "Move toward the receiver, then open your hand."
                        telemetry.text = "Gesture detected · waiting for release"
                    }
                }
                if (armed && hypot(hand[0].x() - pinchOriginX, hand[0].y() - pinchOriginY) > 0.10f) {
                    handMoved = true
                }
            } else {
                pinchSince = 0L
                if (armed && openPalm && handMoved) {
                    sending = true
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
                if (openPalmSince == 0L) openPalmSince = SystemClock.uptimeMillis()
                if (SystemClock.uptimeMillis() - openPalmSince >= 650L) {
                    receiverPolling = true
                    runOnUiThread {
                        status.text = "CATCH GESTURE"
                        instruction.text = "Checking for a received file…"
                        telemetry.text = "Gesture accepted · asking ESP32-RX for transfer status"
                    }
                    lifecycleScope.launch { waitForIncomingFile() }
                }
            } else {
                openPalmSince = 0L
            }
        }
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
            withContext(Dispatchers.Main) {
                status.text = "SENT"
                instruction.text = "Transfer reached the receiver ESP32."
                telemetry.text = "File sent successfully · receiver can catch and save it."
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                sending = false
                armed = false
                handMoved = false
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
                        withContext(Dispatchers.Main) {
                            status.text = "FILE READY"
                            instruction.text = "Received $name. Save it to this phone."
                            telemetry.text = "Complete file detected · choose where to save."
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
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status.text = "SAVE FAILED"
                    instruction.text = "Try downloading the file again."
                    telemetry.text = e.message ?: "Unknown save error."
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
