package com.koboid.scanner

data class IdCardFields(
    val firstName: String = "",
    val lastName: String = "",
    val dateOfBirth: String = "",
    val idNumber: String = "",
    val expiryDate: String = "",
    val nationality: String = ""
)
