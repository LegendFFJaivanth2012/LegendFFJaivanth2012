package com.aerolink.phone

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var mode: TextView
    private lateinit var instruction: TextView
    private lateinit var telemetry: TextView
    private lateinit var selectFile: Button
    private lateinit var discover: Button

    private var role = ""
    private var selectedUri: Uri? = null
    private var landmarker: HandLandmarker? = null
    private var armed = false
    private var sent = false
    private var pinchSince = 0L
    private var receiverTriggered = false

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedUri = uri
        if (uri != null) {
            instruction.text = "Hold a pinch for 0.45 s"
            telemetry.text = "Selected: " + displayName(uri)
            startCamera()
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else telemetry.text = "Camera permission is required for gesture detection."
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

        findViewById<Button>(R.id.senderMode).setOnClickListener {
            role = "sender"
            selectFile.visibility = Button.VISIBLE
            discover.visibility = Button.GONE
            mode.text = "SENDER MODE"
            instruction.text = "Select a file, then pinch."
            telemetry.text = "Phone → ESP32-TX → wired bridge → ESP32-RX → phone"
        }

        findViewById<Button>(R.id.receiverMode).setOnClickListener {
            role = "receiver"
            selectFile.visibility = Button.GONE
            discover.visibility = Button.VISIBLE
            mode.text = "RECEIVER MODE"
            instruction.text = "Turn on discoverable mode."
            telemetry.text = "Receiver ESP32: http://192.168.4.1"
        }

        selectFile.setOnClickListener { picker.launch("*/*") }
        discover.setOnClickListener {
            instruction.text = "Show your open hand to accept."
            status.text = "DISCOVERABLE"
            startCamera()
        }
    }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }

        setupLandmarker()

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { image ->
                try {
                    val bitmap = image.toBitmap()
                    landmarker?.detectAsync(BitmapImageBuilder(bitmap).build(), System.currentTimeMillis())
                } catch (_: Exception) {
                } finally {
                    image.close()
                }
            }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUseCase, analysis)
            status.text = "CAMERA ACTIVE"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupLandmarker() {
        if (landmarker != null) return
        val base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.55f)
            .setMinTrackingConfidence(0.55f)
            .setMinHandPresenceConfidence(0.55f)
            .setResultListener { result, _ -> onHand(result) }
            .build()
        landmarker = HandLandmarker.createFromOptions(this, options)
    }

    private fun onHand(result: HandLandmarkerResult) {
        val hand = result.landmarks().firstOrNull() ?: run {
            if (role == "sender") pinchSince = 0L
            return
        }

        val wrist = hand[0]
        val thumb = hand[4]
        val index = hand[8]
        val middle = hand[12]
        val ring = hand[16]
        val pinky = hand[20]

        val palm = hypot(wrist.x() - middle.x(), wrist.y() - middle.y()).coerceAtLeast(0.08f)
        val pinchDistance = hypot(thumb.x() - index.x(), thumb.y() - index.y()) / palm
        val pinch = pinchDistance < 0.42f

        val openScore =
            hypot(wrist.x() - index.x(), wrist.y() - index.y()) +
            hypot(wrist.x() - middle.x(), wrist.y() - middle.y()) +
            hypot(wrist.x() - ring.x(), wrist.y() - ring.y()) +
            hypot(wrist.x() - pinky.x(), wrist.y() - pinky.y())
        val openHand = openScore / 4f > 0.32f

        if (role == "sender") {
            if (pinch && !armed) {
                if (pinchSince == 0L) pinchSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - pinchSince > 450L) {
                    armed = true
                    runOnUiThread {
                        status.text = "ARMED"
                        instruction.text = "Move into position · release to send"
                        telemetry.text = "Pinch lock acquired"
                    }
                }
            } else if (!pinch) {
                pinchSince = 0L
                if (armed && !sent) {
                    sent = true
                    runOnUiThread {
                        status.text = "SENDING"
                        instruction.text = "Transfer in progress…"
                    }
                    lifecycleScope.launch { uploadSelectedFile() }
                }
            }
        } else if (openHand && !receiverTriggered) {
            receiverTriggered = true
            runOnUiThread {
                status.text = "ACCEPTING"
                instruction.text = "Checking receiver ESP32…"
            }
            lifecycleScope.launch { pollReceiver() }
        }
    }

    private suspend fun uploadSelectedFile() {
        val uri = selectedUri ?: return
        try {
            withContext(Dispatchers.IO) {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read file")
                val id = UUID.randomUUID().toString().replace("-", "").take(12)
                val chunkSize = 4096
                val total = (bytes.size + chunkSize - 1) / chunkSize

                for (seq in 0 until total) {
                    val start = seq * chunkSize
                    val end = minOf(bytes.size, start + chunkSize)
                    postChunk(id, seq, total, displayName(uri), bytes.copyOfRange(start, end))
                }
                withContext(Dispatchers.Main) {
                    status.text = "SENT"
                    instruction.text = "File delivered to receiver ESP32"
                    telemetry.text = bytes.size.toString() + " bytes · " + total + " packets"
                }
            }
        } catch (e: Exception) {
            sent = false
            withContext(Dispatchers.Main) {
                status.text = "ERROR"
                instruction.text = "Transfer failed"
                telemetry.text = e.message ?: "ESP32 connection failed"
            }
        }
    }

    private fun postChunk(id: String, seq: Int, total: Int, name: String, payload: ByteArray) {
        val c = URL("http://192.168.4.1/api/chunk").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 4000
        c.readTimeout = 4000
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setRequestProperty("X-Aero-Transfer", id)
        c.setRequestProperty("X-Aero-Seq", seq.toString())
        c.setRequestProperty("X-Aero-Total", total.toString())
        c.setRequestProperty("X-Aero-Name", name)
        DataOutputStream(c.outputStream).use { it.write(payload) }
        if (c.responseCode !in 200..299) error("ESP32 rejected packet " + seq)
        c.disconnect()
    }

    private suspend fun pollReceiver() {
        withContext(Dispatchers.IO) {
            try {
                val c = URL("http://192.168.4.1/api/status").openConnection() as HttpURLConnection
                c.connectTimeout = 1500
                c.readTimeout = 1500
                val body = c.inputStream.bufferedReader().readText()
                c.disconnect()
                withContext(Dispatchers.Main) {
                    status.text = "RECEIVER ONLINE"
                    instruction.text = "Transfer is ready"
                    telemetry.text = body
                }
            } catch (e: Exception) {
                receiverTriggered = false
                withContext(Dispatchers.Main) {
                    status.text = "WAITING"
                    instruction.text = "Keep your hand open to retry"
                    telemetry.text = "ESP32-RX not reachable"
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        var name = "payload.bin"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i)
        }
        return name
    }

    override fun onDestroy() {
        landmarker?.close()
        landmarker = null
        super.onDestroy()
    }
}
