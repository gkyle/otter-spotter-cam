package com.kylescholz.otterspotter

import android.graphics.RectF

data class Detection(
    val classId: Int,
    val className: String,
    val confidence: Float,
    val box: RectF // [left, top, right, bottom] in un-letterboxed original frame coordinates
)
