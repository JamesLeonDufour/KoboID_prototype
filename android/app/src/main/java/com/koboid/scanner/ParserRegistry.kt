package com.koboid.scanner

import java.util.Locale

/**
 * Routes an OCR result to the correct parser and returns a uniform field map.
 *
 * @param cardType  value of the `card_type` / `scanMode` intent extra
 * @param ocrText   raw text from ML Kit
 */
object ParserRegistry {

    fun parse(cardType: String, ocrText: String): Map<String, String> {
        return when (cardType.uppercase(Locale.US)) {
            "MRZ"        -> MrzParser.parse(ocrText)
            "BELGIUM_ID" -> belgiumMap(IdCardParser.parseBelgiumId(ocrText))
            else         -> emptyMap()   // GENERIC returns empty; raw text is still returned
        }
    }

    /** Converts IdCardFields (legacy class) to the uniform String map. */
    private fun belgiumMap(f: IdCardFields) = mapOf(
        "first_name"  to f.firstName,
        "last_name"   to f.lastName,
        "dob"         to f.dateOfBirth,
        "id_number"   to f.idNumber,
        "expiry"      to f.expiryDate,
        "nationality" to f.nationality
    )

    /**
     * Converts a field map to display-ready strings for ReviewActivity.
     * Keys like "first_name" → "First name".
     */
    fun toDisplayLines(fields: Map<String, String>): List<String> =
        fields.entries
            .filter { it.value.isNotBlank() }
            .map { (k, v) -> "${k.replace('_', ' ').replaceFirstChar { it.uppercase() }}: $v" }

    /**
     * Converts a template's fields + extracted values to display lines,
     * preserving the user-defined label order.
     */
    fun toDisplayLines(template: DocumentTemplate, values: Map<String, String>): List<String> =
        template.fields.map { field ->
            "${field.label}: ${values[field.key]?.takeIf { it.isNotBlank() } ?: "-"}"
        }
}
