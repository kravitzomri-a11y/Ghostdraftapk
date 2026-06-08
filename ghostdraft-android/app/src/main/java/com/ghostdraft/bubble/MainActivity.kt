package com.ghostdraft.bubble

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.ghostdraft.bubble.databinding.ActivityMainBinding

/**
 * The setup hub. Walks through the three grants the bubble needs, then lets
 * you toggle the floating mic on and off. Re-checks state every time the
 * screen resumes (e.g. after returning from a system settings screen).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnMic.setOnClickListener {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        b.btnOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        b.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        b.btnToggle.setOnClickListener {
            if (Permissions.allReady(this)) BubbleService.start(this)
            else refresh()
        }
        b.btnStop.setOnClickListener { BubbleService.stop(this) }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        refresh()
    }

    private fun refresh() {
        val mic = Permissions.hasMic(this)
        val overlay = Permissions.hasOverlay(this)
        val a11y = Permissions.hasAccessibility()

        b.btnMic.text = step("1. Microphone", mic)
        b.btnMic.isEnabled = !mic

        b.btnOverlay.text = step("2. Draw over apps", overlay)
        b.btnOverlay.isEnabled = !overlay

        b.btnAccessibility.text = step("3. Accessibility", a11y)
        b.btnAccessibility.isEnabled = !a11y

        val ready = mic && overlay && a11y
        b.btnToggle.isEnabled = ready
        b.status.text = if (ready)
            "All set. Start the bubble, then tap it anywhere to dictate."
        else
            "Grant the three permissions above to enable the floating mic."
    }

    private fun step(label: String, done: Boolean) =
        if (done) "✓ $label — granted" else "$label — tap to grant"
}
