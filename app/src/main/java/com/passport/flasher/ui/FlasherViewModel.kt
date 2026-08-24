package com.passport.flasher.ui

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.passport.flasher.esptool.EspLoader
import com.passport.flasher.esptool.FlashTerminal
import com.passport.flasher.esptool.FirmwareFile
import com.passport.flasher.esptool.FlashOptions
import com.passport.flasher.usb.UsbManagerHelper
import com.passport.flasher.usb.UsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
enum class FlashState { IDLE, BUSY, DONE, ERROR }

class FlasherViewModel(application: Application) : AndroidViewModel(application), FlashTerminal {
    private val usbManager = application.getSystemService(Context.USB_SERVICE) as UsbManager
    private val usbHelper = UsbManagerHelper(usbManager)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _flashState = MutableStateFlow(FlashState.IDLE)
    val flashState: StateFlow<FlashState> = _flashState.asStateFlow()

    private val _chipInfo = MutableStateFlow("")
    val chipInfo: StateFlow<String> = _chipInfo.asStateFlow()

    private val _logs = MutableStateFlow(listOf<LogEntry>())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _firmwareFiles = MutableStateFlow<List<FirmwareFile>>(emptyList())
    val firmwareFiles: StateFlow<List<FirmwareFile>> = _firmwareFiles.asStateFlow()

    private val _baudrate = MutableStateFlow(115200)
    val baudrate: StateFlow<Int> = _baudrate.asStateFlow()

    private var loader: EspLoader? = null

    data class LogEntry(val msg: String, val isError: Boolean = false, val timestamp: Long = System.currentTimeMillis())

    fun isOtgSupported(): Boolean = usbHelper.isHostSupported()
    fun findPassport(): UsbDevice? = usbHelper.findPassport()

    fun connect(device: UsbDevice) {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.CONNECTING
            addLog("Connecting to ${device.productName ?: device.deviceName}...")
            try {
                val connection = withContext(Dispatchers.IO) {
                    usbHelper.openDevice(device)
                }
                if (connection == null) {
                    _connectionState.value = ConnectionState.ERROR
                    addLog("Failed to open USB device", true)
                    return@launch
                }
                val transport = UsbTransport(usbManager, device, connection)
                withContext(Dispatchers.IO) { transport.open() }
                val espLoader = EspLoader(transport, _baudrate.value, this@FlasherViewModel)
                withContext(Dispatchers.IO) { espLoader.connect() }
                chipName = espLoader.chipName
                _chipInfo.value = "Chip: ${espLoader.chipName}"
                if (espLoader.chipName == "ESP32-C3") {
                    withContext(Dispatchers.IO) { espLoader.runStub() }
                    _chipInfo.value = "Chip: ESP32-C3 (Stub running)"
                    try {
                        val flashId = withContext(Dispatchers.IO) { espLoader.readFlashId() }
                        val sizeId = (flashId shr 16) and 0xff
                        val sizeMap = mapOf(0x17 to "8MB", 0x18 to "16MB", 0x16 to "4MB")
                        val flashSize = sizeMap[sizeId] ?: "Unknown"
                        _chipInfo.value = "Chip: ESP32-C3, Flash: $flashSize"
                        addLog("Flash ID: 0x${flashId.toString(16)}, Size: $flashSize")
                    } catch (e: Exception) {
                        addLog("Flash ID read failed: ${e.message}", true)
                    }
                }
                loader = espLoader
                _connectionState.value = ConnectionState.CONNECTED
                addLog("Connected successfully")
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.ERROR
                addLog("Connection failed: ${e.message}", true)
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            loader?.let {
                try { withContext(Dispatchers.IO) { it.after("hard_reset") } } catch (_: Exception) {}
            }
            loader?.let { it.transport.close() }
            loader = null
            _connectionState.value = ConnectionState.DISCONNECTED
            _chipInfo.value = ""
            addLog("Disconnected")
        }
    }

    fun setFirmwareFiles(files: List<FirmwareFile>) {
        _firmwareFiles.value = files
        addLog("Selected ${files.size} firmware file(s)")
        for (f in files) {
            addLog("  ${f.name} -> 0x${f.address.toString(16)} (${f.data.size} bytes)")
        }
    }

    fun setBaudrate(hz: Int) {
        _baudrate.value = hz
    }

    fun startWrite() {
        val ldr = loader ?: return
        val files = _firmwareFiles.value
        if (files.isEmpty()) { addLog("No firmware files selected", true); return }

        viewModelScope.launch {
            _flashState.value = FlashState.BUSY
            _progress.value = 0f
            addLog("Starting firmware write...")
            try {
                val options = FlashOptions(
                    files = files,
                    compress = true,
                    eraseAll = false,
                    calculateMD5Hash = true,
                    reportProgress = { _, sent, total ->
                        _progress.value = if (total > 0) sent.toFloat() / total else 0f
                    },
                )
                withContext(Dispatchers.IO) { ldr.writeFlash(options) }
                _progress.value = 1f
                _flashState.value = FlashState.DONE
                addLog("Firmware write completed successfully!")
                withContext(Dispatchers.IO) { ldr.after("hard_reset") }
                addLog("Device reset, exiting...")
            } catch (e: Exception) {
                _flashState.value = FlashState.ERROR
                addLog("Write failed: ${e.message}", true)
            }
        }
    }

    fun eraseDevice() {
        val ldr = loader ?: return
        viewModelScope.launch {
            _flashState.value = FlashState.BUSY
            addLog("Erasing device...")
            try {
                withContext(Dispatchers.IO) { ldr.eraseFlash() }
                _flashState.value = FlashState.DONE
                addLog("Erase completed")
            } catch (e: Exception) {
                _flashState.value = FlashState.ERROR
                addLog("Erase failed: ${e.message}", true)
            }
        }
    }

    fun clearLogs() { _logs.value = emptyList() }

    private var chipName = ""

    override fun write(msg: String) { addLog(msg) }
    override fun error(msg: String) { addLog("Error: $msg", true) }
    override fun info(msg: String) { addLog(msg) }
    override fun debug(msg: String) { addLog("Debug: $msg") }

    private fun addLog(msg: String, isError: Boolean = false) {
        val current = _logs.value
        _logs.value = current + LogEntry(msg, isError)
    }
}