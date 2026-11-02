package com.koboid.scanner

/**
 * Parses Machine Readable Zone (MRZ) text per ICAO 9303.
 *
 * Supported formats:
 *  TD1   - 3 lines x 30 chars  (ID cards)
 *  TD2   - 2 lines x 36 chars  (older ID cards / residence permits)
 *  TD3   - 2 lines x 44 chars  (passports)
 *  MRV-A - 2 lines x 44 chars  (visa, large format)
 *  MRV-B - 2 lines x 36 chars  (visa, small format)
 *
 * The parser now scores multiple candidate line groups and prefers the one
 * with the strongest structure and check-digit validation.
 */
object MrzParser : IdParser {

    override fun parse(ocrText: String): Map<String, String> =
        detectAndParse(ocrText) ?: emptyMap()

    fun detectAndParse(ocrText: String): Map<String, String>? {
        val lines = ocrText.lines()
            .map(::sanitizeLine)
            .filter { it.length >= 30 }

        if (lines.isEmpty()) return null

        val candidates = mutableListOf<ParsedMrz>()

        for (i in 0 until lines.lastIndex) {
            val l1 = lines[i]
            val l2 = lines[i + 1]

            if (l1.length == 44 && l2.length == 44) {
                parseTd3(l1, l2)?.let(candidates::add)
                parseMrva(l1, l2)?.let(candidates::add)
            }
            if (l1.length == 36 && l2.length == 36) {
                parseTd2(l1, l2)?.let(candidates::add)
                parseMrvb(l1, l2)?.let(candidates::add)
            }
        }

        for (i in 0 until lines.size - 2) {
            val l1 = lines[i]
            val l2 = lines[i + 1]
            val l3 = lines[i + 2]
            if (l1.length == 30 && l2.length == 30 && l3.length == 30) {
                parseTd1(l1, l2, l3)?.let(candidates::add)
            }
        }

        return candidates.maxByOrNull { it.score }?.fields
    }

    private fun parseTd3(l1: String, l2: String): ParsedMrz? {
        if (l1.length != 44 || l2.length != 44) return null

        val documentType = l1.substring(0, 2).trimFill()
        if (!documentType.startsWith("P")) return null

        val issuer = normalizeAlphaField(l1.substring(2, 5)).trimFill()
        val (lastName, firstName) = splitName(l1.substring(5, 44))
        val documentNumber = l2.substring(0, 9).trimFill()
        val nationality = normalizeAlphaField(l2.substring(10, 13)).trimFill()
        val dob = mrzDate(l2.substring(13, 19))
        val sex = normalizeSex(l2.substring(20, 21))
        val expiry = mrzDate(l2.substring(21, 27))
        val optionalData = l2.substring(28, 42).trimFill()

        val score = scoreCommon(
            format = "TD3",
            documentType = documentType,
            issuer = issuer,
            nationality = nationality,
            lastName = lastName,
            firstName = firstName,
            dob = dob,
            expiry = expiry
        ) + scoreChecks(
            checkDigitMatches(l2.substring(0, 9), l2[9]),
            checkDigitMatches(l2.substring(13, 19), l2[19]),
            checkDigitMatches(l2.substring(21, 27), l2[27]),
            checkDigitMatches(l2.substring(28, 42), l2[42]),
            checkDigitMatches(
                l2.substring(0, 10) + l2.substring(13, 20) + l2.substring(21, 43),
                l2[43]
            )
        )

        return ParsedMrz(
            fields = baseMap(
                format = "TD3",
                documentType = documentType,
                issuer = issuer,
                lastName = lastName,
                firstName = firstName,
                documentNumber = documentNumber,
                nationality = nationality,
                dob = dob,
                expiry = expiry,
                sex = sex,
                optionalData = optionalData
            ),
            score = score
        )
    }

