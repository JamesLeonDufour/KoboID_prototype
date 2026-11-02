package com.koboid.scanner

/**
 * Supported document frame formats with their physical aspect ratios (width / height).
 *
 * ISO 7810 standards:
 *  ID_CARD  — ID-1: 85.60 × 53.98 mm  (credit-card size, Belgian eID, driver's licence)
 *  PASSPORT — ID-3: 125  × 88    mm  (bio-data page)
 *  DOCUMENT — A4 portrait: 297 × 210 mm
 */
enum class CardFormat(val label: String, val aspectRatio: Float) {
    ID_CARD("ID Card",   1.586f),
    PASSPORT("Passport", 1.420f),
    DOCUMENT("Document", 0.707f);

    companion object {
        fun fromName(name: String?): CardFormat? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

        fun fromAspectRatio(aspectRatio: Float): CardFormat =
            entries.minByOrNull { kotlin.math.abs(it.aspectRatio - aspectRatio) } ?: ID_CARD
    }
}
