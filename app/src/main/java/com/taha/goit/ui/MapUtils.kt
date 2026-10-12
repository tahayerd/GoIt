package com.taha.goit.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.maplibre.android.geometry.LatLng

/**
 * Harita yardımcıları: otobüs ikonu üretimi (yön oku ile) ve geometri dönüşümleri.
 */
object MapUtils {

    /**
     * Yön açısına göre dönebilen otobüs ikonu üretir.
     * Kare yeşil zemin + beyaz otobüs silüeti; SymbolLayer iconRotate ile döndürülür.
     */
    fun createBusIcon(sizePx: Int = 96, color: Int = 0xFF0F9D58.toInt()): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val center = sizePx / 2f
        val radius = sizePx / 2f - 4f

        // Daire zemin
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius, bgPaint)

        // Beyaz çerçeve
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        canvas.drawCircle(center, center, radius, borderPaint)

        // Otobüs silüeti (basit dikdörtgen + tekerlekler)
        val busPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        val bodyW = sizePx * 0.46f
        val bodyH = sizePx * 0.30f
        val left = center - bodyW / 2
        val top = center - bodyH / 2 - sizePx * 0.05f
        canvas.drawRoundRect(left, top, left + bodyW, top + bodyH, 8f, 8f, busPaint)

        // Üst yön oku (otobüsün önünü gösterir; rotation ile döner)
        val arrow = Path().apply {
            moveTo(center, top - sizePx * 0.14f)
            lineTo(center - sizePx * 0.10f, top - sizePx * 0.04f)
            lineTo(center + sizePx * 0.10f, top - sizePx * 0.04f)
            close()
        }
        canvas.drawPath(arrow, busPaint)

        return bmp
    }

    fun createStopIcon(sizePx: Int = 48): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = sizePx / 2f
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF174EA6.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, center - 6f, bgPaint)
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, center - 18f, innerPaint)
        return bmp
    }

    /** Kullanıcının kendi konumu için estetik beyaz halkalı mavi nokta + gölge ikonu. */
    fun createUserIcon(sizePx: Int = 64): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = sizePx / 2f

        // 1. Dış yarı saydam hale (akıcı genişlik)
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x2A1A73E8 // ~16% opaklık
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, center - 2f, haloPaint)

        // 2. Yumuşak gölgeli beyaz dış çember
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
            setShadowLayer(4f, 0f, 2f, 0x40000000)
        }
        canvas.drawCircle(center, center, center - 12f, whitePaint)

        // 3. Merkezdeki canlı mavi nokta
        val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A73E8.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, center - 17f, bluePaint)

        return bmp
    }

    /** Sade, göz yormayan küçük durak nokta ikonu (Madde 7). */
    fun createSubtleStopIcon(sizePx: Int = 24): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = sizePx / 2f
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, center - 2f, whitePaint)
        val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1976D2.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, center - 4f, bluePaint)
        return bmp
    }

    /** Yol tarifi uç noktası pini (A/B harfli damla şekli). */
    fun createPinIcon(letter: String, color: Int, sizePx: Int = 72): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val w = sizePx.toFloat()
        val cx = w / 2f
        val r = w * 0.34f
        val cy = w * 0.36f

        // Damla gövdesi
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, r, bodyPaint)
        val tip = Path().apply {
            moveTo(cx - r * 0.72f, cy + r * 0.62f)
            lineTo(cx, w * 0.96f)
            lineTo(cx + r * 0.72f, cy + r * 0.62f)
            close()
        }
        canvas.drawPath(tip, bodyPaint)

        // Beyaz iç daire
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, r * 0.62f, innerPaint)

        // Harf
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = r * 0.9f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val metrics = textPaint.fontMetrics
        val textY = cy - (metrics.ascent + metrics.descent) / 2
        canvas.drawText(letter, cx, textY, textPaint)
        return bmp
    }

    fun latLng(lat: String?, lng: String?): LatLng? {
        val la = lat?.toDoubleOrNull() ?: return null
        val lo = lng?.toDoubleOrNull() ?: return null
        if (la == 0.0 && lo == 0.0) return null
        return LatLng(la, lo)
    }
}
