package com.koboid.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Transparent overlay view placed on top of an ImageView.
 *
 * Users can draw new rectangles, tap existing ones to select them, move them,
 * and resize a selected region by dragging one of its corner handles.
 * Coordinates are stored normalised (0.0-1.0) relative to image content.
 */
class RegionDrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class DrawnRegion(val label: String, val normRect: RectF, val color: Int)
    private enum class ResizeHandle { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private val palette = listOf(
        Color.parseColor("#2094F3"),
        Color.parseColor("#53C5D0"),
        Color.parseColor("#005A9E"),
        Color.parseColor("#7486B9"),
        Color.parseColor("#66C2FF"),
        Color.parseColor("#565E76")
    )

    private val regions = mutableListOf<DrawnRegion>()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 48
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val selectedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#53C5D0")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#53C5D0")
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    private val inProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#53C5D0")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private var imageBounds = RectF()

    private var drawStartX = 0f
    private var drawStartY = 0f
    private val inProgressRect = RectF()
    private var isDrawing = false

    private var activeRegionIndex = -1
    private var selectedRegionIndex = -1
    private var isMovingRegion = false
    private var activeResizeHandle = ResizeHandle.NONE
    private val moveStartRect = RectF()
    private var moveStartTouchX = 0f
    private var moveStartTouchY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val minRegionSizePx = 40f
    private val resizeHandleRadiusPx = 24f
    private val resizeHandleVisualSizePx = 12f

    var onRegionComplete: ((normRect: RectF) -> Unit)? = null
    var onRegionSelected: ((index: Int) -> Unit)? = null
    var onRegionMoved: ((index: Int, normRect: RectF) -> Unit)? = null

    fun setImageBounds(bounds: RectF) {
        imageBounds = bounds
    }

    fun setRegions(items: List<Pair<String, RectF>>) {
        regions.clear()
        items.forEachIndexed { index, (label, normRect) ->
            regions += DrawnRegion(label, RectF(normRect), palette[index % palette.size])
        }
        if (selectedRegionIndex !in regions.indices) {
            selectedRegionIndex = -1
        }
        invalidate()
    }

    fun setSelectedRegion(index: Int) {
        selectedRegionIndex = if (index in regions.indices) index else -1
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageBounds.isEmpty) return false

        val x = event.x.coerceIn(imageBounds.left, imageBounds.right)
        val y = event.y.coerceIn(imageBounds.top, imageBounds.bottom)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeRegionIndex = findRegionAt(x, y)
                if (activeRegionIndex >= 0) {
                    selectedRegionIndex = activeRegionIndex
                    onRegionSelected?.invoke(activeRegionIndex)
                    moveStartTouchX = x
                    moveStartTouchY = y
                    moveStartRect.set(regions[activeRegionIndex].normRect)
                    isMovingRegion = false
                    activeResizeHandle = resolveResizeHandle(activeRegionIndex, x, y)
                    isDrawing = false
                } else {
                    selectedRegionIndex = -1
                    drawStartX = x
                    drawStartY = y
                    isDrawing = true
                    activeResizeHandle = ResizeHandle.NONE
                    inProgressRect.set(x, y, x, y)
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeRegionIndex >= 0) {
                    if (activeResizeHandle != ResizeHandle.NONE) {
                        resizeRegion(activeRegionIndex, activeResizeHandle, x, y)
                    } else {
                        if (!isMovingRegion) {
                            val dx = x - moveStartTouchX
                            val dy = y - moveStartTouchY
                            if (hypot(dx.toDouble(), dy.toDouble()) >= touchSlop) {
                                isMovingRegion = true
                            }
                        }
                        if (isMovingRegion) {
                            moveRegion(activeRegionIndex, x - moveStartTouchX, y - moveStartTouchY)
                        }
                    }
                } else if (isDrawing) {
                    updateInProgress(x, y)
                }
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                if (activeRegionIndex >= 0) {
                    if (!isMovingRegion && activeResizeHandle == ResizeHandle.NONE) {
                        onRegionSelected?.invoke(activeRegionIndex)
                    }
                    activeRegionIndex = -1
                    isMovingRegion = false
                    activeResizeHandle = ResizeHandle.NONE
                } else if (isDrawing) {
                    isDrawing = false
                    updateInProgress(x, y)
                    if (inProgressRect.width() > minRegionSizePx && inProgressRect.height() > minRegionSizePx) {
                        onRegionComplete?.invoke(toNorm(inProgressRect))
                    }
                    inProgressRect.setEmpty()
                }
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                activeRegionIndex = -1
                isMovingRegion = false
                activeResizeHandle = ResizeHandle.NONE
                isDrawing = false
                inProgressRect.setEmpty()
                invalidate()
            }
        }
        return true
    }

    private fun updateInProgress(x: Float, y: Float) {
        inProgressRect.set(
            minOf(drawStartX, x),
            minOf(drawStartY, y),
            maxOf(drawStartX, x),
            maxOf(drawStartY, y)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        regions.forEach { region ->
            val px = toPixel(region.normRect)
            fillPaint.color = region.color
            canvas.drawRect(px, fillPaint)
            strokePaint.color = region.color
            canvas.drawRect(px, strokePaint)
            canvas.drawText(region.label, px.left + 8f, px.top + 36f, labelPaint)
        }

        if (selectedRegionIndex in regions.indices) {
            val selectedRect = toPixel(regions[selectedRegionIndex].normRect)
            canvas.drawRect(selectedRect, selectedStrokePaint)
            drawResizeHandles(canvas, selectedRect)
        }

        if (isDrawing && !inProgressRect.isEmpty) {
            canvas.drawRect(inProgressRect, inProgressPaint)
        }
    }

    private fun drawResizeHandles(canvas: Canvas, rect: RectF) {
        val half = resizeHandleVisualSizePx / 2f
        val corners = listOf(
            rect.left to rect.top,
            rect.right to rect.top,
            rect.left to rect.bottom,
            rect.right to rect.bottom
        )
        corners.forEach { (cx, cy) ->
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, handlePaint)
        }
    }

    private fun findRegionAt(x: Float, y: Float): Int {
        for (index in regions.indices.reversed()) {
            if (toPixel(regions[index].normRect).contains(x, y)) {
                return index
            }
        }
        return -1
    }

    private fun resolveResizeHandle(index: Int, x: Float, y: Float): ResizeHandle {
        if (index !in regions.indices) return ResizeHandle.NONE
        val rect = toPixel(regions[index].normRect)
        if (distance(x, y, rect.left, rect.top) <= resizeHandleRadiusPx) return ResizeHandle.TOP_LEFT
        if (distance(x, y, rect.right, rect.top) <= resizeHandleRadiusPx) return ResizeHandle.TOP_RIGHT
        if (distance(x, y, rect.left, rect.bottom) <= resizeHandleRadiusPx) return ResizeHandle.BOTTOM_LEFT
        if (distance(x, y, rect.right, rect.bottom) <= resizeHandleRadiusPx) return ResizeHandle.BOTTOM_RIGHT
        return ResizeHandle.NONE
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    private fun resizeRegion(index: Int, handle: ResizeHandle, touchX: Float, touchY: Float) {
        if (index !in regions.indices) return
        val rect = toPixel(regions[index].normRect)

        val clampedX = touchX.coerceIn(imageBounds.left, imageBounds.right)
        val clampedY = touchY.coerceIn(imageBounds.top, imageBounds.bottom)

        when (handle) {
            ResizeHandle.TOP_LEFT -> {
                rect.left = minOf(clampedX, rect.right - minRegionSizePx)
                rect.top = minOf(clampedY, rect.bottom - minRegionSizePx)
            }
            ResizeHandle.TOP_RIGHT -> {
                rect.right = maxOf(clampedX, rect.left + minRegionSizePx)
                rect.top = minOf(clampedY, rect.bottom - minRegionSizePx)
            }
            ResizeHandle.BOTTOM_LEFT -> {
                rect.left = minOf(clampedX, rect.right - minRegionSizePx)
                rect.bottom = maxOf(clampedY, rect.top + minRegionSizePx)
            }
            ResizeHandle.BOTTOM_RIGHT -> {
                rect.right = maxOf(clampedX, rect.left + minRegionSizePx)
                rect.bottom = maxOf(clampedY, rect.top + minRegionSizePx)
            }
            ResizeHandle.NONE -> return
        }

        rect.left = rect.left.coerceIn(imageBounds.left, imageBounds.right - minRegionSizePx)
        rect.top = rect.top.coerceIn(imageBounds.top, imageBounds.bottom - minRegionSizePx)
        rect.right = rect.right.coerceIn(imageBounds.left + minRegionSizePx, imageBounds.right)
        rect.bottom = rect.bottom.coerceIn(imageBounds.top + minRegionSizePx, imageBounds.bottom)

        val normRect = toNorm(rect)
        regions[index] = regions[index].copy(normRect = normRect)
        onRegionMoved?.invoke(index, normRect)
    }

    private fun moveRegion(index: Int, deltaX: Float, deltaY: Float) {
        if (index !in regions.indices) return

        val startPx = toPixel(moveStartRect)
        val width = startPx.width()
        val height = startPx.height()
        val moved = RectF(
            (startPx.left + deltaX).coerceIn(imageBounds.left, imageBounds.right - width),
            (startPx.top + deltaY).coerceIn(imageBounds.top, imageBounds.bottom - height),
            0f,
            0f
        )
        moved.right = moved.left + width
        moved.bottom = moved.top + height

        val normRect = toNorm(moved)
        regions[index] = regions[index].copy(normRect = normRect)
        onRegionMoved?.invoke(index, normRect)
    }

    private fun toNorm(px: RectF): RectF {
        val w = imageBounds.width().takeIf { it > 0f } ?: return RectF()
        val h = imageBounds.height().takeIf { it > 0f } ?: return RectF()
        return RectF(
            ((px.left - imageBounds.left) / w).coerceIn(0f, 1f),
            ((px.top - imageBounds.top) / h).coerceIn(0f, 1f),
            ((px.right - imageBounds.left) / w).coerceIn(0f, 1f),
            ((px.bottom - imageBounds.top) / h).coerceIn(0f, 1f)
        )
    }

    private fun toPixel(norm: RectF) = RectF(
        imageBounds.left + norm.left * imageBounds.width(),
        imageBounds.top + norm.top * imageBounds.height(),
        imageBounds.left + norm.right * imageBounds.width(),
        imageBounds.top + norm.bottom * imageBounds.height()
    )
}
