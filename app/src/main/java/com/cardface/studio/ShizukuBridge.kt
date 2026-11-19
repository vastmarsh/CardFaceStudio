package com.cardface.studio

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku 授权状态的薄封装：所有调用都做了异常兜底，
 * 避免 Shizuku 未安装 / 服务未启动时直接崩溃。
 */
object ShizukuBridge {

    fun binderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun granted(): Boolean = try {
        binderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun request(requestCode: Int): Boolean = try {
        if (!binderAlive()) {
            false
        } else {
            Shizuku.requestPermission(requestCode)
            true
        }
    } catch (_: Throwable) {
        false
    }

    fun addPermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.addRequestPermissionResultListener(listener)
        } catch (_: Throwable) {
        }
    }

    fun removePermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.removeRequestPermissionResultListener(listener)
        } catch (_: Throwable) {
        }
    }

    fun addBinderListener(listener: Shizuku.OnBinderReceivedListener) {
        try {
            Shizuku.addBinderReceivedListenerSticky(listener)
        } catch (_: Throwable) {
        }
    }

    fun removeBinderListener(listener: Shizuku.OnBinderReceivedListener) {
        try {
            Shizuku.removeBinderReceivedListener(listener)
        } catch (_: Throwable) {
        }
    }
}
