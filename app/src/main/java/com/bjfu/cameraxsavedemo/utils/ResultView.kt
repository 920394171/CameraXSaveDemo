package com.bjfu.cameraxsavedemo.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import java.util.*


class ResultView : View {
    private var mPaintRectangle: Paint? = null
    private var mPaintText: Paint? = null
    private var mResults: ArrayList<Result>? = null
    var mPersonPixArea: Double = 0.0
    private val TAG = "TTZZ"
    var hasPersonClass = false

    constructor(context: Context?) : super(context) {}
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        mPaintRectangle = Paint()
        mPaintRectangle!!.color = Color.YELLOW
        mPaintText = Paint()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mResults == null) return
        mPersonPixArea = 0.0
        for (result in mResults!!) {
            mPaintRectangle!!.strokeWidth = 5f
            mPaintRectangle!!.style = Paint.Style.STROKE
            canvas.drawRect(result.rect, mPaintRectangle!!)
            val mPath = Path()
            val mRectF = RectF(
                result.rect.left.toFloat(),
                result.rect.top.toFloat(),
                result.rect.left.toFloat() + TEXT_WIDTH,
                result.rect.top.toFloat() + TEXT_HEIGHT
            )
            mPath.addRect(mRectF, Path.Direction.CW)
            mPaintText!!.color = Color.MAGENTA
            canvas.drawPath(mPath, mPaintText!!)
            mPaintText!!.color = Color.WHITE
            mPaintText!!.strokeWidth = 0f
            mPaintText!!.style = Paint.Style.FILL
            mPaintText!!.textSize = 32f
            canvas.drawText(
//                String.format("%s %.2f", PrePostProcessor.mClasses[result.classIndex], result.score),
                String.format("%s", PrePostProcessor.mClasses[result.classIndex]),
                (result.rect.left + TEXT_X).toFloat(),
                (result.rect.top + TEXT_Y).toFloat(),
                mPaintText!!
            )

            if (PrePostProcessor.mClasses[result.classIndex] == "face" && !hasPersonClass) {
                hasPersonClass = true
                mPersonPixArea = (result.rect.width() * result.rect.height()).toDouble()
                Log.e(TAG, "onDraw: width and height: ${result.rect.width()}--------${result.rect.height()}")
            } else if (hasPersonClass && (result.rect.width() * result.rect.height()).toDouble() > mPersonPixArea) {
                mPersonPixArea = (result.rect.width() * result.rect.height()).toDouble()
            }
        }
        if(!hasPersonClass)
            mPersonPixArea = 0.0
    }

    fun setResults(results: ArrayList<Result>?) {
        mResults = results
    }

    companion object {
        private const val TEXT_X = 40
        private const val TEXT_Y = 35
        private const val TEXT_WIDTH = 125
        private const val TEXT_HEIGHT = 50
    }
}