    private fun parseTd2(l1: String, l2: String): ParsedMrz? {
        if (l1.length != 36 || l2.length != 36) return null

        val documentType = l1.substring(0, 2).trimFill()
        if (documentType.startsWith("V")) return null

        val issuer = normalizeAlphaField(l1.substring(2, 5)).trimFill()
        val (lastName, firstName) = splitName(l1.substring(5, 36))
        val documentNumber = l2.substring(0, 9).trimFill()
        val nationality = normalizeAlphaField(l2.substring(10, 13)).trimFill()
        val dob = mrzDate(l2.substring(13, 19))
        val sex = normalizeSex(l2.substring(20, 21))
        val expiry = mrzDate(l2.substring(21, 27))
        val optionalData = l2.substring(28, 35).trimFill()

        val score = scoreCommon(
            format = "TD2",
            documentType = documentType,
            issuer = issuer,
            nationality = nationality,
            lastName = lastName,
            firstName = firstName,
            dob = dob,
            expiry = expiry
        ) + scoreChecks(
            checkDigitMatches(l2.substring(0, 9), l2[9]),
            checkDigitMatches(l2.substring(13, 19), l2[19]),
            checkDigitMatches(l2.substring(21, 27), l2[27]),
            checkDigitMatches(
                l2.substring(0, 10) + l2.substring(13, 20) + l2.substring(21, 35),
                l2[35]
            )
        )

        return ParsedMrz(
            fields = baseMap(
                format = "TD2",
                documentType = documentType,
                issuer = issuer,
                lastName = lastName,
                firstName = firstName,
                documentNumber = documentNumber,
                nationality = nationality,
                dob = dob,
                expiry = expiry,
                sex = sex,
                optionalData = optionalData
            ),
            score = score
        )
    }

    private fun parseTd1(l1: String, l2: String, l3: String): ParsedMrz? {
        if (l1.length != 30 || l2.length != 30 || l3.length != 30) return null

        val documentType = l1.substring(0, 2).trimFill()
        if (documentType.startsWith("V")) return null

        val issuer = normalizeAlphaField(l1.substring(2, 5)).trimFill()
        val documentNumber = l1.substring(5, 14).trimFill()
        val optionalData = (l1.substring(15, 30) + l2.substring(18, 29)).trimFill()
        val dob = mrzDate(l2.substring(0, 6))
        val sex = normalizeSex(l2.substring(7, 8))
        val expiry = mrzDate(l2.substring(8, 14))
        val nationality = normalizeAlphaField(l2.substring(15, 18)).trimFill()
        val (lastName, firstName) = splitName(l3)

        val composite = l1.substring(5, 30) + l2.substring(0, 7) + l2.substring(8, 15) + l2.substring(18, 29)
        val score = scoreCommon(
            format = "TD1",
            documentType = documentType,
            issuer = issuer,
            nationality = nationality,
            lastName = lastName,
            firstName = firstName,
            dob = dob,
            expiry = expiry
        ) + scoreChecks(
            checkDigitMatches(l1.substring(5, 14), l1[14]),
            checkDigitMatches(l2.substring(0, 6), l2[6]),
            checkDigitMatches(l2.substring(8, 14), l2[14]),
            checkDigitMatches(composite, l2[29])
        )

        return ParsedMrz(
            fields = baseMap(
                format = "TD1",
                documentType = documentType,
                issuer = issuer,
                lastName = lastName,
                firstName = firstName,
                documentNumber = documentNumber,
                nationality = nationality,
                dob = dob,
                expiry = expiry,
                sex = sex,
                optionalData = optionalData
            ),
            score = score
        )
    }

