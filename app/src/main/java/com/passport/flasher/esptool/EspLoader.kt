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
            // 所有字节均已喂入 SLIP 状态机，避免下一轮重复解码
            pendingBytes = byteArrayOf()
        }
        throw IOException("读取超时：${timeoutMs}ms")
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
                throw IOException("不支持的命令")
            }
        }
        throw IOException("响应无效")
    }

    private suspend fun command(
        op: Int? = null, data: ByteArray = byteArrayOf(), chk: Int = 0,
        waitResponse: Boolean = true, timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Pair<Int, ByteArray> {
        if (op != null) {
            // 发送前丢弃残留数据（如上一条 fire-and-forget 写入的多余应答），
            // 否则会被误读为本次响应导致协议失序
            flushInput()
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
        debug("检查命令 $opDescription")
        val resp = command(op, data, chk, timeoutMs = timeoutMs)
        if (resp.second.size < responseDataLength + 2) {
            val status = if (resp.second.size >= 2) resp.second.sliceArray(0..1)
                else byteArrayOf(resp.second.firstOrNull() ?: 0, 0)
            if ((status[0].toInt() and 0xff) != 0) {
                throw IOException("执行 $opDescription 失败，状态：${status.joinToString { "0x%02x".format(it) }}")
            }
            throw IOException("执行 $opDescription 失败，仅收到 ${resp.second.size} 字节数据")
        }
        val status0 = resp.second[responseDataLength].toInt() and 0xff
        if (status0 != 0) {
            throw IOException("执行 $opDescription 失败，状态：${resp.second[responseDataLength]} ${resp.second[responseDataLength + 1]}")
        }
        return if (responseDataLength > 0) resp.second.copyOfRange(0, responseDataLength)
        else resp.first
    }

    suspend fun sync() {
        debug("同步")
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
        debug("连接尝试：$mode")
        if (mode == "usb_reset" || transport.isPassport()) {
            usbJtagSerialReset()
        } else {
            delay(100)
        }
        val readBytes = pendingBytes
        val bootStr = readBytes.decodeToString()
        val bootMatch = Regex("boot:(0x[0-9a-fA-F]+)").find(bootStr)
        val downloadMatch = bootStr.contains("waiting for download")
        if (bootMatch != null) debug("引导模式=${bootMatch.groupValues[1]} 下载模式=$downloadMatch")

        for (i in 0 until 5) {
            debug("同步尝试 $i")
            flushInput()
            try {
                sync()
                return "success"
            } catch (e: Exception) {
                debug("同步错误：${e.message}")
            }
        }
        return if (bootMatch != null) {
            if (downloadMatch) "检测到下载模式但无同步应答，请检查 TX 线路"
            else "检测到错误的引导模式（${bootMatch.groupValues[1]}）"
        } else "连接失败"
    }

    private suspend fun usbJtagSerialReset() {
        transport.setRTS(false); transport.setDTR(false); delay(100)
        transport.setDTR(true); transport.setRTS(false); delay(100)
        transport.setRTS(true); transport.setDTR(false); transport.setRTS(true); delay(100)
        transport.setRTS(false); transport.setDTR(false)
    }

    suspend fun connect(mode: String = "default_reset", attempts: Int = 7, detecting: Boolean = true) {
        info("正在连接...")
        transport.setBaudrate(romBaudrate)
        var resp = "连接失败"
        for (i in 0 until attempts) {
            resp = connectAttempt(mode)
            if (resp == "success") break
        }
        if (resp != "success") throw IOException("连接设备失败：$resp")
        info("已连接")
        if (detecting) {
            val magic = readReg(CHIP_DETECT_MAGIC_REG_ADDR)
            chipName = when (magic.toLong() and 0xffffffffL) {
                ESP32C3_MAGIC_1, ESP32C3_MAGIC_2, ESP32C3_MAGIC_3, ESP32C3_MAGIC_4 -> "ESP32-C3"
                else -> "未知（0x${(magic.toLong() and 0xffffffffL).toString(16)}）"
            }
            info("检测到芯片：$chipName")
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
                debug("块 $seq 写入失败，重试中...")
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
        info("压缩 $size 字节为 $compsize...")
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
                debug("压缩块 $seq 写入失败，重试中...")
                delay(150)
            }
        }
    }

    suspend fun flashDeflFinish(reboot: Boolean = false, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        val pkt = intToByteArray(if (reboot) 0 else 1)
        checkCommand("leave compressed flash mode", ESP_FLASH_DEFL_END, pkt, timeoutMs = timeoutMs)
    }

    suspend fun eraseFlash() {
        info("正在擦除 Flash（可能需要较长时间）...")
        checkCommand("erase flash", ESP_ERASE_FLASH, timeoutMs = CHIP_ERASE_TIMEOUT_MS)
        info("芯片擦除完成")
    }

    suspend fun flashMd5sum(addr: Long, size: Long): String {
        val timeout = timeoutPerMb(8000L, size)
        var pkt = appendArray(intToByteArray(addr.toInt()), intToByteArray(size.toInt()))
        pkt = appendArray(pkt, intToByteArray(0)); pkt = appendArray(pkt, intToByteArray(0))
        val respLen = if (isStub) 16 else 32
        val res = checkCommand("calculate md5sum", ESP_SPI_FLASH_MD5, pkt, responseDataLength = respLen, timeoutMs = timeout)
        // stub 返回 16 字节二进制 MD5；ROM 返回 32 字节 ASCII hex 文本
        return if (isStub) (res as ByteArray).joinToString("") { "%02x".format(it) }
        else String(res as ByteArray, Charsets.US_ASCII).lowercase()
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
            info("Stub 已在运行")
            return
        }
        info("上传 Stub...")
        val textBytes = java.util.Base64.getDecoder().decode(StubData.TEXT)
        val dataBytes = java.util.Base64.getDecoder().decode(StubData.DATA)
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
        info("运行 Stub...")
        memFinish(StubData.ENTRY)
        val response = read(DEFAULT_TIMEOUT_MS)
        val responseStr = response.decodeToString()
        if (responseStr != "OHAI") throw IOException("启动 Stub 失败，意外的响应：$responseStr")
        info("Stub 运行中...")
        isStub = true
    }

    suspend fun changeBaud() {
        if (baudrate == romBaudrate) return
        // USB-JTAG/Serial 通过 USB 直连，没有可调节的真实 UART 时钟，切换无意义且可能干扰 stub
        if (transport.isPassport()) {
            debug("USB-JTAG 设备跳过波特率切换")
            return
        }
        info("切换波特率至 $baudrate")
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
        val SPI_MISO_DLEN_REG = base + 0x28
        val SPI_W0_REG = base + 0x58
        val SPI_USR_COMMAND = 1 shl 31
        val SPI_USR_MISO = 1 shl 28
        val oldUsr = readReg(SPI_USR_REG.toLong())
        val oldUsr2 = readReg(SPI_USR2_REG.toLong())
        writeReg(SPI_MISO_DLEN_REG.toLong(), 23)
        writeReg(SPI_USR_REG.toLong(), SPI_USR_COMMAND or SPI_USR_MISO)
        val cmdVal = (7 shl 28) or SPIFLASH_RDID
        writeReg(SPI_USR2_REG.toLong(), cmdVal)
        writeReg(SPI_W0_REG.toLong(), 0)
        writeReg(SPI_CMD_REG.toLong(), SPI_USR_CMD)
        var i = 0
        while (i < 10) {
            val v = readReg(SPI_CMD_REG.toLong()) and SPI_USR_CMD
            if (v == 0) break
            delay(10)
            i++
        }
        // 与官方 runSpiflashCommand 一致：SPI 命令未完成时必须报错，静默继续会读到垃圾值
        if (i == 10) throw IOException("SPI 命令超时未完成")
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
        info("检测到 Flash 大小：$size")
        return size
    }

    private fun md5Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun rawDeflate(data: ByteArray): ByteArray {
        // nowrap=true 生成 raw deflate（RFC1951）；默认 false 是 zlib 流（0x78 头 + adler32 尾），stub 无法解压
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
        debug("开始写入 Flash")
        if (options.flashSize != "keep") {
            val flashEnd = flashSizeBytes(options.flashSize)
            for (f in options.files) {
                if (f.data.size + f.address > flashEnd) {
                    throw IOException("文件 ${f.name} 空间不足")
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
                // nowrap=true 与 rawDeflate 对应；仅用于本地估算每块的未压缩字节数（超时调整）
                val inflater = Inflater(true)
                val uncompBuf = ByteArray(65536)
                var totalUncompressed = 0
                options.reportProgress?.invoke(i, 0, compressed.size)
                var timeoutMs = 5000L
                try {
                    while (imageOffset < compressed.size) {
                        val blockSize = minOf(FLASH_WRITE_SIZE, compressed.size - imageOffset)
                        val block = compressed.copyOfRange(imageOffset, imageOffset + blockSize)
                        val lenPrev = totalUncompressed
                        // 必须耗尽本块全部输入：固定小缓冲会在高压缩比时截断并错乱跨块状态
                        inflater.setInput(block)
                        while (!inflater.finished()) {
                            val n = inflater.inflate(uncompBuf)
                            if (n == 0) break
                            totalUncompressed += n
                        }
                        val blockUncompressed = totalUncompressed - lenPrev
                        val bt = maxOf(3000L, timeoutPerMb(ERASE_WRITE_TIMEOUT_PER_MB, blockUncompressed.toLong()))
                        if (!isStub) timeoutMs = bt
                        flashDeflBlock(block, seq, timeoutMs)
                        if (isStub) timeoutMs = bt
                        bytesSent += block.size
                        imageOffset += blockSize
                        seq++
                        options.reportProgress?.invoke(i, bytesSent, compressed.size)
                    }
                } finally {
                    try { inflater.end() } catch (_: Exception) {}
                }
                info("写入 $uncsize 字节（压缩 $bytesSent 字节）地址 0x${address.toString(16)}")
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
                    val bt = maxOf(3000L, timeoutPerMb(ERASE_WRITE_TIMEOUT_PER_MB, block.size.toLong()))
                    if (!isStub) timeoutMs = bt
                    flashBlock(block, seq, timeoutMs)
                    if (isStub) timeoutMs = bt
                    bytesSent += block.size
                    imageOffset += blockSize
                    seq++
                    options.reportProgress?.invoke(i, bytesSent, image.size.toInt())
                }
                info("写入 ${image.size} 字节，地址 0x${address.toString(16)}")
                if (isStub) flashFinish(false, timeoutMs)
            }
            if (calcmd5 != null) {
                info("文件 MD5：$calcmd5")
                val flashMd5 = flashMd5sum(address, uncsize)
                info("Flash MD5：$flashMd5")
                if (calcmd5 != flashMd5) throw IOException("MD5 不匹配！文件=$calcmd5 Flash=$flashMd5")
                info("MD5 校验通过")
            }
        }
        info("退出...")
    }

    private fun flashSizeBytes(flashSize: String): Long {
        return when {
            "KB" in flashSize -> flashSize.replace("KB", "").toLong() * 1024
            "MB" in flashSize -> flashSize.replace("MB", "").toLong() * 1024 * 1024
            else -> throw IllegalArgumentException("未知 Flash 大小：$flashSize")
        }
    }

    suspend fun after(mode: String = "hard_reset") {
        when (mode) {
            "hard_reset" -> {
                info("正在复位设备...")
                if (transport.isPassport()) {
                    hardResetUsbJtagSerial()
                } else {
                    // 与官方 HardReset 一致：普通串口通过 RTS 脉冲复位
                    delay(100)
                    transport.setRTS(true)
                    delay(100)
                    transport.setRTS(false)
                }
            }
            "soft_reset" -> {
                info("软复位...")
                softReset(false)
            }
            "no_reset_stub" -> info("保持在 flasher stub 中。")
            else -> info("保持在 bootloader 中。")
        }
    }

    /**
     * USB-Serial/JTAG 设备写后复位，对齐官方 web-flasher 的 firmware-reset：
     * 写 USB_SERIAL_JTAG 外设寄存器（0x60008000）断开下载连接并重启进固件；
     * 失败时回退官方 custom_reset 序列 "D0|R1|W100|R0|W500|D0"。
     * 注意不能用 usbJtagSerialReset——那是把芯片拉进下载模式的序列。
     */
    private suspend fun hardResetUsbJtagSerial() {
        try {
            val base = 0x60008000L
            writeReg(base + 0xA8, 0x50D83AA1.toInt())
            writeReg(base + 0x94, 2000)
            writeReg(base + 0x90, 0xD0000102.toInt())
            writeReg(base + 0xA8, 0)
            delay(700)
        } catch (e: Exception) {
            debug("寄存器复位失败，回退 DTR/RTS 序列：${e.message}")
            transport.setDTR(false); transport.setRTS(true); delay(100)
            transport.setRTS(false); delay(500); transport.setDTR(false)
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
