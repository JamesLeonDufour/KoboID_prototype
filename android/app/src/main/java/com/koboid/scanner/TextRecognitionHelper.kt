package com.koboid.scanner

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class TextRecognitionHelper {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Suppress("DEPRECATION")
    suspend fun recognizeText(context: Context, imageUri: Uri): Text {
        val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
        val image = InputImage.fromBitmap(bitmap, 0)
        return recognizer.process(image).await()
    }
}
