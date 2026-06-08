package com.ghostdraft.bubble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Centralizes the three grants the floating bubble depends on. */
object Permissions {

    fun hasMic(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun hasOverlay(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun hasAccessibility(): Boolean =
        GhostDraftAccessibilityService.isEnabled

    fun allReady(context: Context): Boolean =
        hasMic(context) && hasOverlay(context) && hasAccessibility()
}
