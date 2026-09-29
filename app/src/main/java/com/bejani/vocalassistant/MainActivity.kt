package com.bejani.vocalassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tv = TextView(this).apply {
            text = "دستیار صوتی فعال است.\n\nبگویید: سلام یولداش\n\n" +
                   "اگر سرویس قطع شد، برنامه را یک بار دیگر باز کنید."
            textSize = 18f
            gravity = Gravity.CENTER
        }
        setContentView(tv)

        requestRuntimePermissions()
        startServiceSafe()
    }

    private fun requestRuntimePermissions() {
        try {
            val missing = requiredPermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "requestPermissions failed", e)
        }
    }

    override fun onResume() {
        super.onResume()
        // هر بار برگشت به برنامه، سرویس دوباره بررسی/استارت شود
        startServiceSafe()
    }

    private fun startServiceSafe() {
        try {
            val intent = Intent(this, VoiceAssistantService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "startService failed", e)
        }
    }
}
