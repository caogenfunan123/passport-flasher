package com.passport.flasher.esptool

object Esp32C3Rom {
    const val CHIP_NAME = "ESP32-C3"
    const val IMAGE_CHIP_ID = 5
    const val EFUSE_BASE = 0x60008800L
    const val MAC_EFUSE_REG = EFUSE_BASE + 0x044
    const val UART_CLKDIV_REG = 0x3ff40014
    const val UART_CLKDIV_MASK = 0xfffff
    const val UART_DATE_REG_ADDR = 0x6000007c
    const val FLASH_WRITE_SIZE = 0x400
    const val BOOTLOADER_FLASH_OFFSET = 0
    const val SPI_REG_BASE = 0x60002000
    const val SPI_USR_OFFS = 0x18
    const val SPI_USR1_OFFS = 0x1c
    const val SPI_USR2_OFFS = 0x20
    const val SPI_MOSI_DLEN_OFFS = 0x24
    const val SPI_MISO_DLEN_OFFS = 0x28
    const val SPI_W0_OFFS = 0x58

    val FLASH_SIZES = mapOf("1MB" to 0x00, "2MB" to 0x10, "4MB" to 0x20, "8MB" to 0x30,
        "16MB" to 0x40, "32MB" to 0x50, "64MB" to 0x60, "128MB" to 0x70)
    val FLASH_FREQUENCY = mapOf("80m" to 0xf, "40m" to 0x0, "26m" to 0x1, "20m" to 0x2)

    @Suppress("UNUSED_PARAMETER")
    fun getEraseSize(offset: Long, size: Long): Long = size
}