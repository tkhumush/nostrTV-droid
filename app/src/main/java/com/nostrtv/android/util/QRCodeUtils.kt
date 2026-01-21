package com.nostrtv.android.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility for generating QR codes efficiently.
 * Uses batch pixel setting (setPixels) instead of individual setPixel calls
 * for ~10x performance improvement.
 */
object QRCodeUtils {

    /**
     * Generate a QR code bitmap from a string.
     * This is a CPU-intensive operation and should be called from a background thread.
     *
     * @param content The string to encode
     * @param size The width and height of the QR code in pixels
     * @return The generated bitmap or null if encoding fails
     */
    fun generateQRCode(content: String, size: Int): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)

            // Use RGB_565 for black/white QR codes (half the memory of ARGB_8888)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)

            // Use batch pixel setting for much better performance
            // Single setPixels call vs size*size individual setPixel calls
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    pixels[y * size + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)

            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Generate a QR code bitmap on the Default dispatcher (CPU-optimized).
     * Call this from a coroutine context.
     */
    suspend fun generateQRCodeAsync(content: String, size: Int): Bitmap? {
        return withContext(Dispatchers.Default) {
            generateQRCode(content, size)
        }
    }
}
