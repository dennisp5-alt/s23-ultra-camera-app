package com.dennis.s23camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.dennis.s23camera.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashEnabled = false
    private var currentZoomRatio = 1f

    private lateinit var cameraExecutor: ExecutorService

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.captureButton.setOnClickListener { takePhoto() }
        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.flashButton.setOnClickListener { toggleFlash() }

        binding.zoomSlider.max = 20
        binding.zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val ratio = (progress + 1).toFloat()
                currentZoomRatio = ratio
                binding.zoomLabel.text = String.format(Locale.US, "%.1fx", ratio)
                camera?.cameraControl?.setZoomRatio(ratio)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                .build()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                updateCameraUi()
                camera?.cameraInfo?.zoomState?.observe(this) { state ->
                    val ratio = state?.zoomRatio ?: 1f
                    currentZoomRatio = ratio
                    binding.zoomSlider.progress = (ratio - 1f).toInt().coerceIn(0, 20)
                    binding.zoomLabel.text = String.format(Locale.US, "%.1fx", ratio)
                }
            } catch (exc: Exception) {
                Log.e("MainActivity", "Camera binding failed", exc)
                Toast.makeText(this, "Unable to start the camera.", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateCameraUi() {
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
        binding.flashButton.isEnabled = hasFlash
        binding.flashButton.alpha = if (hasFlash) 1f else 0.4f
        binding.flashButton.text = if (flashEnabled && hasFlash) "Flash On" else "Flash Off"
        binding.switchCameraButton.text = if (lensFacing == CameraSelector.LENS_FACING_BACK) "Back" else "Front"
        binding.zoomSlider.progress = (currentZoomRatio - 1f).toInt().coerceIn(0, 20)
        binding.zoomLabel.text = String.format(Locale.US, "%.1fx", currentZoomRatio)
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        currentZoomRatio = 1f
        startCamera()
    }

    private fun toggleFlash() {
        if (camera?.cameraInfo?.hasFlashUnit() != true) {
            Toast.makeText(this, "This camera does not support flash.", Toast.LENGTH_SHORT).show()
            return
        }

        flashEnabled = !flashEnabled
        imageCapture?.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        binding.flashButton.text = if (flashEnabled) "Flash On" else "Flash Off"
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val fileName = "S23U_${timeStamp}.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/S23UltraCameraApp")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri
                    if (savedUri != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val updateValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.IS_PENDING, 0)
                            }
                            contentResolver.update(savedUri, updateValues, null, null)
                        }
                        loadLatestImagePreview(savedUri)
                    }

                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Photo saved to gallery.", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("MainActivity", "Photo capture failed", exception)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Capture failed.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    private fun loadLatestImagePreview(uri: android.net.Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val bitmap = BitmapFactory.decodeStream(inputStream)
                runOnUiThread {
                    binding.thumbnailImage.setImageBitmap(bitmap)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to load preview image", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