    private fun parseMrva(l1: String, l2: String): ParsedMrz? {
        if (l1.length != 44 || l2.length != 44) return null

        val documentType = l1.substring(0, 2).trimFill()
        if (!documentType.startsWith("V")) return null

        val issuer = normalizeAlphaField(l1.substring(2, 5)).trimFill()
        val (lastName, firstName) = splitName(l1.substring(5, 44))
        val documentNumber = l2.substring(0, 9).trimFill()
        val nationality = normalizeAlphaField(l2.substring(10, 13)).trimFill()
        val dob = mrzDate(l2.substring(13, 19))
        val sex = normalizeSex(l2.substring(20, 21))
        val expiry = mrzDate(l2.substring(21, 27))
        val optionalData = l2.substring(28, 44).trimFill()

        val score = scoreCommon(
            format = "MRV-A",
            documentType = documentType,
            issuer = issuer,
            nationality = nationality,
            lastName = lastName,
            firstName = firstName,
            dob = dob,
            expiry = expiry
        ) + scoreChecks(
            checkDigitMatches(l2.substring(0, 9), l2[9]),
            checkDigitMatches(l2.substring(13, 19), l2[19]),
            checkDigitMatches(l2.substring(21, 27), l2[27])
        )

        return ParsedMrz(
            fields = baseMap(
                format = "MRV-A",
                documentType = documentType,
                issuer = issuer,
                lastName = lastName,
                firstName = firstName,
                documentNumber = documentNumber,
                nationality = nationality,
                dob = dob,
                expiry = expiry,
                sex = sex,
                optionalData = optionalData
            ),
            score = score
        )
    }

    private fun parseMrvb(l1: String, l2: String): ParsedMrz? {
        if (l1.length != 36 || l2.length != 36) return null

        val documentType = l1.substring(0, 2).trimFill()
        if (!documentType.startsWith("V")) return null

        val issuer = normalizeAlphaField(l1.substring(2, 5)).trimFill()
        val (lastName, firstName) = splitName(l1.substring(5, 36))
        val documentNumber = l2.substring(0, 9).trimFill()
        val nationality = normalizeAlphaField(l2.substring(10, 13)).trimFill()
        val dob = mrzDate(l2.substring(13, 19))
        val sex = normalizeSex(l2.substring(20, 21))
        val expiry = mrzDate(l2.substring(21, 27))
        val optionalData = l2.substring(28, 36).trimFill()

        val score = scoreCommon(
            format = "MRV-B",
            documentType = documentType,
            issuer = issuer,
            nationality = nationality,
            lastName = lastName,
            firstName = firstName,
            dob = dob,
            expiry = expiry
        ) + scoreChecks(
            checkDigitMatches(l2.substring(0, 9), l2[9]),
            checkDigitMatches(l2.substring(13, 19), l2[19]),
            checkDigitMatches(l2.substring(21, 27), l2[27])
        )

        return ParsedMrz(
            fields = baseMap(
                format = "MRV-B",
                documentType = documentType,
                issuer = issuer,
                lastName = lastName,
                firstName = firstName,
                documentNumber = documentNumber,
                nationality = nationality,
                dob = dob,
                expiry = expiry,
                sex = sex,
                optionalData = optionalData
            ),
            score = score
        )
    }

    private fun baseMap(
        format: String,
        documentType: String,
        issuer: String,
        lastName: String,
        firstName: String,
        documentNumber: String,
        nationality: String,
        dob: String,
        expiry: String,
        sex: String,
        optionalData: String
    ): Map<String, String> = linkedMapOf(
        "mrz_format" to format,
        "document_type" to documentType,
        "issuer" to issuer,
        "last_name" to lastName,
        "first_name" to firstName,
        "id_number" to documentNumber,
        "nationality" to nationality,
        "dob" to dob,
        "expiry" to expiry,
        "sex" to sex,
        "optional_data" to optionalData
    ).filterValues { it.isNotBlank() }

