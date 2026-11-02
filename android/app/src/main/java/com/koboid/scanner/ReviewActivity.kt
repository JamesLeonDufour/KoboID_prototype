package com.koboid.scanner

import android.content.ClipData
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Shows the captured photo alongside extracted field values for user review.
 *
 * Fields are rendered dynamically from [EXTRA_DISPLAY_LINES] so this activity
 * works uniformly for Belgium ID, MRZ, and custom templates.
 *
 * Extras in:
 *   data (Uri)                — photo URI
 *   [EXTRA_OCR_TEXT]          — raw OCR text
 *   [EXTRA_DISPLAY_LINES]     — ArrayList<String>  ("Label: value" lines)
 *   [EXTRA_RESULT_FIELDS]     — Bundle  (key → value for KoboCollect)
 *
 * Extras out (RESULT_OK):
 *   data (Uri), [EXTRA_OCR_TEXT], [EXTRA_RESULT_FIELDS]
 */
class ReviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OCR_TEXT      = "ocr_text"
        const val EXTRA_DISPLAY_LINES = "display_lines"
        const val EXTRA_RESULT_FIELDS = "result_fields"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)

        val reviewImage    = findViewById<ImageView>(R.id.review_image)
        val retakeButton   = findViewById<Button>(R.id.retake_button)
        val sendButton     = findViewById<Button>(R.id.send_button)
        val reviewText     = findViewById<TextView>(R.id.review_text)
        val fieldsContainer = findViewById<LinearLayout>(R.id.fields_container)
        val toggleRawButton = findViewById<Button>(R.id.toggle_raw_button)
        val rawTextLabel = findViewById<TextView>(R.id.raw_text_label)
        val rawTextHint = findViewById<TextView>(R.id.raw_text_hint)
        val summaryText = findViewById<TextView>(R.id.review_summary_text)

        val photoUri: Uri = intent.data ?: run { finish(); return }
        val ocrText      = intent.getStringExtra(EXTRA_OCR_TEXT) ?: ""
        val displayLines = intent.getStringArrayListExtra(EXTRA_DISPLAY_LINES) ?: arrayListOf()
        val resultFields = intent.getBundleExtra(EXTRA_RESULT_FIELDS) ?: Bundle()
        val detectedMrzFormat = resultFields.getString("mrz_format").orEmpty()

        // Photo
        reviewImage.setImageURI(photoUri)

        summaryText.text = if (detectedMrzFormat.isNotBlank()) {
            "Detected MRZ type: $detectedMrzFormat. Check the extracted travel-document fields before sending them back."
        } else if (displayLines.isEmpty()) {
            "No structured fields were detected. You can still inspect the raw OCR text or retake the photo."
        } else {
            "Check ${displayLines.size} extracted field${if (displayLines.size == 1) "" else "s"} before sending them back."
        }

        // Parsed fields — rendered as label/value cards
        if (displayLines.isEmpty()) {
            fieldsContainer.addView(buildFieldCard("No fields extracted", "Open raw text for the full OCR output or retake the photo for a clearer capture.", emptyState = true))
        } else {
            if (detectedMrzFormat.isNotBlank()) {
                fieldsContainer.addView(buildFieldCard("Detected MRZ type", detectedMrzFormat))
            }
            displayLines.forEach { line ->
                val parts = line.split(":", limit = 2)
                val label = parts.firstOrNull()?.trim().orEmpty()
                val value = parts.getOrNull(1)?.trim().orEmpty()
                if (label.equals("Mrz format", ignoreCase = true)) return@forEach
                fieldsContainer.addView(buildFieldCard(label, value))
            }
        }

        // Raw text toggle
        reviewText.text = ocrText
        toggleRawButton.setOnClickListener {
            if (reviewText.visibility == View.VISIBLE) {
                reviewText.visibility = View.GONE
                rawTextLabel.visibility = View.GONE
                rawTextHint.visibility = View.GONE
                toggleRawButton.text = "Show raw text"
            } else {
                reviewText.visibility = View.VISIBLE
                rawTextLabel.visibility = View.VISIBLE
                rawTextHint.visibility = View.VISIBLE
                toggleRawButton.text = "Hide raw text"
            }
        }

        retakeButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        sendButton.setOnClickListener {
            setResult(RESULT_OK, Intent().apply {
                data = photoUri
                putExtra(Intent.EXTRA_STREAM, photoUri)
                putExtra(EXTRA_OCR_TEXT, ocrText)
                putExtra(EXTRA_RESULT_FIELDS, resultFields)
                clipData = ClipData.newUri(contentResolver, "scanned_photo", photoUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            finish()
        }
    }

    private fun buildFieldCard(label: String, value: String, emptyState: Boolean = false): View {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(if (emptyState) R.drawable.bg_compact_panel else R.drawable.bg_surface_card)
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
            layoutParams = params

            addView(TextView(context).apply {
                text = label.ifBlank { "Field" }
                textSize = 11f
                letterSpacing = 0.08f
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                setTextColor(getColor(R.color.textSecondary))
            })

            addView(TextView(context).apply {
                text = value.ifBlank { if (emptyState) "" else "-" }
                textSize = if (emptyState) 14f else 18f
                typeface = Typeface.create(
                    if (emptyState) "sans-serif" else "sans-serif-medium",
                    Typeface.NORMAL
                )
                setTextColor(getColor(R.color.textPrimary))
                if (!emptyState) {
                    setPadding(0, (6 * density).toInt(), 0, 0)
                } else {
                    setPadding(0, (8 * density).toInt(), 0, 0)
                }
            })
        }
    }
}
