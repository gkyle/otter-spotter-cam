package com.kylescholz.otterspotter

import android.net.Uri

data class OtterPhoto(
    val uri: Uri,
    val name: String,
    val dateTaken: Long,
    val size: Long,
    val filePath: String?,
    val isAuto: Boolean
)
