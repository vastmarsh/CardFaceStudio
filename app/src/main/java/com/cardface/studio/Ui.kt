package com.cardface.studio

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import java.util.Locale

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

fun Context.toastLong(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "未知"
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
}

/** 不可取消的进度对话框。 */
fun showProgress(context: Context, title: String, message: String): AlertDialog {
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(context.dp(24), context.dp(20), context.dp(24), context.dp(16))
    }

    val bar = ProgressBar(context).apply { isIndeterminate = true }
    container.addView(bar, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))

    val text = TextView(context).apply {
        this.text = message
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        setPadding(0, context.dp(12), 0, 0)
    }
    container.addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    return AlertDialog.Builder(context)
        .setTitle(title)
        .setView(container)
        .setCancelable(false)
        .create()
}
