package com.koboid.scanner

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val EXTRA_CARD_TYPE = "card_type"
        private const val EXTRA_SCAN_MODE = "scanMode"
        private const val EXTRA_MRZ_STANDARD = "mrzStandard"

        private const val MODE_NEW_DOCUMENT = "NEW_DOCUMENT"
        private const val MODE_MRZ = "MRZ"
        private const val MODE_BELGIUM = "BELGIUM_ID"
        private const val MODE_GENERIC = "GENERIC"
        private const val TEMPLATE_PREFIX = "TEMPLATE:"
        private const val TEMPLATE_MODE_STANDARD = "STANDARD"
        private const val TEMPLATE_MODE_CUSTOM = "CUSTOM"
        private const val MRZ_STANDARD_TD1 = "TD1"
        private const val MRZ_STANDARD_TD2 = "TD2"
        private const val MRZ_STANDARD_TD3 = "TD3"
    }

    private var isTestMode = false
    private var selectedMode = MODE_BELGIUM
    private var selectedMrzStandard = MRZ_STANDARD_TD3
    private var pendingNewTemplateFormat = CardFormat.ID_CARD
    private var pendingNewTemplateMode = TEMPLATE_MODE_STANDARD

    private val spinnerItems = mutableListOf<Pair<String, String>>()

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val photoUri = result.data?.data
            val formatName = result.data?.getStringExtra(CameraActivity.EXTRA_FORMAT_NAME)
            if (photoUri != null) onPhotoCaptured(photoUri, formatName)
        } else if (!isTestMode) {
            finish()
        }
    }

    private val reviewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (result.resultCode) {
            RESULT_OK -> {
                showProcessingState(false)
                val photoUri = result.data?.data ?: return@registerForActivityResult
                val ocrText = result.data?.getStringExtra(ReviewActivity.EXTRA_OCR_TEXT) ?: ""
                val resultFields = result.data?.getBundleExtra(ReviewActivity.EXTRA_RESULT_FIELDS) ?: Bundle()
                if (!isTestMode) returnToKobo(photoUri, ocrText, resultFields)
            }
            RESULT_CANCELED -> {
                showProcessingState(false)
                launchCamera()
            }
        }
    }

    private val templateEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val photoUri = data.data ?: return@registerForActivityResult
            val templateId = data.getStringExtra(TemplateEditorActivity.EXTRA_TEMPLATE_ID) ?: return@registerForActivityResult
            val resultFields = data.getBundleExtra(TemplateEditorActivity.EXTRA_RESULT_FIELDS) ?: Bundle()
            val displayLines = data.getStringArrayListExtra(TemplateEditorActivity.EXTRA_DISPLAY_LINES) ?: arrayListOf()
            showReviewScreen(photoUri, "", displayLines, resultFields)
            selectedMode = "$TEMPLATE_PREFIX$templateId"
            rebuildSpinner()
            updateModeSummary()
        } else if (!isTestMode) {
            finish()
        }
    }

    private val importTemplateLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importTemplateFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isLauncher = intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        when {
            Intent.ACTION_MAIN == intent.action && isLauncher -> {
                isTestMode = true
                selectedMrzStandard = resolveMrzStandard(AppSettings.defaultMrzStandard(this))
                AppSettings.cleanupTemporaryCaptures(this)
                setContentView(R.layout.activity_main)
                rebuildSpinner()
                setupTestModeUi()
            }
            "org.kobo.scanner.SCAN" == intent.action -> {
                isTestMode = false
                AppSettings.cleanupTemporaryCaptures(this)
                selectedMode = resolveMode(
                    intent.getStringExtra(EXTRA_CARD_TYPE) ?: intent.getStringExtra(EXTRA_SCAN_MODE)
                )
                selectedMrzStandard = resolveMrzStandard(
                    intent.getStringExtra(EXTRA_MRZ_STANDARD) ?: AppSettings.defaultMrzStandard(this)
                )
                launchCamera()
            }
        }
    }

    private fun setupTestModeUi() {
        val manageButton = findViewById<Button>(R.id.manage_template_button)
        val settingsButton = findViewById<ImageButton>(R.id.settings_button)
        val mrzButton = findViewById<Button>(R.id.start_mrz_scan_button)
        val newDocumentButton = findViewById<Button>(R.id.start_new_document_button)
        val importTemplateButton = findViewById<Button>(R.id.import_template_button)
        val spinner = findViewById<Spinner>(R.id.card_type_spinner)
        val startButton = findViewById<Button>(R.id.start_scan_button)
        val mrzInfoButton = findViewById<Button>(R.id.mrz_info_button)
        val specificInfoButton = findViewById<Button>(R.id.specific_info_button)
        val otherDocsInfoButton = findViewById<Button>(R.id.other_docs_info_button)

        showProcessingState(false)
        updateModeSummary()

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedMode = spinnerItems.getOrNull(position)?.second ?: MODE_BELGIUM
                updateModeSummary()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        manageButton.setOnClickListener { showTemplateActionsDialog() }
        settingsButton.setOnClickListener { showSettingsDialog() }
        mrzButton.setOnClickListener { launchMrzCamera() }
        mrzInfoButton.setOnClickListener {
            showInfoDialog(
                "MRZ mode",
                "Use MRZ mode for travel documents with machine-readable lines. TD1 is common for EU ID cards and residence permits, TD2 for some larger cards or visas, and TD3 for passports. The app still auto-detects the MRZ after capture, but the selected standard helps choose the most useful frame."
            )
        }
        specificInfoButton.setOnClickListener {
            showInfoDialog(
                "Specific ID or preset",
                "Use this section for Belgium ID, Generic OCR, and any saved presets. Saved presets scan only the field regions you defined earlier and let you manage or export them."
            )
        }
        otherDocsInfoButton.setOnClickListener {
            showInfoDialog(
                "Create or import",
                "Create a new preset by capturing one sample and marking the fields you want. Import preset lets you load a previously exported KoboID template JSON file onto this device."
            )
        }
        newDocumentButton.setOnClickListener {
            selectedMode = MODE_NEW_DOCUMENT
            updateModeSummary()
            showNewDocumentSetupDialog()
        }
        importTemplateButton.setOnClickListener { promptImportTemplate() }
        startButton.setOnClickListener { launchCamera() }
    }

    private fun updateModeSummary() {
        val titleView = findViewById<TextView?>(R.id.mode_title_text) ?: return
        val bodyView = findViewById<TextView?>(R.id.mode_description_text) ?: return
        val manageButton = findViewById<Button?>(R.id.manage_template_button)

        val (title, body) = when {
            selectedMode == MODE_BELGIUM ->
                "Belgium ID" to "Structured Belgian eID fields."
            selectedMode == MODE_GENERIC ->
                "Generic OCR" to "Raw text extraction without a document parser."
            selectedMode == MODE_MRZ ->
                "MRZ" to "Travel-document fields from machine-readable lines."
            selectedMode == MODE_NEW_DOCUMENT ->
                "New document" to "Create a reusable preset from one sample."
            selectedMode.startsWith(TEMPLATE_PREFIX) -> {
                val templateId = selectedMode.removePrefix(TEMPLATE_PREFIX)
                val template = TemplateStore.load(this, templateId)
                val templateName = template?.name ?: "Saved template"
                val fieldCount = template?.fields?.size ?: 0
                templateName to "$fieldCount saved field${if (fieldCount == 1) "" else "s"} ready for scanning."
            }
            else ->
                "Document scan" to "Choose a mode and start scanning."
        }

        titleView.text = title
        bodyView.text = body
        manageButton?.visibility = if (selectedMode.startsWith(TEMPLATE_PREFIX)) View.VISIBLE else View.GONE
    }

    private fun rebuildSpinner() {
        spinnerItems.clear()
        spinnerItems += "Belgium ID" to MODE_BELGIUM
        spinnerItems += "Generic OCR" to MODE_GENERIC
        TemplateStore.loadAll(this).forEach { template ->
            spinnerItems += template.name to "$TEMPLATE_PREFIX${template.id}"
        }

        val spinner = findViewById<Spinner?>(R.id.card_type_spinner) ?: return
        val labels = spinnerItems.map { it.first }
        val adapter = ArrayAdapter(this, R.layout.item_spinner_selected, labels)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        spinner.adapter = adapter
        val idx = spinnerItems.indexOfFirst { it.second == selectedMode }.takeIf { it >= 0 } ?: 0
        spinner.setSelection(idx)
    }

    private fun launchCamera() {
        AppSettings.cleanupTemporaryCaptures(this)
        cameraLauncher.launch(Intent(this, CameraActivity::class.java).apply {
            putExtra(EXTRA_CARD_TYPE, selectedMode)
            putExtra(CameraActivity.EXTRA_FORMAT_NAME, formatForMode(selectedMode).name)
            putExtra(CameraActivity.EXTRA_LOCK_FORMAT, isFormatLockedForMode(selectedMode))
            if (selectedMode == MODE_MRZ) {
                putExtra(CameraActivity.EXTRA_MRZ_STANDARD, selectedMrzStandard)
            }
            if (selectedMode == MODE_NEW_DOCUMENT) {
                putExtra(TemplateEditorActivity.EXTRA_TEMPLATE_MODE, pendingNewTemplateMode)
            }
        })
    }

    private fun launchMrzCamera() {
        showProcessingState(false)
        showMrzSetupDialog()
    }

    private fun formatForMode(mode: String): CardFormat {
        return when {
            mode == MODE_MRZ -> mrzStandardToFormat(selectedMrzStandard)
            mode == MODE_GENERIC -> CardFormat.DOCUMENT
            mode == MODE_NEW_DOCUMENT -> pendingNewTemplateFormat
            mode.startsWith(TEMPLATE_PREFIX) -> {
                val templateId = mode.removePrefix(TEMPLATE_PREFIX)
                val template = TemplateStore.load(this, templateId)
                if (template != null) CardFormat.fromAspectRatio(template.aspectRatio) else CardFormat.ID_CARD
            }
            else -> CardFormat.ID_CARD
        }
    }

    private fun mrzStandardToFormat(standard: String): CardFormat {
        return when (standard) {
            MRZ_STANDARD_TD1 -> CardFormat.ID_CARD
            MRZ_STANDARD_TD2, MRZ_STANDARD_TD3 -> CardFormat.PASSPORT
            else -> CardFormat.PASSPORT
        }
    }

    private fun resolveMrzStandard(raw: String?): String {
        return when (raw?.trim()?.uppercase(Locale.US)) {
            MRZ_STANDARD_TD1 -> MRZ_STANDARD_TD1
            MRZ_STANDARD_TD2 -> MRZ_STANDARD_TD2
            MRZ_STANDARD_TD3 -> MRZ_STANDARD_TD3
            else -> MRZ_STANDARD_TD3
        }
    }

    private fun isFormatLockedForMode(mode: String): Boolean {
        return mode != MODE_GENERIC
    }

    private fun onPhotoCaptured(photoUri: Uri, capturedFormatName: String?) {
        when {
            selectedMode == MODE_NEW_DOCUMENT -> {
                templateEditorLauncher.launch(
                    Intent(this, TemplateEditorActivity::class.java).apply {
                        putExtra(TemplateEditorActivity.EXTRA_PHOTO_URI, photoUri)
                        putExtra(TemplateEditorActivity.EXTRA_FORMAT_NAME, capturedFormatName)
                        putExtra(TemplateEditorActivity.EXTRA_TEMPLATE_MODE, pendingNewTemplateMode)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            }
            selectedMode.startsWith(TEMPLATE_PREFIX) -> {
                val templateId = selectedMode.removePrefix(TEMPLATE_PREFIX)
                val template = TemplateStore.load(this, templateId)
                if (template == null) {
                    Log.e(TAG, "Template $templateId not found, falling back to generic")
                    handleOcr(photoUri)
                } else {
                    handleTemplateOcr(photoUri, template)
                }
            }
            else -> handleOcr(photoUri)
        }
    }

    private fun handleOcr(photoUri: Uri) {
        val helper = TextRecognitionHelper()
        showProcessingState(true, "Processing capture", "Running OCR and preparing the detected fields.")
        lifecycleScope.launch {
            try {
                val ocrText = helper.recognizeText(this@MainActivity, photoUri).text
                val parsedFields = ParserRegistry.parse(selectedMode, ocrText)
                val displayLines = ArrayList(ParserRegistry.toDisplayLines(parsedFields))
                val resultBundle = Bundle().apply {
                    parsedFields.forEach { (k, v) -> putString(k, v) }
                }
                showReviewScreen(photoUri, ocrText, displayLines, resultBundle)
            } catch (e: Exception) {
                Log.e(TAG, "OCR failed", e)
                showProcessingState(false)
                showReviewScreen(photoUri, "", arrayListOf(), Bundle())
            }
        }
    }

    private fun handleTemplateOcr(photoUri: Uri, template: DocumentTemplate) {
        showProcessingState(true, "Processing preset", "Reading each saved field from the captured document.")
        lifecycleScope.launch {
            try {
                val values = extractTemplateRegions(this@MainActivity, photoUri, template)
                val displayLines = ArrayList(ParserRegistry.toDisplayLines(template, values))
                val resultBundle = Bundle().apply {
                    values.forEach { (k, v) -> putString(k, v) }
                }
                showReviewScreen(photoUri, "", displayLines, resultBundle)
            } catch (e: Exception) {
                Log.e(TAG, "Template OCR failed", e)
                showProcessingState(false)
                showReviewScreen(photoUri, "", arrayListOf(), Bundle())
            }
        }
    }

    private fun showReviewScreen(
        photoUri: Uri,
        ocrText: String,
        displayLines: ArrayList<String>,
        resultFields: Bundle
    ) {
        showProcessingState(false)
        reviewLauncher.launch(Intent(this, ReviewActivity::class.java).apply {
            data = photoUri
            putExtra(ReviewActivity.EXTRA_OCR_TEXT, ocrText)
            putExtra(ReviewActivity.EXTRA_DISPLAY_LINES, displayLines)
            putExtra(ReviewActivity.EXTRA_RESULT_FIELDS, resultFields)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun returnToKobo(photoUri: Uri, ocrText: String, resultFields: Bundle) {
        setResult(RESULT_OK, Intent().apply {
            data = photoUri
            putExtra("scan_mode", selectedMode)
            putExtra("scanned_text", ocrText)
            putExtra("scanned_photo", photoUri.toString())
            putExtra(Intent.EXTRA_STREAM, photoUri)
            clipData = ClipData.newUri(contentResolver, "scanned_photo", photoUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            resultFields.keySet().forEach { key ->
                putExtra(key, resultFields.getString(key))
            }
        })
        finish()
    }

    private fun resolveMode(raw: String?): String {
        val upper = raw?.trim()?.uppercase() ?: return MODE_BELGIUM
        return when {
            upper.startsWith(TEMPLATE_PREFIX) -> upper
            upper.contains("BELGIUM") -> MODE_BELGIUM
            upper == "MRZ" -> MODE_MRZ
            upper == "GENERIC" -> MODE_GENERIC
            upper == MODE_NEW_DOCUMENT -> MODE_NEW_DOCUMENT
            else -> upper
        }
    }

    private fun showNewDocumentSetupDialog() {
        AlertDialog.Builder(this)
            .setTitle("New document setup")
            .setItems(arrayOf("Standard layout", "Custom layout")) { _, which ->
                pendingNewTemplateMode = if (which == 0) TEMPLATE_MODE_STANDARD else TEMPLATE_MODE_CUSTOM
                showNewDocumentFrameDialog()
            }
            .setNegativeButton("Cancel") { _, _ ->
                if (isTestMode) {
                    selectedMode = spinnerItems.firstOrNull()?.second ?: MODE_BELGIUM
                    updateModeSummary()
                }
            }
            .show()
    }

    private fun showNewDocumentFrameDialog() {
        val title = if (pendingNewTemplateMode == TEMPLATE_MODE_STANDARD) {
            "Choose a standard frame"
        } else {
            "Choose a starting frame"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(arrayOf("ID Card", "Passport", "Document")) { _, which ->
                pendingNewTemplateFormat = when (which) {
                    1 -> CardFormat.PASSPORT
                    2 -> CardFormat.DOCUMENT
                    else -> CardFormat.ID_CARD
                }
                launchCamera()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMrzSetupDialog() {
        AlertDialog.Builder(this)
            .setTitle("Choose MRZ standard")
            .setItems(
                arrayOf(
                    "TD1 - ID cards and residence permits",
                    "TD2 - larger cards and some visas",
                    "TD3 - passports"
                )
            ) { _, which ->
                selectedMrzStandard = when (which) {
                    0 -> MRZ_STANDARD_TD1
                    1 -> MRZ_STANDARD_TD2
                    else -> MRZ_STANDARD_TD3
                }
                selectedMode = MODE_MRZ
                updateModeSummary()
                launchCamera()
            }
            .setNegativeButton("Cancel") { _, _ ->
                updateModeSummary()
            }
            .show()
    }

    private fun showSettingsDialog() {
        val retainPhotos = AppSettings.shouldRetainCapturedPhotos(this)
        val mrzStandard = resolveMrzStandard(AppSettings.defaultMrzStandard(this))
        val storageLabel = if (retainPhotos) {
            "Keep on device"
        } else {
            "Temporary only"
        }

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(
                arrayOf(
                    "Photo storage: $storageLabel",
                    "Default MRZ standard: $mrzStandard"
                )
            ) { _, which ->
                when (which) {
                    0 -> showPhotoStorageDialog()
                    1 -> showDefaultMrzStandardDialog()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showPhotoStorageDialog() {
        val options = arrayOf(
            "Temporary only (default)",
            "Keep on device"
        )
        val checkedItem = if (AppSettings.shouldRetainCapturedPhotos(this)) 1 else 0

        AlertDialog.Builder(this)
            .setTitle("Photo storage")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val retain = which == 1
                AppSettings.setRetainCapturedPhotos(this, retain)
                if (!retain) {
                    AppSettings.cleanupTemporaryCaptures(this)
                }
                dialog.dismiss()
                showSettingsDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDefaultMrzStandardDialog() {
        val options = arrayOf("TD1", "TD2", "TD3")
        val current = resolveMrzStandard(AppSettings.defaultMrzStandard(this))
        val checkedItem = options.indexOf(current).takeIf { it >= 0 } ?: 2

        AlertDialog.Builder(this)
            .setTitle("Default MRZ standard")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val selected = options[which]
                AppSettings.setDefaultMrzStandard(this, selected)
                selectedMrzStandard = selected
                dialog.dismiss()
                showSettingsDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTemplateActionsDialog() {
        if (!selectedMode.startsWith(TEMPLATE_PREFIX)) return
        val templateId = selectedMode.removePrefix(TEMPLATE_PREFIX)
        val template = TemplateStore.load(this, templateId) ?: run {
            Toast.makeText(this, "Template not found.", Toast.LENGTH_SHORT).show()
            rebuildSpinner()
            updateModeSummary()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(template.name)
            .setItems(arrayOf("Export preset", "Delete preset")) { _, which ->
                when (which) {
                    0 -> exportTemplatePreset(templateId)
                    1 -> confirmDeleteTemplate(template)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteTemplate(template: DocumentTemplate) {
        AlertDialog.Builder(this)
            .setTitle("Delete preset")
            .setMessage("Delete \"${template.name}\" from this device?")
            .setPositiveButton("Delete") { _, _ ->
                TemplateStore.delete(this, template.id)
                if (selectedMode == "$TEMPLATE_PREFIX${template.id}") {
                    selectedMode = MODE_BELGIUM
                }
                rebuildSpinner()
                updateModeSummary()
                Toast.makeText(this, "Preset deleted.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportTemplatePreset(templateId: String) {
        val exportFile = TemplateStore.exportToCache(this, templateId)
        if (exportFile == null) {
            Toast.makeText(this, "Unable to export preset.", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", exportFile)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, exportFile.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Export preset"
            )
        )
    }

    private fun promptImportTemplate() {
        importTemplateLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    private fun importTemplateFromUri(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val imported = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                TemplateStore.importFromJson(this, reader.readText())
            } ?: error("Unable to open file.")
        }.getOrElse { error ->
            Toast.makeText(this, "Import failed: ${error.message}", Toast.LENGTH_LONG).show()
            return
        }

        selectedMode = "$TEMPLATE_PREFIX${imported.id}"
        rebuildSpinner()
        updateModeSummary()
        Toast.makeText(this, "Imported preset: ${imported.name}", Toast.LENGTH_SHORT).show()
    }

    private fun showInfoDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showProcessingState(
        visible: Boolean,
        title: String = "Processing capture",
        body: String = "Running OCR and preparing your results."
    ) {
        val overlay = findViewById<View?>(R.id.processing_overlay) ?: return
        findViewById<TextView?>(R.id.processing_title_text)?.text = title
        findViewById<TextView?>(R.id.processing_body_text)?.text = body
        overlay.visibility = if (visible) View.VISIBLE else View.GONE
    }
}

private suspend fun extractTemplateRegions(
    context: android.content.Context,
    photoUri: Uri,
    template: DocumentTemplate
): Map<String, String> {
    val bmp = android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, photoUri)
    val recognizer = com.google.mlkit.vision.text.TextRecognition
        .getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
    val results = mutableMapOf<String, String>()
    template.fields.forEach { field ->
        val cropX = (field.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val cropY = (field.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val cropW = ((field.right - field.left) * bmp.width).toInt().coerceAtLeast(1).coerceAtMost(bmp.width - cropX)
        val cropH = ((field.bottom - field.top) * bmp.height).toInt().coerceAtLeast(1).coerceAtMost(bmp.height - cropY)
        val crop = android.graphics.Bitmap.createBitmap(bmp, cropX, cropY, cropW, cropH)
        runCatching {
            results[field.key] = recognizer
                .process(com.google.mlkit.vision.common.InputImage.fromBitmap(crop, 0))
                .await()
                .text
                .trim()
        }
    }
    return results
}
