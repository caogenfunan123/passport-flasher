package com.passport.flasher.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * USB 传输层：封装 Android USB Host 的 bulk 读写与控制传输，
 * 语义对齐 esptool-js 的 Transport（webserial.js）。
 */
class UsbTransport(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
) {
    companion object {
        const val VENDOR_ESPRESSIF = 0x303a
        const val PRODUCT_USB_SERIAL_JTAG = 0x1001
        const val TAG = "UsbTransport"

        // CDC 控制请求
        const val REQ_GET_LINE_CODING = 0x21
        const val REQ_SET_LINE_CODING = 0x20
        const val REQ_SET_CONTROL_LINE_STATE = 0x22

        // bmRequestType: HOST_TO_DEVICE (0x00) | CLASS (0x20) | INTERFACE (0x01)
        const val HOST_TO_DEVICE_CLASS = 0x21
        const val DEVICE_TO_HOST_CLASS = 0xA1

        const val DEFAULT_BAUD = 115_200
    }

    private var claimedInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null

    @Volatile var isOpen = false
        private set

    private var rxBuffer = byteArrayOf()
    private val writeLock = Object()
    private val readLock = Object()

    fun open() {
        device.getInterface(0).let { intf ->
            claimedInterface = intf
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep
                    else if (ep.direction == UsbConstants.USB_DIR_OUT) bulkOut = ep
                }
            }
            val ok = connection.claimInterface(intf, true)
            if (!ok) throw IOException("claimInterface 失败")
            isOpen = true
        }
    }

    fun close() {
        isOpen = false
        synchronized(readLock) { rxBuffer = byteArrayOf() }
        claimedInterface?.let { intf ->
            runCatching { connection.releaseInterface(intf) }
            claimedInterface = null
        }
        runCatching { connection.close() }
    }

    fun getFriendlyName(): String = device.deviceName

    fun isPassport(): Boolean =
        device.vendorId == VENDOR_ESPRESSIF && device.productId == PRODUCT_USB_SERIAL_JTAG

    /** ESP32-C3 USB-Serial/JTAG 不支持标准 setSignals；通过 CDC 控制请求模拟 DTR/RTS。 */
    fun setDTR(state: Boolean) {
        isDtrHigh = state
        _setControlLineState((if (state) 1 else 0) or ((if (isRtsHigh) 1 else 0) shl 1))
    }

    private var isRtsHigh = false

    fun setRTS(state: Boolean) {
        isRtsHigh = state
        _setControlLineState((if (isDtrHigh) 1 else 0) or ((if (state) 1 else 0) shl 1))
    }

    private var isDtrHigh = false

    private fun _setControlLineState(value: Int) {
        val ret = connection.controlTransfer(
            HOST_TO_DEVICE_CLASS,
            REQ_SET_CONTROL_LINE_STATE,
            value,
            0,
            null,
            0,
            100,
        )
        if (ret < 0) Log.w(TAG, "SET_CONTROL_LINE_STATE failed ret=$ret")
    }

    /** 设置波特率：通过 SET_LINE_CODING（dwDTERate=hz, 8N1）。 */
    fun setBaudrate(hz: Int) {
        val data = ByteArray(7)
        data[0] = (hz and 0xff).toByte()
        data[1] = ((hz shr 8) and 0xff).toByte()
        data[2] = ((hz shr 16) and 0xff).toByte()
        data[3] = ((hz shr 24) and 0xff).toByte()
        data[4] = 0 // stop bits: 1
        data[5] = 0 // parity: none
        data[6] = 8 // data bits
        val ret = connection.controlTransfer(
            HOST_TO_DEVICE_CLASS,
            REQ_SET_LINE_CODING,
            0,
            0,
            data,
            data.size,
            100,
        )
        if (ret < 0) Log.w(TAG, "SET_LINE_CODING failed ret=$ret")
    }

    /** bulk 写，返回写入字节数。 */
    fun write(data: ByteArray, timeoutMs: Int = 1000): Int {
        // USB bulk 单包上限 64 字节，需要分包；UsbDeviceConnection.bulkTransfer 按端点 maxPacketSize 自动分包，但一次调用数据量较大的时候应手动分块。
        var written = 0
        var offset = 0
        while (offset < data.size) {
            val chunk = minOf(data.size - offset, 64)
            val n = bulkOut?.let { connection.bulkTransfer(it, data, offset, chunk, timeoutMs) } ?: -1
            if (n < 0) throw IOException("bulk write 失败, offset=$offset")
            written += n
            offset += n
        }
        return written
    }

    /** bulk 读，返回实际读到的字节（可能少于请求）。 */
    fun readRaw(timeoutMs: Int = 200): ByteArray? {
        val ep = bulkIn ?: return null
        val buf = ByteArray(ep.maxPacketSize)
        val n = connection.bulkTransfer(ep, buf, buf.size, timeoutMs)
        if (n < 0) return null
        return buf.copyOf(n)
    }

    fun flushInput() {
        synchronized(readLock) { rxBuffer = byteArrayOf() }
        // Android bulk 读是拉模式，残留应答留在 USB 驱动缓冲中，
        // 必须用短超时循环读取排空，仅清本地缓冲无效
        val ep = bulkIn ?: return
        val buf = ByteArray(ep.maxPacketSize)
        while (connection.bulkTransfer(ep, buf, buf.size, 10) > 0) { /* 排空 */ }
    }
}