package com.example.nichirin

/** 16 bytes: [01][20][12*U8][CRC_L][CRC_H] */
fun buildFrame(bands12: IntArray): ByteArray {
    require(bands12.size == 12) { "bands12 must be length=12" }
    val frame = ByteArray(16)
    frame[0] = FIXED_ADDR.toByte()
    frame[1] = FIXED_FUNC.toByte()
    for (i in 0 until 12) {
        frame[2 + i] = bands12[i].coerceIn(0, 255).toByte()
    }
    val crc = crc16Modbus(frame, 14)
    frame[14] = (crc and 0xFF).toByte()
    frame[15] = ((crc ushr 8) and 0xFF).toByte()
    return frame
}

fun crc16Modbus(data: ByteArray, len: Int): Int {
    var crc = 0xFFFF
    for (i in 0 until len) {
        crc = crc xor (data[i].toInt() and 0xFF)
        repeat(8) {
            crc = if ((crc and 0x0001) != 0) {
                (crc ushr 1) xor 0xA001
            } else {
                crc ushr 1
            }
        }
    }
    return crc and 0xFFFF
}
