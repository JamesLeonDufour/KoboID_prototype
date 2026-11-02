package com.koboid.scanner

/** Common interface for all document parsers. */
interface IdParser {
    /** Returns a map of field-key → extracted value. Empty map if nothing found. */
    fun parse(ocrText: String): Map<String, String>
}
