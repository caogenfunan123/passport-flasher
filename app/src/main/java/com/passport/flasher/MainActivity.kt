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
import androidx.lifecycle.ViewModelProvider
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
                    Toast.makeText(this@MainActivity, getString(R.string.toast_usb_permission_denied), Toast.LENGTH_SHORT).show()
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
                // 单文件视为合并固件，写入 0x0
                listOf(files[0].copy(address = 0x0L))
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
        viewModel = ViewModelProvider(this)[FlasherViewModel::class.java]

        registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
        registerReceiver(usbAttachedReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED))

        binding.connectBtn.setOnClickListener {
            when (viewModel.connectionState.value) {
                ConnectionState.DISCONNECTED -> {
                    val device = viewModel.findPassport()
                    if (device != null) {
                        requestPermissionAndConnect(device)
                    } else {
                        Toast.makeText(this, getString(R.string.toast_no_device), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.toast_read_file_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.firmwareFiles.collect { files ->
                    binding.selectedFirmwareText.text = if (files.isEmpty()) {
                        ""
                    } else {
                        val lines = files.map { "${it.name} → 0x${it.address.toString(16)}（${it.data.size} 字节）" }
                        "已选择 ${files.size} 个固件文件：\n" + lines.joinToString("\n")
                    }
                }
            }
        }
    }

    private fun updateUiForState(state: ConnectionState) {
        when (state) {
            ConnectionState.DISCONNECTED -> {
                binding.connectBtn.text = getString(R.string.btn_connect)
                binding.chipInfoText.text = getString(R.string.status_no_device)
                binding.firmwareGroup.isVisible = false
                binding.actionGroup.isVisible = false
            }
            ConnectionState.CONNECTING -> {
                binding.connectBtn.text = getString(R.string.btn_connecting)
                binding.connectBtn.isEnabled = false
            }
            ConnectionState.CONNECTED -> {
                binding.connectBtn.text = getString(R.string.btn_disconnect)
                binding.connectBtn.isEnabled = true
                binding.firmwareGroup.isVisible = true
                binding.actionGroup.isVisible = true
            }
            ConnectionState.ERROR -> {
                binding.connectBtn.text = getString(R.string.btn_retry)
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