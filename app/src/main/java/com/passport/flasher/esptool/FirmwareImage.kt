package com.passport.flasher.esptool

class FirmwareImage {
    companion object {
        val DEFAULT_ADDRESSES = mapOf(
            "bootloader" to 0x0000L,
            "partition-table" to 0x8000L,
            "ota_data_initial" to 0xf000L,
            "phy_init" to 0xf000L,
            "nvs" to 0x9000L,
            "otadata" to 0xe000L,
            "app" to 0x10000L,
        )

        fun inferAddress(fileName: String): Long {
            val lower = fileName.lowercase()
            for ((key, addr) in DEFAULT_ADDRESSES) {
                if (lower.contains(key)) return addr
            }
            return 0x10000L
        }
    }
}