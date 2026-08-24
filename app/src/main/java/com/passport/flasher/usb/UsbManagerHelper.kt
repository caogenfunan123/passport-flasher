package com.passport.flasher.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager

class UsbManagerHelper(private val usbManager: UsbManager) {
    companion object {
        const val VENDOR_ESPRESSIF = 0x303a
        const val PRODUCT_USB_SERIAL_JTAG = 0x1001
    }

    fun isHostSupported(): Boolean = true

    fun findPassport(): UsbDevice? {
        val devices = usbManager.deviceList ?: return null
        return devices.values.find { device ->
            device.vendorId == VENDOR_ESPRESSIF && device.productId == PRODUCT_USB_SERIAL_JTAG
        }
    }

    fun openDevice(device: UsbDevice): UsbDeviceConnection? {
        return usbManager.openDevice(device)
    }
}