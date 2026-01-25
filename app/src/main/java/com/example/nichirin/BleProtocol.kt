package com.example.nichirin

fun buildSpectrumFrame(bands12: IntArray): ByteArray {
    require(bands12.size == REG_BAND_COUNT) { "bands12 must be length=$REG_BAND_COUNT" }
    val values = IntArray(REG_BAND_COUNT) { i -> bands12[i].coerceIn(0, 255) }
    return buildWriteMultipleRegisters(REG_BAND_BASE, values)
}

fun buildWriteSingleRegister(reg: Int, value: Int): ByteArray {
    val payload = ByteArray(6)
    payload[0] = MODBUS_ADDR.toByte()
    payload[1] = 0x06
    payload[2] = ((reg ushr 8) and 0xFF).toByte()
    payload[3] = (reg and 0xFF).toByte()
    payload[4] = ((value ushr 8) and 0xFF).toByte()
    payload[5] = (value and 0xFF).toByte()
    return appendCrc(payload)
}

fun buildWriteMultipleRegisters(startReg: Int, values: IntArray): ByteArray {
    val count = values.size
    val byteCount = count * 2
    val payload = ByteArray(7 + byteCount)
    payload[0] = MODBUS_ADDR.toByte()
    payload[1] = 0x10
    payload[2] = ((startReg ushr 8) and 0xFF).toByte()
    payload[3] = (startReg and 0xFF).toByte()
    payload[4] = ((count ushr 8) and 0xFF).toByte()
    payload[5] = (count and 0xFF).toByte()
    payload[6] = (byteCount and 0xFF).toByte()
    for (i in values.indices) {
        val v = values[i].coerceIn(0, 0xFFFF)
        payload[7 + i * 2] = ((v ushr 8) and 0xFF).toByte()
        payload[8 + i * 2] = (v and 0xFF).toByte()
    }
    return appendCrc(payload)
}

fun buildReadHoldingRegisters(startReg: Int, count: Int): ByteArray {
    val payload = ByteArray(6)
    payload[0] = MODBUS_ADDR.toByte()
    payload[1] = 0x03
    payload[2] = ((startReg ushr 8) and 0xFF).toByte()
    payload[3] = (startReg and 0xFF).toByte()
    payload[4] = ((count ushr 8) and 0xFF).toByte()
    payload[5] = (count and 0xFF).toByte()
    return appendCrc(payload)
}

fun crc16Modbus(data: ByteArray, len: Int = data.size): Int {
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

fun verifyCrc(frame: ByteArray): Boolean {
    if (frame.size < 3) return false
    val dataLen = frame.size - 2
    val crc = crc16Modbus(frame, dataLen)
    val lo = frame[frame.size - 2].toInt() and 0xFF
    val hi = frame[frame.size - 1].toInt() and 0xFF
    return ((hi shl 8) or lo) == crc
}

private fun appendCrc(payload: ByteArray): ByteArray {
    val crc = crc16Modbus(payload, payload.size)
    val frame = ByteArray(payload.size + 2)
    System.arraycopy(payload, 0, frame, 0, payload.size)
    frame[frame.size - 2] = (crc and 0xFF).toByte()
    frame[frame.size - 1] = ((crc ushr 8) and 0xFF).toByte()
    return frame
}
