package com.example.nichirin

import java.util.UUID

val UUID_FFE0: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
val UUID_FFE1: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb") // Write / Write No Response
val UUID_FFE2: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb") // Notify
val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

const val MODBUS_ADDR = 0x01

const val REG_MODE = 0x0000
const val REG_HUE = 0x0001
const val REG_SAT = 0x0002
const val REG_VAL = 0x0003
const val REG_PARAM = 0x0004

const val REG_BAND_BASE = 0x0100
const val REG_BAND_COUNT = 12
