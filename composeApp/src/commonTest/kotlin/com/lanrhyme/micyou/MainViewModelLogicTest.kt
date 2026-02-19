package com.lanrhyme.micyou

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainViewModelLogicTest {
    @Test
    fun migratesLegacyWifiUdpToWifi() {
        assertEquals(ConnectionMode.Wifi, mapConnectionModeFromSettings("WifiUdp"))
    }

    @Test
    fun fallsBackToWifiOnUnknownMode() {
        assertEquals(ConnectionMode.Wifi, mapConnectionModeFromSettings("UnknownMode"))
    }

    @Test
    fun resolvesAutoConfigForBluetooth() {
        val preset = resolveAutoConfigPreset(ConnectionMode.Bluetooth)
        assertEquals(SampleRate.Rate16000, preset.sampleRate)
        assertEquals(ChannelCount.Mono, preset.channelCount)
        assertEquals(AudioFormat.PCM_16BIT, preset.audioFormat)
    }

    @Test
    fun resolvesAutoConfigForWifiAndUsb() {
        val wifiPreset = resolveAutoConfigPreset(ConnectionMode.Wifi)
        val usbPreset = resolveAutoConfigPreset(ConnectionMode.Usb)

        assertEquals(SampleRate.Rate48000, wifiPreset.sampleRate)
        assertEquals(ChannelCount.Stereo, wifiPreset.channelCount)
        assertEquals(AudioFormat.PCM_16BIT, wifiPreset.audioFormat)

        assertEquals(wifiPreset, usbPreset)
    }

    @Test
    fun generatesTokenWithRequestedLength() {
        val token = generateAuthToken(length = 32)
        assertEquals(32, token.length)
        assertTrue(token.all { it.isLetterOrDigit() })
    }
}
