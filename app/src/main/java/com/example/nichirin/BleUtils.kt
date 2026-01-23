package com.example.nichirin

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 3)
    for (b in this) sb.append(String.format("%02X ", b))
    return sb.toString().trim()
}
