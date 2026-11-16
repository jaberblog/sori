package com.example.objectdetection

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.objectdetection.databinding.ActivityMainBinding
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var isAnalyzing = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    companion object {
        private const val TAG = "ObjectDetection"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val API_URL = "https://platform.doona.ai/v1/chat/completions"
        private const val API_KEY = "sk-a1759da10892fd78bed23f8a0041bb5e2d0d117864ca5bce"
        private const val MODEL = "gpt-6-luna"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Make preview circular
        setupCircularPreview()

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.actionButton.setOnClickListener {
            if (isAnalyzing) return@setOnClickListener
            takePhotoAndDetect()
        }
    }

    private fun setupCircularPreview() {
        binding.previewContainer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        binding.previewContainer.clipToOutline = true

        binding.previewView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        binding.previewView.clipToOutline = true
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
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = binding.previewView.surfaceProvider
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(Size(640, 480))
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
                binding.placeholderText.visibility = View.GONE
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, "خطا در راه‌اندازی دوربین", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhotoAndDetect() {
        val imageCapture = imageCapture ?: return

        isAnalyzing = true
        binding.actionButton.isEnabled = false
        binding.loadingProgress.visibility = View.VISIBLE
        binding.resultText.text = getString(R.string.analyzing)
        binding.actionButton.text = "..."

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()

                    if (bitmap != null) {
                        lifecycleScope.launch {
                            detectObjects(bitmap)
                        }
                    } else {
                        resetUI("خطا در گرفتن تصویر")
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    resetUI("خطا در گرفتن عکس")
                }
            }
        )
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val planeProxy = image.planes[0]
            val buffer = planeProxy.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            // For JPEG format from ImageCapture
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            // Rotate if needed
            val rotationDegrees = image.imageInfo.rotationDegrees
            if (rotationDegrees != 0 && bitmap != null) {
                val matrix = Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert ImageProxy to Bitmap", e)
            null
        }
    }

    private suspend fun detectObjects(bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            try {
                // Resize to reduce payload size
                val scaledBitmap = scaleBitmap(bitmap, 800)
                val base64Image = bitmapToBase64(scaledBitmap)

                val result = callVisionApi(base64Image)

                withContext(Dispatchers.Main) {
                    binding.resultText.text = result
                    binding.actionButton.text = getString(R.string.retest_button)
                    binding.loadingProgress.visibility = View.GONE
                    binding.actionButton.isEnabled = true
                    isAnalyzing = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detection failed", e)
                withContext(Dispatchers.Main) {
                    resetUI("خطا: ${e.message?.take(80) ?: "نامشخص"}")
                }
            }
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val scale = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height, 1f)
        if (scale >= 1f) return bitmap
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun callVisionApi(base64Image: String): String {
        val mediaType = "application/json; charset=utf-8".toMediaType()

        // Standard OpenAI-compatible vision message format
        val requestBodyJson = JsonObject().apply {
            addProperty("model", MODEL)
            add("messages", gson.toJsonTree(listOf(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf(
                            "type" to "text",
                            "text" to "این تصویر را دقیق بررسی کن و بگو چه اجسامی در آن دیده می‌شود. پاسخ را به فارسی و خیلی کوتاه و واضح بده. فقط اجسام اصلی را نام ببر. مثال: «یک انسان هست» یا «یک صندلی و یک میز»."
                        ),
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf(
                                "url" to "data:image/jpeg;base64,$base64Image"
                            )
                        )
                    )
                )
            )))
            addProperty("stream", false) // non-stream for simpler parsing
            addProperty("temperature", 0)
            addProperty("max_tokens", 300)
            // reasoning_effort may not be supported by all models; include if needed
            addProperty("reasoning_effort", "xhigh")
        }

        val requestBody = requestBodyJson.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $API_KEY")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string() ?: throw Exception("پاسخ خالی از سرور")

            if (!response.isSuccessful) {
                Log.e(TAG, "API error: ${response.code} - $bodyString")
                throw Exception("خطای API: ${response.code}")
            }

            // Parse response
            val json = gson.fromJson(bodyString, JsonObject::class.java)
            val choices = json.getAsJsonArray("choices")
            if (choices != null && choices.size() > 0) {
                val message = choices[0].asJsonObject.getAsJsonObject("message")
                val content = message?.get("content")?.asString
                if (!content.isNullOrBlank()) {
                    return content.trim()
                }
            }

            // Fallback if structure is different
            return bodyString.take(200)
        }
    }

    private fun resetUI(message: String) {
        binding.resultText.text = message
        binding.loadingProgress.visibility = View.GONE
        binding.actionButton.isEnabled = true
        binding.actionButton.text = getString(R.string.retest_button)
        isAnalyzing = false
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
