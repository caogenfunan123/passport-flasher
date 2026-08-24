package com.passport.flasher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.passport.flasher.databinding.ActivityMainBinding
import com.passport.flasher.esptool.FirmwareFile
import com.passport.flasher.esptool.FirmwareImage
import com.passport.flasher.ui.ConnectionState
import com.passport.flasher.ui.FlasherViewModel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream

class MainActivity : AppCompatActivity() {
    companion object {
        const val ACTION_USB_PERMISSION = "com.passport.flasher.USB_PERMISSION"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: FlasherViewModel

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    device?.let { viewModel.connect(it) }
                } else {
                    Toast.makeText(this@MainActivity, "USB permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val usbAttachedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (device != null && viewModel.connectionState.value == ConnectionState.DISCONNECTED) {
                requestPermissionAndConnect(device)
            }
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        val files = uris.map { uri -> readFirmwareFile(uri) }.filterNotNull()
        if (files.isNotEmpty()) {
            val withAddresses = if (files.size == 1) {
                val single = files[0]
                if (single.name.lowercase().contains("bootloader")) {
                    listOf(single.copy(address = 0x0L))
                } else {
                    listOf(single.copy(address = 0x0L))
                }
            } else {
                files.map { f ->
                    val addr = FirmwareImage.inferAddress(f.name)
                    f.copy(address = addr)
                }
            }
            viewModel.setFirmwareFiles(withAddresses)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_PassportFlasher)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = FlasherViewModel(application)

        registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
        registerReceiver(usbAttachedReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED))

        binding.connectBtn.setOnClickListener {
            when (viewModel.connectionState.value) {
                ConnectionState.DISCONNECTED -> {
                    val device = viewModel.findPassport()
                    if (device != null) {
                        requestPermissionAndConnect(device)
                    } else {
                        Toast.makeText(this, "No Passport device found", Toast.LENGTH_SHORT).show()
                    }
                }
                ConnectionState.CONNECTED -> viewModel.disconnect()
                else -> {}
            }
        }

        binding.selectFirmwareBtn.setOnClickListener {
            filePickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }

        binding.writeBtn.setOnClickListener { viewModel.startWrite() }
        binding.eraseBtn.setOnClickListener { viewModel.eraseDevice() }
        binding.clearLogsBtn.setOnClickListener { viewModel.clearLogs() }

        observeViewModel()
    }

    private fun requestPermissionAndConnect(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val permissionIntent = Intent(this, javaClass).apply {
            action = ACTION_USB_PERMISSION
        }
        val pi = android.app.PendingIntent.getBroadcast(
            this, 0, permissionIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        usbManager.requestPermission(device, pi)
    }

    private fun readFirmwareFile(uri: Uri): FirmwareFile? {
        return try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            val name = cursor?.use {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                it.moveToFirst()
                if (nameIndex >= 0) it.getString(nameIndex) else "unknown.bin"
            } ?: "unknown.bin"

            val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                BufferedInputStream(stream).readBytes()
            } ?: return null

            FirmwareFile(name = name, data = bytes, address = 0L)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connectionState.collect { state ->
                    updateUiForState(state)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flashState.collect { state ->
                    when (state) {
                        com.passport.flasher.ui.FlashState.IDLE -> {
                            binding.progressBar.isVisible = false
                            binding.writeBtn.isEnabled = true
                            binding.eraseBtn.isEnabled = true
                        }
                        com.passport.flasher.ui.FlashState.BUSY -> {
                            binding.progressBar.isVisible = true
                            binding.writeBtn.isEnabled = false
                            binding.eraseBtn.isEnabled = false
                        }
                        com.passport.flasher.ui.FlashState.DONE -> {
                            binding.progressBar.isVisible = false
                            binding.writeBtn.isEnabled = true
                            binding.eraseBtn.isEnabled = true
                        }
                        com.passport.flasher.ui.FlashState.ERROR -> {
                            binding.progressBar.isVisible = false
                            binding.writeBtn.isEnabled = true
                            binding.eraseBtn.isEnabled = true
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.progress.collect { p ->
                    binding.progressBar.progress = (p * 100).toInt()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.chipInfo.collect { info ->
                    binding.chipInfoText.text = info
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.logs.collect { entries ->
                    binding.logView.text = entries.joinToString("\n") {
                        if (it.isError) "E: ${it.msg}" else it.msg
                    }
                }
            }
        }
    }

    private fun updateUiForState(state: ConnectionState) {
        when (state) {
            ConnectionState.DISCONNECTED -> {
                binding.connectBtn.text = "Connect"
                binding.chipInfoText.text = "No device"
                binding.firmwareGroup.isVisible = false
                binding.actionGroup.isVisible = false
            }
            ConnectionState.CONNECTING -> {
                binding.connectBtn.text = "Connecting..."
                binding.connectBtn.isEnabled = false
            }
            ConnectionState.CONNECTED -> {
                binding.connectBtn.text = "Disconnect"
                binding.connectBtn.isEnabled = true
                binding.firmwareGroup.isVisible = true
                binding.actionGroup.isVisible = true
            }
            ConnectionState.ERROR -> {
                binding.connectBtn.text = "Retry"
                binding.connectBtn.isEnabled = true
                binding.firmwareGroup.isVisible = false
                binding.actionGroup.isVisible = false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(usbAttachedReceiver) } catch (_: Exception) {}
    }
}