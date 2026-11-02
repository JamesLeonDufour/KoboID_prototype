package com.koboid.scanner

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.media.Image
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraActivity"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        const val EXTRA_FORMAT_NAME = "camera_format"
        const val EXTRA_LOCK_FORMAT = "lock_camera_format"
        const val EXTRA_MRZ_STANDARD = "mrzStandard"
        private const val REQUIRED_ALIGNED_FRAMES = 2
        private const val FRAME_MARGIN_RATIO = 0.18f
        private const val MIN_FILL_RATIO = 0.20f
        private const val MAX_FILL_RATIO = 0.95f
        private const val CROP_BORDER_RATIO = 0.04f
    }

    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textRecognizer: TextRecognizer
    private lateinit var scannerOverlay: ScannerOverlay
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var formatButton: Button
    private lateinit var alignmentStatusText: TextView
    private lateinit var alignmentHintText: TextView
    private lateinit var captureSummaryText: TextView
    private val isAnalyzing = AtomicBoolean(false)
    private var alignedStreak = 0
    private var isCapturing = false
    private var currentFormat = CardFormat.ID_CARD
    private var formatLocked = false
    private var mrzStandard: String? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startCamera()
        else {
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        cameraExecutor = Executors.newSingleThreadExecutor()
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        previewView = findViewById(R.id.viewFinder)
        scannerOverlay = findViewById(R.id.scanner_overlay)
        captureButton = findViewById(R.id.image_capture_button)
        formatButton = findViewById(R.id.format_button)
        alignmentStatusText = findViewById(R.id.alignment_status_text)
        alignmentHintText = findViewById(R.id.alignment_hint_text)
        captureSummaryText = findViewById(R.id.capture_summary_text)

        formatLocked = intent.getBooleanExtra(EXTRA_LOCK_FORMAT, false)
        mrzStandard = intent.getStringExtra(EXTRA_MRZ_STANDARD)?.trim()?.uppercase(Locale.US)
        currentFormat = CardFormat.fromName(intent.getStringExtra(EXTRA_FORMAT_NAME))
            ?: cardTypeToFormat(intent.getStringExtra("card_type"))
        scannerOverlay.setFormat(currentFormat)
        formatButton.text = currentFormat.label
        formatButton.isEnabled = !formatLocked
        formatButton.alpha = if (formatLocked) 0.72f else 1f

        captureButton.isEnabled = true
        updateAlignmentUi(false)

        if (allPermissionsGranted()) startCamera()
        else requestPermissionLauncher.launch(Manifest.permission.CAMERA)

        captureButton.setOnClickListener { takePhoto() }
        formatButton.setOnClickListener {
            if (formatLocked) return@setOnClickListener
            val formats = CardFormat.entries
            currentFormat = formats[(currentFormat.ordinal + 1) % formats.size]
            scannerOverlay.setFormat(currentFormat)
            formatButton.text = currentFormat.label
            alignedStreak = 0
            updateAlignmentUi(false)
        }
    }

    private fun cardTypeToFormat(cardType: String?): CardFormat {
        val upper = cardType?.uppercase(Locale.US) ?: return CardFormat.ID_CARD
        return when {
            upper == "MRZ" -> when (mrzStandard) {
                "TD1" -> CardFormat.ID_CARD
                "TD2", "TD3" -> CardFormat.PASSPORT
                else -> CardFormat.PASSPORT
            }
            upper == "GENERIC" -> CardFormat.DOCUMENT
            upper.contains("BELGIUM") -> CardFormat.ID_CARD
            upper.contains("PASSPORT") -> CardFormat.PASSPORT
            upper.contains("DOCUMENT") || upper.contains("A4") -> CardFormat.DOCUMENT
            else -> CardFormat.ID_CARD
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        if (isCapturing) return

        isCapturing = true
        captureButton.isEnabled = false
        captureButton.text = "Saving..."

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        AppSettings.cleanupTemporaryCaptures(this)
        val rawFile = File(createCaptureDir(), "raw-$name.jpg")

        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(rawFile).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    runCatching { finalizeCapturedPhoto(rawFile, name) }
                        .onSuccess { croppedUri ->
                            val resultIntent = Intent().apply {
                                data = croppedUri
                                putExtra(EXTRA_FORMAT_NAME, currentFormat.name)
                                putExtra(Intent.EXTRA_STREAM, croppedUri)
                                clipData = ClipData.newUri(contentResolver, "scanned_photo", croppedUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            setResult(RESULT_OK, resultIntent)
                            finish()
                        }
                        .onFailure { error ->
                            Log.e(TAG, "Photo finalize failed", error)
                            rawFile.delete()
                            isCapturing = false
                            captureButton.isEnabled = true
                            captureButton.text = "Take Picture"
                            Toast.makeText(
                                this@CameraActivity,
                                "Unable to save the cropped document image.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    isCapturing = false
                    captureButton.isEnabled = true
                    captureButton.text = "Take Picture"
                }
            }
        )
    }

    private fun finalizeCapturedPhoto(rawFile: File, name: String): Uri {
        val bitmap = BitmapFactory.decodeFile(rawFile.absolutePath)
            ?: error("Unable to decode captured photo.")
        val orientedBitmap = applyExifRotation(rawFile, bitmap)
        val croppedBitmap = cropToFrame(orientedBitmap)
        if (orientedBitmap !== bitmap) {
            bitmap.recycle()
        }

        val finalFile = File(createCaptureDir(), "capture-$name.jpg")
        FileOutputStream(finalFile).use { stream ->
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        }

        croppedBitmap.recycle()
        if (orientedBitmap !== croppedBitmap && !orientedBitmap.isRecycled) {
            orientedBitmap.recycle()
        }
        rawFile.delete()

        return FileProvider.getUriForFile(this, "$packageName.fileprovider", finalFile)
    }

    private fun cropToFrame(bitmap: Bitmap): Bitmap {
        val frame = scannerOverlay.getFrameRect(previewView.width, previewView.height)
        val mappedFrame = mapViewRectToBitmap(frame, bitmap.width, bitmap.height)
        val borderX = mappedFrame.width() * CROP_BORDER_RATIO
        val borderY = mappedFrame.height() * CROP_BORDER_RATIO

        val cropRect = RectF(
            (mappedFrame.left - borderX).coerceIn(0f, bitmap.width.toFloat() - 1f),
            (mappedFrame.top - borderY).coerceIn(0f, bitmap.height.toFloat() - 1f),
            (mappedFrame.right + borderX).coerceIn(1f, bitmap.width.toFloat()),
            (mappedFrame.bottom + borderY).coerceIn(1f, bitmap.height.toFloat())
        )

        val cropX = cropRect.left.toInt().coerceIn(0, bitmap.width - 1)
        val cropY = cropRect.top.toInt().coerceIn(0, bitmap.height - 1)
        val cropWidth = (cropRect.width().toInt()).coerceAtLeast(1).coerceAtMost(bitmap.width - cropX)
        val cropHeight = (cropRect.height().toInt()).coerceAtLeast(1).coerceAtMost(bitmap.height - cropY)
        return Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight)
    }

    private fun mapViewRectToBitmap(viewRect: RectF, bitmapWidth: Int, bitmapHeight: Int): RectF {
        val viewWidth = previewView.width.toFloat().coerceAtLeast(1f)
        val viewHeight = previewView.height.toFloat().coerceAtLeast(1f)
        val scale = maxOf(viewWidth / bitmapWidth, viewHeight / bitmapHeight)
        val displayedWidth = bitmapWidth * scale
        val displayedHeight = bitmapHeight * scale
        val offsetX = (viewWidth - displayedWidth) / 2f
        val offsetY = (viewHeight - displayedHeight) / 2f

        return RectF(
            ((viewRect.left - offsetX) / scale).coerceIn(0f, bitmapWidth.toFloat()),
            ((viewRect.top - offsetY) / scale).coerceIn(0f, bitmapHeight.toFloat()),
            ((viewRect.right - offsetX) / scale).coerceIn(0f, bitmapWidth.toFloat()),
            ((viewRect.bottom - offsetY) / scale).coerceIn(0f, bitmapHeight.toFloat())
        )
    }

    private fun applyExifRotation(file: File, bitmap: Bitmap): Bitmap {
        val exif = ExifInterface(file.absolutePath)
        val rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun createCaptureDir(): File =
        AppSettings.captureDirectory(this)

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()
            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyzeFrame) }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture!!,
                imageAnalysis!!
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (isCapturing || isAnalyzing.getAndSet(true)) {
            imageProxy.close()
            return
        }

        val mediaImage: Image = imageProxy.image ?: run {
            imageProxy.close()
            isAnalyzing.set(false)
            return
        }

        var imageWidth = imageProxy.width
        var imageHeight = imageProxy.height
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            val tmp = imageWidth
            imageWidth = imageHeight
            imageHeight = tmp
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        val finalWidth = imageWidth
        val finalHeight = imageHeight

        textRecognizer.process(inputImage)
            .addOnSuccessListener { text ->
                val aligned = isFrameAligned(text, finalWidth, finalHeight)
                alignedStreak = if (aligned) alignedStreak + 1 else 0
                val stableAligned = alignedStreak >= REQUIRED_ALIGNED_FRAMES
                runOnUiThread { updateAlignmentUi(stableAligned) }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Live OCR failed", e)
                runOnUiThread { updateAlignmentUi(false) }
                alignedStreak = 0
            }
            .addOnCompleteListener {
                imageProxy.close()
                isAnalyzing.set(false)
            }
    }

    private fun isFrameAligned(text: Text, imageWidth: Int, imageHeight: Int): Boolean {
        if (text.textBlocks.isEmpty()) return false

        val frame = scannerOverlay.getFrameRect(imageWidth, imageHeight)
        val frameWidth = frame.width()
        val frameHeight = frame.height()

        var unionLeft = Float.MAX_VALUE
        var unionTop = Float.MAX_VALUE
        var unionRight = 0f
        var unionBottom = 0f

        for (block in text.textBlocks) {
            val bb = block.boundingBox ?: continue
            unionLeft = minOf(unionLeft, bb.left.toFloat())
            unionTop = minOf(unionTop, bb.top.toFloat())
            unionRight = maxOf(unionRight, bb.right.toFloat())
            unionBottom = maxOf(unionBottom, bb.bottom.toFloat())
        }

        if (unionLeft == Float.MAX_VALUE) return false

        val unionWidth = unionRight - unionLeft
        val unionHeight = unionBottom - unionTop
        val unionCenterX = unionLeft + unionWidth / 2f
        val unionCenterY = unionTop + unionHeight / 2f

        val marginX = frameWidth * FRAME_MARGIN_RATIO
        val marginY = frameHeight * FRAME_MARGIN_RATIO

        val centerInside = unionCenterX in frame.left..frame.right &&
            unionCenterY in frame.top..frame.bottom
        val withinFrame = unionLeft >= frame.left - marginX &&
            unionRight <= frame.right + marginX &&
            unionTop >= frame.top - marginY &&
            unionBottom <= frame.bottom + marginY
        val fillRatio = if (frameWidth * frameHeight == 0f) {
            0f
        } else {
            (maxOf(0f, unionWidth) * maxOf(0f, unionHeight)) / (frameWidth * frameHeight)
        }
        val fillOk = fillRatio in MIN_FILL_RATIO..MAX_FILL_RATIO

        return centerInside && withinFrame && fillOk
    }

    private fun updateAlignmentUi(aligned: Boolean) {
        scannerOverlay.setAligned(aligned)
        if (!isCapturing) {
            captureButton.isEnabled = true
            captureButton.text = "Take Picture"
        }

        if (aligned) {
            alignmentStatusText.text = "Aligned and ready"
            alignmentHintText.text = alignmentReadyHint()
            captureSummaryText.text = captureSummaryHint()
        } else {
            alignmentStatusText.text = "Move document into frame"
            alignmentHintText.text = alignmentGuidanceHint()
            captureSummaryText.text = captureSummaryHint()
        }
    }

    private fun alignmentReadyHint(): String {
        return when (mrzStandard) {
            "TD1" -> "TD1 selected. Align the full ID-card width; the MRZ is the 3-line zone at the bottom."
            "TD2" -> "TD2 selected. Align the larger card or visa page within the frame."
            "TD3" -> "TD3 selected. Align the passport bio-data page; the MRZ is the 2-line zone at the bottom."
            else -> "The document fits the frame well."
        }
    }

    private fun alignmentGuidanceHint(): String {
        return when (mrzStandard) {
            "TD1" -> "Use the ID-card frame for TD1 documents. Capture is still available even if alignment is imperfect."
            "TD2" -> "Use the wider travel-document frame for TD2 documents. Capture is still available."
            "TD3" -> "Use the passport page frame for TD3 documents. Capture is still available."
            else -> "The frame is guidance only. Capture is still available."
        }
    }

    private fun captureSummaryHint(): String {
        return when (mrzStandard) {
            "TD1" -> "TD1 usually means national ID cards or residence permits. The app crops to the frame with a small safety border."
            "TD2" -> "TD2 usually means larger cards or some visas. The app crops to the frame with a small safety border."
            "TD3" -> "TD3 is the standard passport format. The app crops to the frame with a small safety border."
            else -> "The app saves the framed document area with a small safety border, not the full camera photo."
        }
    }
}