    private fun scoreCommon(
        format: String,
        documentType: String,
        issuer: String,
        nationality: String,
        lastName: String,
        firstName: String,
        dob: String,
        expiry: String
    ): Int {
        var score = 0
        if (format.startsWith("TD") || format.startsWith("MRV")) score += 2
        if (documentType.length in 1..2 && documentType.all { it.isLetter() }) score += 8
        if (issuer.length == 3 && issuer.all { it.isLetter() }) score += 8
        if (nationality.length == 3 && nationality.all { it.isLetter() }) score += 8
        if (lastName.isNotBlank()) score += 6
        if (firstName.isNotBlank()) score += 4
        if (dob.matches(Regex("\\d{2}/\\d{2}/\\d{4}"))) score += 6
        if (expiry.matches(Regex("\\d{2}/\\d{2}/\\d{4}"))) score += 6
        return score
    }

    private fun scoreChecks(vararg results: Boolean?): Int =
        results.sumOf { result ->
            when (result) {
                true -> 25
                false -> -20
                null -> 0
            }
        }

    private fun checkDigitMatches(field: String, checkDigitChar: Char): Boolean? {
        val expected = parseCheckDigit(checkDigitChar) ?: return null
        val actual = computeCheckDigit(field)
        return actual == expected
    }

    private fun computeCheckDigit(value: String): Int {
        val weights = intArrayOf(7, 3, 1)
        return value.withIndex().sumOf { (index, ch) ->
            mrzValue(ch) * weights[index % weights.size]
        } % 10
    }

    private fun mrzValue(ch: Char): Int = when (val normalized = ch.uppercaseChar()) {
        '<' -> 0
        in '0'..'9' -> normalized - '0'
        in 'A'..'Z' -> normalized - 'A' + 10
        else -> 0
    }

    private fun parseCheckDigit(ch: Char): Int? = when (ch.uppercaseChar()) {
        in '0'..'9' -> ch.digitToInt()
        'O', 'Q', 'D', 'U' -> 0
        'I', 'L' -> 1
        'Z' -> 2
        'S' -> 5
        'G' -> 6
        'B' -> 8
        else -> null
    }

    private fun splitName(raw: String): Pair<String, String> {
        val parts = raw.split("<<", limit = 2)
        val last = normalizeAlphaField(parts[0]).replace("<", " ").trim()
        val first = if (parts.size > 1) normalizeAlphaField(parts[1]).replace("<", " ").trim() else ""
        return last to first
    }

    private fun mrzDate(raw: String): String {
        val s = normalizeDigits(raw)
        if (!s.matches(Regex("\\d{6}"))) return raw.trimFill()
        val yy = s.substring(0, 2).toIntOrNull() ?: return raw.trimFill()
        val mm = s.substring(2, 4)
        val dd = s.substring(4, 6)
        val year = if (yy <= 30) 2000 + yy else 1900 + yy
        return "$dd/$mm/$year"
    }

    private fun normalizeSex(value: String): String {
        val normalized = value.trimFill().uppercase()
        return when (normalized) {
            "M", "F", "X", "<" -> normalized.replace("<", "X")
            "0" -> "O"
            else -> normalized.ifBlank { "X" }
        }
    }

    private fun sanitizeLine(line: String): String =
        line.trim()
            .replace(" ", "")
            .uppercase()
            .map { ch ->
                when {
                    ch.isLetterOrDigit() || ch == '<' -> ch
                    else -> '<'
                }
            }
            .joinToString("")

    private fun normalizeDigits(value: String): String =
        value.uppercase().map { ch ->
            when (ch) {
                in '0'..'9' -> ch
                'O', 'Q', 'D', 'U' -> '0'
                'I', 'L' -> '1'
                'Z' -> '2'
                'S' -> '5'
                'G' -> '6'
                'B' -> '8'
                else -> ch
            }
        }.joinToString("")

    private fun normalizeAlphaField(value: String): String =
        value.uppercase().map { ch ->
            when (ch) {
                '0' -> 'O'
                '1' -> 'I'
                '2' -> 'Z'
                '5' -> 'S'
                '6' -> 'G'
                '8' -> 'B'
                else -> ch
            }
        }.joinToString("")

    private fun String.trimFill() = replace("<", "").trim()

    private data class ParsedMrz(
        val fields: Map<String, String>,
        val score: Int
    )
}
