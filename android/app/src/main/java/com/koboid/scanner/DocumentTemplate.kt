package com.koboid.scanner

import android.graphics.RectF

/** A user-defined document scanning template. Persisted as JSON by [TemplateStore]. */
data class DocumentTemplate(
    val id: String,
    val name: String,
    val aspectRatio: Float = CardFormat.ID_CARD.aspectRatio,
    val fields: List<TemplateField>
)

/**
 * One labelled region within a document template.
 *
 * Coordinates are **normalised** (0.0–1.0) relative to the document image dimensions,
 * so they remain valid regardless of capture resolution.
 */
data class TemplateField(
    /** Intent-extra key returned to KoboCollect, e.g. "first_name" */
    val key: String,
    /** Human-readable label shown in the review screen, e.g. "First Name" */
    val label: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun toRectF() = RectF(left, top, right, bottom)
}
