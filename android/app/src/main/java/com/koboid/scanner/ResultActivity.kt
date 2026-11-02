package com.koboid.scanner

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ResultActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        val resultImage = findViewById<ImageView>(R.id.result_image)
        val ocrText = findViewById<TextView>(R.id.ocr_text)
        val homeButton = findViewById<Button>(R.id.home_button)
        val testAgainButton = findViewById<Button>(R.id.test_again_button)

        val photoUri: Uri? = intent.data
        val recognizedText = intent.getStringExtra("ocr_text")

        photoUri?.let { resultImage.setImageURI(it) }
        recognizedText?.let { ocrText.text = it }

        homeButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        testAgainButton.setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }
    }
}
