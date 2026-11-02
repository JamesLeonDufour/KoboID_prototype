package com.koboid.scanner

import java.util.Locale
import java.util.regex.Pattern

object IdCardParser {

    private val DATE_PATTERN = Pattern.compile(
        "(\\d{2})[./-](\\d{2})[./-](\\d{4})|(\\d{2})\\s+(\\d{2})\\s+(\\d{4})"
    )

    fun parseBelgiumId(ocrText: String?): IdCardFields {
        ocrText ?: return IdCardFields()

        val lines = ocrText.split("\\r?\\n".toRegex())
        var firstName = ""
        var lastName = ""
        var nationality = ""
        var idNumber = ""
        var dateOfBirth = ""
        var expiryDate = ""

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val upper = line.uppercase(Locale.US)

            if (lastName.isEmpty() && containsAny(upper, "NOM", "NAME", "SURNAME", "FAMILY NAME")) {
                lastName = extractValue(line)
                if (lastName.isEmpty() && i + 1 < lines.size) lastName = lines[i + 1].trim()
                continue
            }
            if (firstName.isEmpty() && containsAny(upper, "PRENOM", "GIVEN", "FIRST NAME", "PRENOMS")) {
                firstName = extractValue(line)
                if (firstName.isEmpty() && i + 1 < lines.size) firstName = lines[i + 1].trim()
                continue
            }
            if (nationality.isEmpty() && containsAny(upper, "NATIONALITE", "NATIONALITY")) {
                nationality = extractIsoCode(extractValue(line))
                if (nationality.isEmpty() && i + 1 < lines.size) nationality = extractIsoCode(lines[i + 1].trim())
                continue
            }
            if (idNumber.isEmpty() && containsAny(upper, "NATIONAL REGISTER", "RIJKSREGISTERNR", "REGISTRE NATIONAL", "NATIONAL")) {
                idNumber = extractIdNumber(line)
                continue
            }
            if (idNumber.isEmpty() && containsAny(upper, "CARD", "CARTE", "KAART", "CARD N", "CARTE N", "KAARTNR")) {
                idNumber = extractIdNumber(line)
            }
        }

        if (idNumber.isEmpty()) idNumber = extractIdNumber(ocrText)

        val dates = extractDates(ocrText).sorted()
        if (dates.isNotEmpty()) {
            dateOfBirth = dates.first()
            expiryDate = dates.last()
        }

        return IdCardFields(firstName, lastName, dateOfBirth, idNumber, expiryDate, nationality)
    }

    private fun containsAny(value: String, vararg keys: String) = keys.any { value.contains(it) }

    private fun extractValue(line: String): String {
        val parts = line.split("[:\\s]{2,}".toRegex(), limit = 2)
        if (parts.size > 1) return parts[1].trim()
        val idx = line.indexOf(':')
        if (idx >= 0 && idx + 1 < line.length) return line.substring(idx + 1).trim()
        return line.replace("^[A-Za-z\\s\\-/.]+".toRegex(), "").trim()
    }

    private fun extractIdNumber(text: String): String {
        val digitsOnly = text.replace("[^0-9]".toRegex(), "")
        val matcher = Pattern.compile("\\d{11}").matcher(digitsOnly)
        if (matcher.find()) return matcher.group()!!
        val fallback = Pattern.compile("\\b\\d{9,12}\\b").matcher(text.replace("[^0-9]".toRegex(), " "))
        return if (fallback.find()) fallback.group()!!.trim() else ""
    }

    private fun extractIsoCode(value: String?): String {
        val trimmed = value?.trim()?.uppercase(Locale.US) ?: return ""
        if (trimmed.matches("^[A-Z]{3}$".toRegex())) return trimmed
        val matcher = Pattern.compile("\\b[A-Z]{3}\\b").matcher(trimmed)
        return if (matcher.find()) matcher.group()!! else ""
    }

    private fun extractDates(text: String): List<String> {
        val matcher = DATE_PATTERN.matcher(text)
        val results = mutableListOf<String>()
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                results.add(matcher.group(1)!!)
            } else if (matcher.group(4) != null) {
                results.add("${matcher.group(4)}/${matcher.group(5)}/${matcher.group(6)}")
            }
        }
        return results
    }
}
