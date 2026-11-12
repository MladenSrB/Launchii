package com.oneuiclone.home.settings

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Maintainer of this fork and attribution to its upstream author. */
object Developer {
    const val HANDLE = "MladenSrB"
    const val BASED_ON = "Based on y7esdii"
    const val GITHUB = "https://github.com/MladenSrB"

    fun open(context: Context, url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }
}
