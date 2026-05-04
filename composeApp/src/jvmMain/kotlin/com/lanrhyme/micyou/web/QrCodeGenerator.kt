package com.lanrhyme.micyou.web

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage

object QrCodeGenerator {
    private const val DEFAULT_SIZE = 256
    private const val MARGIN = 2

    fun generateQrCodeImage(text: String, size: Int = DEFAULT_SIZE): BufferedImage {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to MARGIN,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )

        val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)

        for (x in 0 until size) {
            for (y in 0 until size) {
                val color = if (bitMatrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                image.setRGB(x, y, color)
            }
        }

        return image
    }
}
