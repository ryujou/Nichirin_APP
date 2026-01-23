package com.example.nichirin

import android.bluetooth.BluetoothDevice

enum class Screen { SCAN, DETAIL }

data class BleDeviceItem(
    val name: String,
    val address: String,
    val rssi: Int,
    val device: BluetoothDevice
)
