package com.example.receiverapp

import android.os.Handler
import android.os.Looper

object AppLogger {
    var listener: ((String) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())

    fun log(message: String) {
        handler.post {
            listener?.invoke(message)
        }
    }
}
