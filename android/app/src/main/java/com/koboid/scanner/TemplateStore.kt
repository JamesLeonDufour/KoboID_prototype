package com.koboid.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Persists [DocumentTemplate] objects as JSON files in the app's internal storage. */
object TemplateStore {

    private const val DIR = "doc_templates"

    fun newId(): String = UUID.randomUUID().toString()

    fun save(context: Context, template: DocumentTemplate) {
        dir(context).mkdirs()
        file(context, template.id).writeText(template.toJson().toString(2))
    }

    fun loadAll(context: Context): List<DocumentTemplate> {
        val d = dir(context)
        if (!d.exists()) return emptyList()
        return d.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun load(context: Context, id: String): DocumentTemplate? {
        val f = file(context, id)
        if (!f.exists()) return null
        return runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun delete(context: Context, id: String) {
        file(context, id).delete()
    }

    fun exportToCache(context: Context, id: String): File? {
        val source = file(context, id)
        if (!source.exists()) return null

        val templateName = load(context, id)?.name ?: id
        val safeName = templateName
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "template" }

        val exportDir = File(context.cacheDir, "template_exports").apply { mkdirs() }
        val exportFile = File(exportDir, "$safeName-$id.koboid-template.json")
        source.copyTo(exportFile, overwrite = true)
        return exportFile
    }

    fun importFromJson(context: Context, json: String): DocumentTemplate {
        val imported = fromJson(JSONObject(json))
        val existing = loadAll(context)
        val existingNames = existing.map { it.name }.toSet()
        val finalId = imported.id.takeIf { it.isNotBlank() && load(context, it) == null } ?: newId()
        val finalName = uniqueName(existingNames, imported.name.ifBlank { "Imported preset" })
        val normalized = imported.copy(id = finalId, name = finalName)
        save(context, normalized)
        return normalized
    }

    // ── private ─────────────────────────────────────────────────────────────────

    private fun dir(context: Context) = File(context.filesDir, DIR)
    private fun file(context: Context, id: String) = File(dir(context), "$id.json")

    private fun uniqueName(existingNames: Set<String>, baseName: String): String {
        if (baseName !in existingNames) return baseName
        var index = 2
        while (true) {
            val candidate = "$baseName ($index)"
            if (candidate !in existingNames) return candidate
            index += 1
        }
    }

    private fun DocumentTemplate.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("aspectRatio", aspectRatio.toDouble())
        put("fields", JSONArray().also { arr ->
            fields.forEach { f ->
                arr.put(JSONObject().apply {
                    put("key",    f.key)
                    put("label",  f.label)
                    put("left",   f.left.toDouble())
                    put("top",    f.top.toDouble())
                    put("right",  f.right.toDouble())
                    put("bottom", f.bottom.toDouble())
                })
            }
        })
    }

    private fun fromJson(obj: JSONObject): DocumentTemplate {
        val arr = obj.getJSONArray("fields")
        return DocumentTemplate(
            id          = obj.getString("id"),
            name        = obj.getString("name"),
            aspectRatio = obj.optDouble("aspectRatio", CardFormat.ID_CARD.aspectRatio.toDouble()).toFloat(),
            fields      = (0 until arr.length()).map { i ->
                arr.getJSONObject(i).let { f ->
                    TemplateField(
                        key    = f.getString("key"),
                        label  = f.getString("label"),
                        left   = f.getDouble("left").toFloat(),
                        top    = f.getDouble("top").toFloat(),
                        right  = f.getDouble("right").toFloat(),
                        bottom = f.getDouble("bottom").toFloat()
                    )
                }
            }
        )
    }
}
