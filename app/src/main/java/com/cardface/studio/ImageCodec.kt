package com.cardface.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import java.io.ByteArrayOutputStream

enum class ImageKind { PNG, JPEG, WEBP, GIF, BMP, OTHER }

object ImageCodec {

    /** 按文件头判断真实格式，比扩展名可靠。 */
    fun sniff(data: ByteArray): ImageKind {
        if (data.size >= 8 &&
            data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
            data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()
        ) return ImageKind.PNG

        if (data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) return ImageKind.JPEG

        if (data.size >= 12 &&
            data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
            data[2] == 0x46.toByte() && data[3] == 0x46.toByte() &&
            data[8] == 0x57.toByte() && data[9] == 0x45.toByte() &&
            data[10] == 0x42.toByte() && data[11] == 0x50.toByte()
        ) return ImageKind.WEBP

        if (data.size >= 3 && data[0] == 0x47.toByte() && data[1] == 0x49.toByte() && data[2] == 0x46.toByte()) return ImageKind.GIF
        if (data.size >= 2 && data[0] == 0x42.toByte() && data[1] == 0x4D.toByte()) return ImageKind.BMP

        return ImageKind.OTHER
    }

    private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")

    /** HEIF/HEIC/AVIF：ftyp 盒 + 品牌标识。 */
    fun isHeif(data: ByteArray): Boolean {
        if (data.size < 12) return false
        val isFtyp = data[4] == 0x66.toByte() && data[5] == 0x74.toByte() &&
            data[6] == 0x79.toByte() && data[7] == 0x70.toByte()
        if (!isFtyp) return false
        val brand = String(data, 8, 4, Charsets.US_ASCII)
        return brand in HEIF_BRANDS
    }

    /** 卡面文件可能没有后缀名，这里按内容判断是否为图片。 */
    fun isImage(data: ByteArray): Boolean = sniff(data) != ImageKind.OTHER || isHeif(data)

    fun bounds(data: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    /** 按目标尺寸做采样解码，避免大图 OOM。 */
    fun decodeScaled(data: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, probe)
        val width = probe.outWidth
        val height = probe.outHeight
        if (width <= 0 || height <= 0) return null

        var sample = 1
        if (reqWidth > 0 && reqHeight > 0) {
            while (width / (sample * 2) >= reqWidth && height / (sample * 2) >= reqHeight) {
                sample *= 2
            }
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
        } catch (_: Throwable) {
            null
        }
    }

    /** 等比缩放 + 居中裁剪（cover），保证不拉伸变形且尺寸与原卡面一致。 */
    fun fitCover(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (targetWidth <= 0 || targetHeight <= 0) return source
        if (source.width == targetWidth && source.height == targetHeight) return source

        val scale = maxOf(targetWidth.toFloat() / source.width, targetHeight.toFloat() / source.height)
        val scaledWidth = (source.width * scale + 0.5f).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale + 0.5f).toInt().coerceAtLeast(1)

        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val offsetX = ((scaledWidth - targetWidth) / 2).coerceAtLeast(0)
        val offsetY = ((scaledHeight - targetHeight) / 2).coerceAtLeast(0)

        val out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(scaled, -offsetX.toFloat(), -offsetY.toFloat(), null)

        if (scaled !== source) scaled.recycle()
        return out
    }

    private fun flattenToWhite(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)
        return out
    }

    @Suppress("DEPRECATION")
    fun encode(bitmap: Bitmap, kind: ImageKind): ByteArray {
        val out = ByteArrayOutputStream()
        when (kind) {
            ImageKind.JPEG -> {
                val flat = if (bitmap.hasAlpha()) flattenToWhite(bitmap) else bitmap
                flat.compress(Bitmap.CompressFormat.JPEG, 95, out)
                if (flat !== bitmap) flat.recycle()
            }

            ImageKind.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)

            ImageKind.WEBP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 95, out)
                }
            }

            else -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return out.toByteArray()
    }

    /**
     * 生成用于替换原卡面的字节流：尺寸对齐原图、格式对齐原图。
     * 返回 (数据, 提示信息)
     */
    fun buildReplacement(originalBytes: ByteArray, replacement: Bitmap): Pair<ByteArray, String?> {
        val kind = sniff(originalBytes)
        val targetBounds = bounds(originalBytes)

        val working = if (targetBounds != null) {
            fitCover(replacement, targetBounds.first, targetBounds.second)
        } else {
            replacement
        }

        val data = encode(working, kind)
        if (working !== replacement) working.recycle()

        var warning: String? = null
        if (kind == ImageKind.GIF || kind == ImageKind.BMP) {
            warning = "原文件是 ${kind.name} 格式，无法按原格式编码，已写入 PNG 数据（文件名保持不变，多数图片库按内容解码）。"
        } else if (kind == ImageKind.OTHER) {
            warning = "原文件格式未识别（可能是 HEIF/AVIF），已写入 PNG 数据（文件名保持不变，若钱包应用按格式解码可能无法显示，可用备份复原）。"
        }
        if (targetBounds == null) {
            val extra = "未能解析原图尺寸，已按所选图片的原始尺寸写入。"
            warning = if (warning == null) extra else "$warning $extra"
        }
        return data to warning
    }
}
