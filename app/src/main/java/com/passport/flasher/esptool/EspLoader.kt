package com.passport.flasher.esptool

import com.passport.flasher.usb.UsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.min

interface FlashTerminal {
    fun write(msg: String)
    fun error(msg: String)
    fun info(msg: String)
    fun debug(msg: String)
}

data class FirmwareFile(val name: String, val data: ByteArray, var address: Long)

data class FlashOptions(
    val files: List<FirmwareFile>,
    val compress: Boolean = true,
    val flashMode: String = "keep",
    val flashFreq: String = "keep",
    val flashSize: String = "keep",
    val eraseAll: Boolean = false,
    val calculateMD5Hash: Boolean = true,
    val reportProgress: ((fileIndex: Int, bytesSent: Int, totalBytes: Int) -> Unit)? = null,
)

class EspLoader(
    val transport: UsbTransport,
    private val baudrate: Int = 115200,
    private val terminal: FlashTerminal? = null,
) {
    companion object {
        const val ESP_FLASH_BEGIN = 0x02
        const val ESP_FLASH_DATA = 0x03
        const val ESP_FLASH_END = 0x04
        const val ESP_MEM_BEGIN = 0x05
        const val ESP_MEM_END = 0x06
        const val ESP_MEM_DATA = 0x07
        const val ESP_SYNC = 0x08
        const val ESP_WRITE_REG = 0x09
        const val ESP_READ_REG = 0x0a
        const val ESP_SPI_FLASH_MD5 = 0x13
        const val ESP_ERASE_FLASH = 0xd0
        const val ESP_ERASE_REGION = 0xd1
        const val ESP_FLASH_DEFL_BEGIN = 0x10
        const val ESP_FLASH_DEFL_DATA = 0x11
        const val ESP_FLASH_DEFL_END = 0x12
        const val ESP_READ_FLASH = 0xd2
        const val ESP_RUN_USER_CODE = 0xd3
        const val ESP_CHECKSUM_MAGIC = 0xef
        const val ROM_INVALID_RECV_MSG = 0x05
        const val DEFAULT_TIMEOUT_MS = 3000L
        const val CHIP_DETECT_MAGIC_REG_ADDR = 0x40001000L
        const val USB_JTAG_SERIAL_PID = 0x1001
        const val ESP_RAM_BLOCK = 0x1800
        const val FLASH_WRITE_SIZE = 0x4000
        const val WRITE_BLOCK_ATTEMPTS = 3
        const val ERASE_WRITE_TIMEOUT_PER_MB = 40000L
        const val ERASE_REGION_TIMEOUT_PER_MB = 30000L
        const val CHIP_ERASE_TIMEOUT_MS = 120000L
        const val ESP32C3_MAGIC_1 = 0x6921506fL
        const val ESP32C3_MAGIC_2 = 0x1b31506fL
        const val ESP32C3_MAGIC_3 = 0x4881606fL
        const val ESP32C3_MAGIC_4 = 0x4361606fL
        const val USB_DOWNLOAD_MODE_MAGIC = 0x01010000L
    }

    var isStub = false
        private set
    var chipName: String = "unknown"
        private set
    private var romBaudrate = 115200
    private var syncStubDetected = false
    private var frameAcc = SlipCodec.FrameAccumulator()
    private var pendingBytes = byteArrayOf()

    private fun info(msg: String) = terminal?.info(msg)
    private fun debug(msg: String) = terminal?.debug(msg)
    private fun error(msg: String) = terminal?.error(msg)
    private fun write(msg: String) = terminal?.write(msg)

    private fun intToByteArray(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte(),
    )

    private fun shortToByteArray(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
    )

    private fun byteArrayToInt(b0: Byte, b1: Byte, b2: Byte, b3: Byte): Int =
        (b0.toInt() and 0xff) or ((b1.toInt() and 0xff) shl 8) or
                ((b2.toInt() and 0xff) shl 16) or ((b3.toInt() and 0xff) shl 24)

    private fun checksum(data: ByteArray, state: Int = ESP_CHECKSUM_MAGIC): Int {
        var s = state
        for (b in data) s = s xor (b.toInt() and 0xff)
        return s
    }

    private fun appendArray(a: ByteArray, b: ByteArray): ByteArray {
        val c = ByteArray(a.size + b.size)
        a.copyInto(c)
        b.copyInto(c, a.size)
        return c
    }

    private fun padTo(data: ByteArray, alignment: Int, padChar: Byte = 0xff.toByte()): ByteArray {
        val mod = data.size % alignment
        if (mod == 0) return data
        val padding = ByteArray(alignment - mod) { padChar }
        return data + padding
    }

    private suspend fun read(timeoutMs: Long): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val raw = withContext(Dispatchers.IO) { transport.readRaw(50) }
            if (raw != null) pendingBytes += raw
            for (i in pendingBytes.indices) {
                val frame = SlipCodec.decode(pendingBytes[i], frameAcc)
                if (frame != null) {
                    pendingBytes = pendingBytes.drop(i + 1).toByteArray()
                    return frame
                }
            }
            pendingBytes = byteArrayOf()
        }
        throw IOException("Read timeout after ${timeoutMs}ms")
    }

    private suspend fun write(data: ByteArray) {
        val encoded = SlipCodec.encode(data)
        withContext(Dispatchers.IO) { transport.write(encoded) }
    }

    private suspend fun readPacket(
        op: Int? = null, timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Pair<Int, ByteArray> {
        for (i in 0 until 100) {
            val p = read(timeoutMs)
            if (p.size < 8) continue
            val resp = p[0].toInt() and 0xff
            if (resp != 1) continue
            val opRet = p[1].toInt() and 0xff
            val value = byteArrayToInt(p[4], p[5], p[6], p[7])
            val data = p.copyOfRange(8, p.size)
            if (op == null || opRet == op) return Pair(value, data)
            if (data.size >= 2 && (data[0].toInt() and 0xff) != 0 && (data[1].toInt() and 0xff) == ROM_INVALID_RECV_MSG) {
                flushInput()
                throw IOException("Unsupported command error")
            }
        }
        throw IOException("Invalid response")
    }

    private suspend fun command(
        op: Int? = null, data: ByteArray = byteArrayOf(), chk: Int = 0,
        waitResponse: Boolean = true, timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Pair<Int, ByteArray> {
        if (op != null) {
            val pkt = ByteArray(8 + data.size)
            pkt[0] = 0x00; pkt[1] = op.toByte()
            pkt[2] = (data.size and 0xff).toByte(); pkt[3] = ((data.size shr 8) and 0xff).toByte()
            pkt[4] = (chk and 0xff).toByte(); pkt[5] = ((chk shr 8) and 0xff).toByte()
            pkt[6] = ((chk shr 16) and 0xff).toByte(); pkt[7] = ((chk shr 24) and 0xff).toByte()
            data.copyInto(pkt, 8)
            write(pkt)
        }
        if (!waitResponse) return Pair(0, byteArrayOf())
        return readPacket(op, timeoutMs)
    }

    private suspend fun checkCommand(
        opDescription: String, op: Int? = null, data: ByteArray = byteArrayOf(),
        chk: Int = 0, responseDataLength: Int = 0, timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Any {
        debug("check_command $opDescription")
        val resp = command(op, data, chk, timeoutMs = timeoutMs)
        if (resp.second.size < responseDataLength + 2) {
            val status = if (resp.second.size >= 2) resp.second.sliceArray(0..1)
                else byteArrayOf(resp.second.firstOrNull() ?: 0, 0)
            if ((status[0].toInt() and 0xff) != 0) {
                throw IOException("Failed to $opDescription with status ${status.joinToString { "0x%02x".format(it) }}")
            }
            throw IOException("Failed to $opDescription - only got ${resp.second.size} bytes data")
        }
        val status0 = resp.second[responseDataLength].toInt() and 0xff
        if (status0 != 0) {
            throw IOException("Failed to $opDescription with status ${resp.second[responseDataLength]} ${resp.second[responseDataLength + 1]}")
        }
        return if (responseDataLength > 0) resp.second.copyOfRange(0, responseDataLength)
        else resp.first
    }

    suspend fun sync() {
        debug("Sync")
        val cmd = ByteArray(36)
        cmd[0] = 0x07; cmd[1] = 0x07; cmd[2] = 0x12; cmd[3] = 0x20
        for (i in 0 until 32) cmd[4 + i] = 0x55
        var resp = command(ESP_SYNC, cmd, timeoutMs = 100)
        syncStubDetected = resp.first == 0
        for (i in 0 until 7) {
            resp = readPacket(ESP_SYNC, 100)
            syncStubDetected = syncStubDetected && resp.first == 0
        }
    }

    private suspend fun connectAttempt(mode: String): String {
        debug("connect_attempt $mode")
        if (mode == "usb_reset" || transport.isPassport()) {
            usbJtagSerialReset()
        } else {
            delay(100)
        }
        val readBytes = pendingBytes
        val bootStr = readBytes.decodeToString()
        val bootMatch = Regex("boot:(0x[0-9a-fA-F]+)").find(bootStr)
        val downloadMatch = bootStr.contains("waiting for download")
        if (bootMatch != null) debug("bootMode=${bootMatch.groupValues[1]} downloadMode=$downloadMatch")

        for (i in 0 until 5) {
            debug("Sync attempt $i")
            flushInput()
            try {
                sync()
                return "success"
            } catch (e: Exception) {
                debug("Sync error: ${e.message}")
            }
        }
        return if (bootMatch != null) {
            if (downloadMatch) "Download mode detected but no sync reply - check TX path"
            else "Wrong boot mode detected (${bootMatch.groupValues[1]})"
        } else "Failed to connect"
    }

    private suspend fun usbJtagSerialReset() {
        transport.setRTS(false); transport.setDTR(false); delay(100)
        transport.setDTR(true); transport.setRTS(false); delay(100)
        transport.setRTS(true); transport.setDTR(false); transport.setRTS(true); delay(100)
        transport.setRTS(false); transport.setDTR(false)
    }

    suspend fun connect(mode: String = "default_reset", attempts: Int = 7, detecting: Boolean = true) {
        info("Connecting...")
        transport.setBaudrate(romBaudrate)
        var resp: String
        for (i in 0 until attempts) {
            resp = connectAttempt(mode)
            if (resp == "success") break
        }
        resp = try { connectAttempt(mode) } catch (e: Exception) { e.message ?: "unknown" }
        if (resp != "success") throw IOException("Failed to connect with device: $resp")
        info("Connected")
        if (detecting) {
            val magic = readReg(CHIP_DETECT_MAGIC_REG_ADDR)
            chipName = when (magic.toLong() and 0xffffffffL) {
                ESP32C3_MAGIC_1, ESP32C3_MAGIC_2, ESP32C3_MAGIC_3, ESP32C3_MAGIC_4 -> "ESP32-C3"
                else -> "Unknown (0x${(magic.toLong() and 0xffffffffL).toString(16)})"
            }
            info("Detected chip: $chipName")
        }
    }

    suspend fun readReg(addr: Long, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Int {
        val pkt = intToByteArray(addr.toInt())
        val val_ = command(ESP_READ_REG, pkt, timeoutMs = timeoutMs)
        return val_.first
    }

    suspend fun writeReg(addr: Long, value: Int, mask: Int = -1, delayUs: Int = 0, delayAfterUs: Int = 0) {
        var pkt = appendArray(intToByteArray(addr.toInt()), intToByteArray(value))
        pkt = appendArray(pkt, intToByteArray(mask))
        pkt = appendArray(pkt, intToByteArray(delayUs))
        if (delayAfterUs > 0) {
            pkt = appendArray(pkt, intToByteArray(Esp32C3Rom.UART_DATE_REG_ADDR.toInt()))
            pkt = appendArray(pkt, intToByteArray(0))
            pkt = appendArray(pkt, intToByteArray(0))
            pkt = appendArray(pkt, intToByteArray(delayAfterUs))
        }
        checkCommand("write target memory", ESP_WRITE_REG, pkt)
    }

    suspend fun flashBegin(size: Long, offset: Long): Int {
        val numBlocks = ((size + FLASH_WRITE_SIZE - 1) / FLASH_WRITE_SIZE).toInt()
        val eraseSize = Esp32C3Rom.getEraseSize(offset, size)
        val timeout = if (isStub) DEFAULT_TIMEOUT_MS
        else timeoutPerMb(ERASE_REGION_TIMEOUT_PER_MB, size)
        debug("flash_begin eraseSize=$eraseSize numBlocks=$numBlocks writeSize=$FLASH_WRITE_SIZE offset=0x${offset.toString(16)}")
        var pkt = appendArray(intToByteArray(eraseSize.toInt()), intToByteArray(numBlocks))
        pkt = appendArray(pkt, intToByteArray(FLASH_WRITE_SIZE))
        pkt = appendArray(pkt, intToByteArray(offset.toInt()))
        if (!isStub) pkt = appendArray(pkt, intToByteArray(0))
        checkCommand("enter Flash download mode", ESP_FLASH_BEGIN, pkt, timeoutMs = timeout)
        return numBlocks
    }

    suspend fun flashBlock(data: ByteArray, seq: Int, timeoutMs: Long) {
        var pkt = appendArray(intToByteArray(data.size), intToByteArray(seq))
        pkt = appendArray(pkt, intToByteArray(0))
        pkt = appendArray(pkt, intToByteArray(0))
        pkt = appendArray(pkt, data)
        val chk = checksum(data)
        for (attempt in WRITE_BLOCK_ATTEMPTS downTo 1) {
            try {
                checkCommand("write to target Flash after seq $seq", ESP_FLASH_DATA, pkt, chk, timeoutMs = timeoutMs)
                return
            } catch (e: Exception) {
                if (attempt == 1) throw e
                debug("Block $seq write failed, retrying...")
                delay(150)
            }
        }
    }

    suspend fun flashFinish(reboot: Boolean = false, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        val pkt = intToByteArray(if (reboot) 0 else 1)
        checkCommand("leave Flash mode", ESP_FLASH_END, pkt, timeoutMs = timeoutMs)
    }

    suspend fun flashDeflBegin(size: Long, compsize: Long, offset: Long): Int {
        val numBlocks = ((compsize + FLASH_WRITE_SIZE - 1) / FLASH_WRITE_SIZE).toInt()
        val eraseBlocks = ((size + FLASH_WRITE_SIZE - 1) / FLASH_WRITE_SIZE).toInt()
        val writeSize = if (isStub) size else eraseBlocks * FLASH_WRITE_SIZE.toLong()
        val timeout = if (isStub) DEFAULT_TIMEOUT_MS else timeoutPerMb(ERASE_REGION_TIMEOUT_PER_MB, writeSize)
        info("Compressed $size bytes to $compsize...")
        var pkt = appendArray(intToByteArray(writeSize.toInt()), intToByteArray(numBlocks))
        pkt = appendArray(pkt, intToByteArray(FLASH_WRITE_SIZE))
        pkt = appendArray(pkt, intToByteArray(offset.toInt()))
        if (!isStub) pkt = appendArray(pkt, intToByteArray(0))
        checkCommand("enter compressed flash mode", ESP_FLASH_DEFL_BEGIN, pkt, timeoutMs = timeout)
        return numBlocks
    }

    suspend fun flashDeflBlock(data: ByteArray, seq: Int, timeoutMs: Long) {
        var pkt = appendArray(intToByteArray(data.size), intToByteArray(seq))
        pkt = appendArray(pkt, intToByteArray(0))
        pkt = appendArray(pkt, intToByteArray(0))
        pkt = appendArray(pkt, data)
        val chk = checksum(data)
        for (attempt in WRITE_BLOCK_ATTEMPTS downTo 1) {
            try {
                checkCommand("write compressed data to flash after seq $seq", ESP_FLASH_DEFL_DATA, pkt, chk, timeoutMs = timeoutMs)
                return
            } catch (e: Exception) {
                if (attempt == 1) throw e
                debug("Compressed block $seq write failed, retrying...")
                delay(150)
            }
        }
    }

    suspend fun flashDeflFinish(reboot: Boolean = false, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        val pkt = intToByteArray(if (reboot) 0 else 1)
        checkCommand("leave compressed flash mode", ESP_FLASH_DEFL_END, pkt, timeoutMs = timeoutMs)
    }

    suspend fun eraseFlash() {
        info("Erasing flash (this may take a while)...")
        checkCommand("erase flash", ESP_ERASE_FLASH, timeoutMs = CHIP_ERASE_TIMEOUT_MS)
        info("Chip erase completed")
    }

    suspend fun flashMd5sum(addr: Long, size: Long): String {
        val timeout = timeoutPerMb(8000L, size)
        var pkt = appendArray(intToByteArray(addr.toInt()), intToByteArray(size.toInt()))
        pkt = appendArray(pkt, intToByteArray(0)); pkt = appendArray(pkt, intToByteArray(0))
        val respLen = if (isStub) 16 else 32
        val res = checkCommand("calculate md5sum", ESP_SPI_FLASH_MD5, pkt, responseDataLength = respLen, timeoutMs = timeout)
        return (res as ByteArray).joinToString("") { "%02x".format(it) }
    }

    suspend fun memBegin(size: Long, blocks: Int, blocksize: Int, offset: Long) {
        var pkt = appendArray(intToByteArray(size.toInt()), intToByteArray(blocks))
        pkt = appendArray(pkt, intToByteArray(blocksize))
        pkt = appendArray(pkt, intToByteArray(offset.toInt()))
        checkCommand("enter RAM download mode", ESP_MEM_BEGIN, pkt)
    }

    suspend fun memBlock(buffer: ByteArray, seq: Int) {
        var pkt = appendArray(intToByteArray(buffer.size), intToByteArray(seq))
        pkt = appendArray(pkt, intToByteArray(0)); pkt = appendArray(pkt, intToByteArray(0))
        pkt = appendArray(pkt, buffer)
        val chk = checksum(buffer)
        checkCommand("write to target RAM", ESP_MEM_DATA, pkt, chk)
    }

    suspend fun memFinish(entrypoint: Long) {
        val isEntry = if (entrypoint == 0L) 1 else 0
        val pkt = appendArray(intToByteArray(isEntry), intToByteArray(entrypoint.toInt()))
        checkCommand("leave RAM download mode", ESP_MEM_END, pkt, timeoutMs = 200)
    }

    suspend fun runStub() {
        if (syncStubDetected) {
            info("Stub already running")
            return
        }
        info("Uploading stub...")
        val textBytes = Base64.getDecoder().decode(StubData.TEXT)
        val dataBytes = Base64.getDecoder().decode(StubData.DATA)
        val stubs = listOf(textBytes to StubData.TEXT_START, dataBytes to StubData.DATA_START)
        for ((stub, start) in stubs) {
            if (stub.isEmpty()) continue
            val blocks = (stub.size + ESP_RAM_BLOCK - 1) / ESP_RAM_BLOCK
            memBegin(stub.size.toLong(), blocks, ESP_RAM_BLOCK, start)
            for (seq in 0 until blocks) {
                val from = seq * ESP_RAM_BLOCK
                val to = minOf(from + ESP_RAM_BLOCK, stub.size)
                memBlock(stub.copyOfRange(from, to), seq)
            }
        }
        info("Running stub...")
        memFinish(StubData.ENTRY)
        val response = read(DEFAULT_TIMEOUT_MS)
        val responseStr = response.decodeToString()
        if (responseStr != "OHAI") throw IOException("Failed to start stub. Unexpected response: $responseStr")
        info("Stub running...")
        isStub = true
    }

    suspend fun changeBaud() {
        if (baudrate == romBaudrate) return
        info("Changing baudrate to $baudrate")
        val secondArg = if (isStub) romBaudrate else 0
        val pkt = appendArray(intToByteArray(baudrate), intToByteArray(secondArg))
        command(0x0f, pkt)
        delay(50)
        transport.setBaudrate(baudrate)
        delay(100)
    }

    private fun timeoutPerMb(secondsPerMb: Long, sizeBytes: Long): Long {
        val result = secondsPerMb * (sizeBytes / 1_000_000)
        return if (result < 3000) 3000 else result
    }

    private fun flushInput() {
        pendingBytes = byteArrayOf()
        frameAcc = SlipCodec.FrameAccumulator()
        transport.flushInput()
    }

    suspend fun readFlashId(): Int {
        val SPIFLASH_RDID = 0x9f
        val base = Esp32C3Rom.SPI_REG_BASE
        val SPI_CMD_REG = base + 0x00
        val SPI_USR_CMD = 1 shl 18
        val SPI_USR_REG = base + 0x18
        val SPI_USR2_REG = base + 0x20
        val SPI_W0_REG = base + 0x58
        val SPI_USR_COMMAND = 1 shl 31
        val SPI_USR_MISO = 1 shl 28
        val oldUsr = readReg(SPI_USR_REG.toLong())
        val oldUsr2 = readReg(SPI_USR2_REG.toLong())
        writeReg(SPI_USR_REG.toLong(), SPI_USR_COMMAND or SPI_USR_MISO)
        val cmdVal = (7 shl 28) or SPIFLASH_RDID
        writeReg(SPI_USR2_REG.toLong(), cmdVal)
        writeReg(SPI_W0_REG.toLong(), 0)
        writeReg(SPI_CMD_REG.toLong(), SPI_USR_CMD)
        for (i in 0 until 10) {
            val v = readReg(SPI_CMD_REG.toLong()) and SPI_USR_CMD
            if (v == 0) break
            delay(10)
        }
        val status = readReg(SPI_W0_REG.toLong())
        writeReg(SPI_USR_REG.toLong(), oldUsr)
        writeReg(SPI_USR2_REG.toLong(), oldUsr2)
        return status
    }

    suspend fun detectFlashSize(): String {
        val flashId = readFlashId()
        val sizeId = (flashId shr 16) and 0xff
        val detected = mapOf(
            0x12 to "256KB", 0x13 to "512KB", 0x14 to "1MB", 0x15 to "2MB",
            0x16 to "4MB", 0x17 to "8MB", 0x18 to "16MB", 0x19 to "32MB",
            0x1a to "64MB", 0x1b to "128MB", 0x20 to "64MB", 0x21 to "128MB",
        )
        val size = detected[sizeId] ?: "4MB"
        info("Detected flash size: $size")
        return size
    }

    private fun md5Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun rawDeflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    suspend fun writeFlash(options: FlashOptions) {
        debug("writeFlash started")
        if (options.flashSize != "keep") {
            val flashEnd = flashSizeBytes(options.flashSize)
            for (f in options.files) {
                if (f.data.size + f.address > flashEnd) {
                    throw IOException("File ${f.name} doesn't fit in flash")
                }
            }
        }
        if (isStub && options.eraseAll) {
            eraseFlash()
        }
        for ((i, file) in options.files.withIndex()) {
            var image = file.data
            if (image.isEmpty()) continue
            image = padTo(image, 4)
            val address = file.address
            val uncsize = image.size.toLong()
            val calcmd5 = if (options.calculateMD5Hash) md5Hex(image) else null
            if (options.compress) {
                val compressed = rawDeflate(image)
                flashDeflBegin(uncsize, compressed.size.toLong(), address)
                var seq = 0
                var bytesSent = 0
                var imageOffset = 0
                val inflater = Inflater(true)
                var totalUncompressed = 0
                options.reportProgress?.invoke(i, 0, compressed.size)
                var timeoutMs = 5000L
                while (imageOffset < compressed.size) {
                    val blockSize = minOf(FLASH_WRITE_SIZE, compressed.size - imageOffset)
                    val block = compressed.copyOfRange(imageOffset, imageOffset + blockSize)
                    val isLast = imageOffset + blockSize >= compressed.size
                    val lenPrev = totalUncompressed
                    inflater.setInput(block)
                    val uncompBuf = ByteArray(blockSize * 2)
                    val n = inflater.inflate(uncompBuf)
                    if (n > 0) totalUncompressed += n
                    if (isLast) inflater.end()
                    val blockUncompressed = totalUncompressed - lenPrev
                    val bt = timeoutPerMb(ERASE_WRITE_TIMEOUT_PER_MB, blockUncompressed.toLong())
                    if (!isStub) timeoutMs = bt
                    flashDeflBlock(block, seq, timeoutMs)
                    if (isStub) timeoutMs = bt
                    bytesSent += block.size
                    imageOffset += blockSize
                    seq++
                    options.reportProgress?.invoke(i, bytesSent, compressed.size)
                }
                info("Wrote $uncsize bytes ($bytesSent compressed) at 0x${address.toString(16)}")
                if (isStub) flashDeflFinish(false, timeoutMs)
            } else {
                flashBegin(uncsize, address)
                var seq = 0
                var bytesSent = 0
                var imageOffset = 0
                options.reportProgress?.invoke(i, 0, image.size.toInt())
                var timeoutMs = 5000L
                while (imageOffset < image.size) {
                    val blockSize = minOf(FLASH_WRITE_SIZE, image.size - imageOffset)
                    var block = image.copyOfRange(imageOffset, imageOffset + blockSize)
                    if (block.size < FLASH_WRITE_SIZE) {
                        val padded = ByteArray(FLASH_WRITE_SIZE) { 0xff.toByte() }
                        block.copyInto(padded)
                        block = padded
                    }
                    val bt = timeoutPerMb(ERASE_WRITE_TIMEOUT_PER_MB, block.size.toLong())
                    if (!isStub) timeoutMs = bt
                    flashBlock(block, seq, timeoutMs)
                    if (isStub) timeoutMs = bt
                    bytesSent += block.size
                    imageOffset += blockSize
                    seq++
                    options.reportProgress?.invoke(i, bytesSent, image.size.toInt())
                }
                info("Wrote ${image.size} bytes at 0x${address.toString(16)}")
                if (isStub) flashFinish(false, timeoutMs)
            }
            if (calcmd5 != null) {
                info("File md5: $calcmd5")
                val flashMd5 = flashMd5sum(address, uncsize)
                info("Flash md5: $flashMd5")
                if (calcmd5 != flashMd5) throw IOException("MD5 mismatch! File=$calcmd5 Flash=$flashMd5")
                info("Hash verified")
            }
        }
        info("Leaving...")
    }

    private fun flashSizeBytes(flashSize: String): Long {
        return when {
            "KB" in flashSize -> flashSize.replace("KB", "").toLong() * 1024
            "MB" in flashSize -> flashSize.replace("MB", "").toLong() * 1024 * 1024
            else -> throw IllegalArgumentException("Unknown flash size: $flashSize")
        }
    }

    suspend fun after(mode: String = "hard_reset") {
        when (mode) {
            "hard_reset" -> {
                info("Hard resetting via RTS pin...")
                transport.setRTS(false)
                delay(200)
            }
            "soft_reset" -> {
                info("Soft resetting...")
                softReset(false)
            }
            "no_reset_stub" -> info("Staying in flasher stub.")
            else -> info("Staying in bootloader.")
        }
    }

    suspend fun softReset(stayInBootloader: Boolean) {
        if (!isStub) {
            if (stayInBootloader) return
            flashBegin(0, 0)
            flashFinish(false)
        } else {
            flashBegin(0, 0)
            flashFinish(true)
        }
    }
}

private object Base64 {
    private val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE = IntArray(256) { -1 }.also { arr ->
        ALPHABET.forEachIndexed { i, c -> arr[c.code] = i }
        arr['='.code] = 0
    }

    fun getDecoder() = Decoder()

    class Decoder {
        fun decode(str: String): ByteArray {
            val clean = str.filter { it != '\n' && it != '\r' && it != ' ' }
            val out = ByteArray(clean.length * 3 / 4)
            var pos = 0
            var i = 0
            while (i < clean.length) {
                val a = DECODE[clean[i].code]; val b = DECODE[clean[i + 1].code]
                val c = DECODE[clean[i + 2].code]; val d = DECODE[clean[i + 3].code]
                out[pos++] = ((a shl 2) or (b shr 4)).toByte()
                out[pos++] = ((b shl 4) or (c shr 2)).toByte()
                out[pos++] = ((c shl 6) or d).toByte()
                i += 4
            }
            return out.copyOf(pos)
        }
    }
}