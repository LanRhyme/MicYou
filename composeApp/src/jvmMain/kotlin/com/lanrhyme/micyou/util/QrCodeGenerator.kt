package com.lanrhyme.micyou.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.common.BitMatrix
import java.awt.image.BufferedImage

object QrCodeGenerator {
    private const val DEFAULT_SIZE = 256
    private const val QUIET_ZONE = 2

    fun generateQrCodeImageBitmap(content: String, size: Int = DEFAULT_SIZE): ImageBitmap {
        val matrix = generateBitMatrix(content, size)
        return matrixToImageBitmap(matrix, size)
    }

    fun generateBitMatrix(content: String, size: Int = DEFAULT_SIZE): BitMatrix {
        val writer = QRCodeWriter()
        val hints = mapOf(EncodeHintType.MARGIN to QUIET_ZONE)
        return writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    }

    fun matrixToImageBitmap(matrix: BitMatrix, size: Int): ImageBitmap {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = java.awt.Color.WHITE
        graphics.fillRect(0, 0, size, size)
        graphics.color = java.awt.Color.BLACK

        val matrixSize = matrix.width
        val scale = size.toDouble() / matrixSize

        for (x in 0 until matrixSize) {
            for (y in 0 until matrixSize) {
                if (matrix.get(x, y)) {
                    val px = (x * scale).toInt()
                    val py = (y * scale).toInt()
                    val pw = maxOf(1, ((x + 1) * scale).toInt() - px)
                    val ph = maxOf(1, ((y + 1) * scale).toInt() - py)
                    graphics.fillRect(px, py, pw, ph)
                }
            }
        }
        graphics.dispose()
        return image.toComposeImageBitmap()
    }
}
