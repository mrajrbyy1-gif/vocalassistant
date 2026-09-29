package com.bejani.vocalassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var downloadButton: Button
    private lateinit var testVoiceButton: Button
    private lateinit var startAssistantButton: Button

    private var offlineTts: OfflinePersianTts? = null

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
        buildUi()
        offlineTts = OfflinePersianTts(this)

        requestRuntimePermissions()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val title = TextView(this).apply {
            text = "دستیار صوتی"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)

        statusText = TextView(this).apply {
            text = ""
            textSize = 15f
            setPadding(0, 0, 0, 32)
        }
        root.addView(statusText)

        downloadButton = Button(this).apply {
            text = "📥 دانلود مدل صدای فارسی"
            setOnClickListener { downloadVoiceModel() }
        }
        root.addView(downloadButton, buttonParams())

        testVoiceButton = Button(this).apply {
            text = "🔊 تست صدای فارسی"
            setOnClickListener { testVoice() }
        }
        root.addView(testVoiceButton, buttonParams())

        startAssistantButton = Button(this).apply {
            text = "▶️ شروع دستیار (روشن کردن میکروفون)"
            setOnClickListener { startAssistant() }
        }
        root.addView(startAssistantButton, buttonParams())

        val helpText = TextView(this).apply {
            text = "\nدستورات نمونه:\n" +
                   "• سلام یولداش\n" +
                   "• ساعت چنده\n" +
                   "• تاریخ چنده\n" +
                   "• شارژ باتری چقدره\n" +
                   "• واتساپ رو باز کن\n" +
                   "• تماس بگیر با [نام مخاطب]\n\n" +
                   "برای خواندن اعلان‌ها، دسترسی اعلان را در تنظیمات فعال کنید."
            textSize = 13f
            setPadding(0, 32, 0, 0)
        }
        root.addView(helpText)

        val scroll = ScrollView(this).apply {
            addView(root)
        }
        setContentView(scroll)
    }

    private fun buttonParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 12, 0, 12) }

    // ---------------- Status ----------------

    private fun updateStatus() {
        val tts = offlineTts ?: return
        val installed = tts.isInstalled()
        val modelFile = java.io.File(filesDir, "tts/fas/model.onnx")
        val tokensFile = java.io.File(filesDir, "tts/fas/tokens.txt")
        val modelSize = if (modelFile.exists()) modelFile.length() / (1024 * 1024) else 0
        val tokensSize = if (tokensFile.exists()) tokensFile.length() / 1024 else 0

        statusText.text = buildString {
            append("وضعیت مدل صدا:\n")
            append(if (installed) "✅ مدل نصب شده\n" else "❌ مدل نصب نشده\n")
            append("model.onnx: $modelSize مگابایت\n")
            append("tokens.txt: $tokensSize کیلوبایت\n\n")
            append("وضعیت مجوزها:\n")
            append("🎤 میکروفون: ${permStr(Manifest.permission.RECORD_AUDIO)}\n")
            append("📞 تماس: ${permStr(Manifest.permission.CALL_PHONE)}\n")
            append("👥 مخاطبین: ${permStr(Manifest.permission.READ_CONTACTS)}")
        }
    }

    private fun permStr(perm: String): String =
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED)
            "✅ داده شده" else "❌ داده نشده"

    // ---------------- Actions ----------------

    private fun downloadVoiceModel() {
        val tts = offlineTts ?: return
        if (tts.isInstalled()) {
            statusText.text = "مدل صدا قبلاً نصب شده است ✅"
            return
        }
        downloadButton.isEnabled = false
        testVoiceButton.isEnabled = false
        statusText.text = "شروع دانلود مدل صدا...\n(حدود ۱۰۰ مگابایت، بسته به اینترنت ممکن است چند دقیقه طول بکشد)"

        Thread {
            try {
                tts.prepare { progress ->
                    runOnUiThread {
                        statusText.text = "در حال دانلود... $progress٪"
                    }
                }
                runOnUiThread {
                    statusText.text = "✅ مدل صدا با موفقیت نصب شد."
                    downloadButton.isEnabled = true
                    testVoiceButton.isEnabled = true
                    updateStatus()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "download failed", e)
                runOnUiThread {
                    statusText.text = "❌ خطا در دانلود:\n${e.message}\n\n" +
                            "اینترنت را چک کنید و دوباره تلاش کنید."
                    downloadButton.isEnabled = true
                    testVoiceButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun testVoice() {
        val tts = offlineTts ?: return
        if (!tts.isInstalled()) {
            statusText.text = "اول مدل صدا را دانلود کنید."
            return
        }
        testVoiceButton.isEnabled = false
        statusText.text = "🔊 در حال پخش تست صدا..."
        Thread {
            try {
                tts.prepare()
                tts.speak("سلام، من دستیار صوتی هستم. الان ساعت هشت و سی دقیقه است.")
                runOnUiThread {
                    statusText.text = "✅ تست صدا انجام شد."
                    testVoiceButton.isEnabled = true
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "test voice failed", e)
                runOnUiThread {
                    statusText.text = "❌ خطا در پخش صدا:\n${e.message}"
                    testVoiceButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun startAssistant() {
        try {
            val intent = Intent(this, VoiceAssistantService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            statusText.text = "✅ دستیار روشن شد.\n\nحالا بگویید: «سلام یولداش»\n\n" +
                    "اگر میکروفون کار نکرد، اپ را در Recent Apps قفل کنید و Autostart را روشن کنید."
        } catch (e: Exception) {
            statusText.text = "❌ خطا در شروع سرویس:\n${e.message}"
        }
    }

    // ---------------- Permissions ----------------

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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateStatus()
    }
}
