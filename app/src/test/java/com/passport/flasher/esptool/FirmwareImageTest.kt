package com.passport.flasher.esptool

import org.junit.Assert
import org.junit.Test

class FirmwareImageTest {

    @Test
    fun inferAddressFromNames() {
        Assert.assertEquals(0x0000L, FirmwareImage.inferAddress("bootloader.bin"))
        Assert.assertEquals(0x8000L, FirmwareImage.inferAddress("partition-table.bin"))
        Assert.assertEquals(0x10000L, FirmwareImage.inferAddress("app.bin"))
        Assert.assertEquals(0x9000L, FirmwareImage.inferAddress("nvs.bin"))
        Assert.assertEquals(0xF000L, FirmwareImage.inferAddress("phy_init.bin"))
    }

    @Test
    fun inferAddressFallbackToApp() {
        Assert.assertEquals(0x10000L, FirmwareImage.inferAddress("unknown_random.bin"))
    }
}