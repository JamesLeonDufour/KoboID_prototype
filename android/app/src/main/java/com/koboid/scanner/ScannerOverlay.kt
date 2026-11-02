package com.koboid.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class ScannerOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99111317")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.frameIdle)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.frameIdle)
        style = Paint.Style.STROKE
        strokeWidth = 7f
    }

    private val frameRect = RectF()
    private var aligned = false
    private var aspectRatio = CardFormat.ID_CARD.aspectRatio

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setAligned(aligned: Boolean) {
        if (this.aligned == aligned) return
        this.aligned = aligned
        updateFrameColor()
        invalidate()
    }

    fun setFormat(format: CardFormat) {
        if (this.aspectRatio == format.aspectRatio) return
        this.aspectRatio = format.aspectRatio
        aligned = false
        updateFrameColor()
        invalidate()
    }

    private fun updateFrameColor() {
        val color = context.getColor(if (aligned) R.color.frameReady else R.color.frameIdle)
        strokePaint.color = color
        cornerPaint.color = color
    }

    /** Exposes the current frame rect so CameraActivity can use the same geometry for alignment. */
    fun getFrameRect(viewWidth: Int, viewHeight: Int): RectF {
        val fw: Float
        val fh: Float
        if (aspectRatio >= 1f) {
            // landscape frame (ID card, passport)
            fw = viewWidth * 0.9f
            fh = fw / aspectRatio
        } else {
            // portrait frame (A4 document)
            fh = viewHeight * 0.9f
            fw = fh * aspectRatio
        }
        val left = (viewWidth - fw) / 2f
        val top = (viewHeight - fh) / 2f
        return RectF(left, top, left + fw, top + fh)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = getFrameRect(width, height)
        frameRect.set(rect)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        canvas.drawRect(frameRect, borderPaint)
        canvas.drawRect(frameRect, strokePaint)
        drawCornerMarkers(canvas, frameRect)
    }

    private fun drawCornerMarkers(canvas: Canvas, rect: RectF) {
        val marker = minOf(rect.width(), rect.height()) * 0.08f

        canvas.drawLine(rect.left, rect.top, rect.left + marker, rect.top, cornerPaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + marker, cornerPaint)

        canvas.drawLine(rect.right, rect.top, rect.right - marker, rect.top, cornerPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + marker, cornerPaint)

        canvas.drawLine(rect.left, rect.bottom, rect.left + marker, rect.bottom, cornerPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - marker, cornerPaint)

        canvas.drawLine(rect.right, rect.bottom, rect.right - marker, rect.bottom, cornerPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - marker, cornerPaint)
    }
}
