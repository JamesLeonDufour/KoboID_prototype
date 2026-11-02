package com.koboid.scanner

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Lets the user define a new document template by drawing labelled rectangles
 * over a captured photo. Saved rectangles can then be selected, moved, and
 * deleted before the final preset is stored.
 */
class TemplateEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PHOTO_URI = "photo_uri"
        const val EXTRA_FORMAT_NAME = "format_name"
        const val EXTRA_TEMPLATE_MODE = "template_mode"
        const val EXTRA_TEMPLATE_ID = "template_id"
        const val EXTRA_RESULT_FIELDS = "result_fields"
        const val EXTRA_DISPLAY_LINES = "display_lines"
    }

    private lateinit var documentImage: ImageView
    private lateinit var regionView: RegionDrawingView
    private lateinit var fieldList: LinearLayout
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button
    private lateinit var editorTitle: TextView
    private lateinit var editorSubtitle: TextView
    private lateinit var selectionHint: TextView

    private var photoUri: Uri? = null
    private var photoBitmap: Bitmap? = null
    private var templateFormat = CardFormat.ID_CARD
    private var templateMode = "STANDARD"
    private val pendingFields = mutableListOf<TemplateField>()
    private var selectedFieldIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_template_editor)

        documentImage = findViewById(R.id.document_image)
        regionView = findViewById(R.id.region_drawing)
        fieldList = findViewById(R.id.field_list)
        saveButton = findViewById(R.id.save_template_button)
        deleteButton = findViewById(R.id.delete_selected_button)
        editorTitle = findViewById(R.id.template_editor_title)
        editorSubtitle = findViewById(R.id.template_editor_subtitle)
        selectionHint = findViewById(R.id.selection_hint_text)

        photoUri = intent.getParcelableExtra(EXTRA_PHOTO_URI)
        templateFormat = CardFormat.fromName(intent.getStringExtra(EXTRA_FORMAT_NAME)) ?: CardFormat.ID_CARD
        templateMode = intent.getStringExtra(EXTRA_TEMPLATE_MODE) ?: "STANDARD"

        updateIntroCopy()
        loadPhoto()

        regionView.onRegionComplete = { normRect -> showFieldNameDialog(normRect) }
        regionView.onRegionSelected = { index ->
            selectedFieldIndex = index
            refreshFieldList()
        }
        regionView.onRegionMoved = { index, normRect ->
            updateFieldRect(index, normRect)
        }

        deleteButton.setOnClickListener { deleteSelectedField() }
        saveButton.setOnClickListener { showSaveDialog() }
        refreshFieldList()
    }

    private fun updateIntroCopy() {
        val modeLabel = if (templateMode == "CUSTOM") "Custom layout" else "Standard layout"
        editorTitle.text = "$modeLabel - ${templateFormat.label}"
        editorSubtitle.text =
            if (templateMode == "CUSTOM") {
                "Draw a field, name it, then tap saved fields to move or delete them before saving the preset."
            } else {
                "This template uses a standard ${templateFormat.label.lowercase()} frame. Draw each field, then adjust saved rectangles directly on the image."
            }
    }

    private fun loadPhoto() {
        val uri = photoUri ?: return
        contentResolver.openInputStream(uri)?.use { stream ->
            photoBitmap = BitmapFactory.decodeStream(stream)
        }
        photoBitmap?.let { bmp ->
            documentImage.setImageBitmap(bmp)
            documentImage.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        documentImage.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        regionView.setImageBounds(computeImageBounds(bmp))
                        syncOverlay()
                    }
                }
            )
        }
    }

    private fun computeImageBounds(bmp: Bitmap): RectF {
        val vw = documentImage.width.toFloat()
        val vh = documentImage.height.toFloat()
        if (vw == 0f || vh == 0f) return RectF(0f, 0f, vw, vh)

        val scale = minOf(vw / bmp.width, vh / bmp.height)
        val scaledW = bmp.width * scale
        val scaledH = bmp.height * scale
        val left = (vw - scaledW) / 2f
        val top = (vh - scaledH) / 2f
        return RectF(left, top, left + scaledW, top + scaledH)
    }

    private fun showFieldNameDialog(normRect: RectF) {
        val input = EditText(this).apply {
            hint = "e.g. First Name, ID Number"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Name this field")
            .setView(input)
            .setPositiveButton("Save field") { _, _ ->
                val label = input.text.toString().trim()
                if (label.isNotEmpty()) {
                    val key = label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                    pendingFields += TemplateField(
                        key = key.ifBlank { "field_${pendingFields.size + 1}" },
                        label = label,
                        left = normRect.left,
                        top = normRect.top,
                        right = normRect.right,
                        bottom = normRect.bottom
                    )
                    selectedFieldIndex = pendingFields.lastIndex
                    syncOverlay()
                    refreshFieldList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateFieldRect(index: Int, normRect: RectF) {
        if (index !in pendingFields.indices) return
        val current = pendingFields[index]
        pendingFields[index] = current.copy(
            left = normRect.left,
            top = normRect.top,
            right = normRect.right,
            bottom = normRect.bottom
        )
    }

    private fun deleteSelectedField() {
        if (selectedFieldIndex !in pendingFields.indices) return
        pendingFields.removeAt(selectedFieldIndex)
        selectedFieldIndex = if (pendingFields.isEmpty()) -1 else minOf(selectedFieldIndex, pendingFields.lastIndex)
        syncOverlay()
        refreshFieldList()
    }

    private fun syncOverlay() {
        regionView.setRegions(pendingFields.map { it.label to it.toRectF() })
        regionView.setSelectedRegion(selectedFieldIndex)
    }

    private fun refreshFieldList() {
        fieldList.removeAllViews()
        saveButton.text = "Save and Scan"

        if (pendingFields.isEmpty()) {
            fieldList.addView(TextView(this).apply {
                text = "No saved fields yet. Draw a rectangle on the image to add one."
                textSize = 14f
                setTextColor(getColor(R.color.textSecondary))
                background = AppCompatResources.getDrawable(context, R.drawable.bg_compact_panel)
                val inset = (14 * resources.displayMetrics.density).toInt()
                setPadding(inset, inset, inset, inset)
            })
        } else {
            pendingFields.forEachIndexed { index, field ->
                fieldList.addView(buildFieldRow(index, field))
            }
        }

        deleteButton.isEnabled = selectedFieldIndex in pendingFields.indices
        selectionHint.text =
            if (selectedFieldIndex in pendingFields.indices) {
                "Selected: ${pendingFields[selectedFieldIndex].label}. Drag inside to move, or drag a corner handle to resize."
            } else {
                "Tap a saved field to select it. Drag inside to move, or drag a corner handle to resize."
            }
        saveButton.isEnabled = pendingFields.isNotEmpty()
        deleteButton.alpha = if (deleteButton.isEnabled) 1f else 0.45f
        saveButton.alpha = if (saveButton.isEnabled) 1f else 0.45f
    }

    private fun buildFieldRow(index: Int, field: TemplateField): View {
        val isSelected = index == selectedFieldIndex
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AppCompatResources.getDrawable(
                context,
                if (isSelected) R.drawable.bg_tutorial_row else R.drawable.bg_compact_panel
            )
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
            setOnClickListener {
                selectedFieldIndex = index
                regionView.setSelectedRegion(index)
                refreshFieldList()
            }

            addView(LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                orientation = LinearLayout.VERTICAL

                addView(TextView(context).apply {
                    text = field.label
                    textSize = 16f
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    setTextColor(getColor(R.color.textPrimary))
                })

                addView(TextView(context).apply {
                    text = field.key
                    textSize = 12f
                    setTextColor(getColor(R.color.textSecondary))
                })
            })

            addView(Button(context).apply {
                text = if (isSelected) "Selected" else "Select"
                isAllCaps = false
                minHeight = (44 * density).toInt()
                minimumHeight = (44 * density).toInt()
                background = AppCompatResources.getDrawable(
                    context,
                    if (isSelected) R.drawable.bg_primary_button else R.drawable.bg_outline_chip
                )
                setTextColor(
                    getColor(if (isSelected) R.color.textInverse else R.color.textPrimary)
                )
                setOnClickListener {
                    selectedFieldIndex = index
                    regionView.setSelectedRegion(index)
                    refreshFieldList()
                }
            })
        }
    }

    private fun showSaveDialog() {
        val input = EditText(this).apply {
            hint = "e.g. French ID, Afghan Tazkira"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Template name")
            .setView(input)
            .setPositiveButton("Save & Scan") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) saveAndExtract(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveAndExtract(templateName: String) {
        val template = DocumentTemplate(
            id = TemplateStore.newId(),
            name = templateName,
            aspectRatio = templateFormat.aspectRatio,
            fields = pendingFields.toList()
        )
        TemplateStore.save(this, template)

        val bmp = photoBitmap
        if (bmp == null) {
            returnResult(template, emptyMap())
            return
        }

        saveButton.isEnabled = false
        deleteButton.isEnabled = false
        saveButton.alpha = 0.45f
        deleteButton.alpha = 0.45f
        saveButton.text = "Processing..."

        lifecycleScope.launch {
            val values = extractRegions(bmp, template)
            returnResult(template, values)
        }
    }

    private suspend fun extractRegions(
        bmp: Bitmap,
        template: DocumentTemplate
    ): Map<String, String> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val results = mutableMapOf<String, String>()
        template.fields.forEach { field ->
            val cropX = (field.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val cropY = (field.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            val cropW = ((field.right - field.left) * bmp.width).toInt().coerceAtLeast(1)
                .coerceAtMost(bmp.width - cropX)
            val cropH = ((field.bottom - field.top) * bmp.height).toInt().coerceAtLeast(1)
                .coerceAtMost(bmp.height - cropY)
            val crop = Bitmap.createBitmap(bmp, cropX, cropY, cropW, cropH)
            runCatching {
                results[field.key] = recognizer
                    .process(InputImage.fromBitmap(crop, 0))
                    .await()
                    .text
                    .trim()
            }
        }
        return results
    }

    private fun returnResult(template: DocumentTemplate, values: Map<String, String>) {
        val displayLines = ArrayList(ParserRegistry.toDisplayLines(template, values))
        val resultBundle = Bundle().apply {
            values.forEach { (k, v) -> putString(k, v) }
        }
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_TEMPLATE_ID, template.id)
            putExtra(EXTRA_RESULT_FIELDS, resultBundle)
            putExtra(EXTRA_DISPLAY_LINES, displayLines)
            photoUri?.let {
                data = it
                putExtra(Intent.EXTRA_STREAM, it)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        })
        finish()
    }
}
