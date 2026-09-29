package com.bejani.vocalassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.provider.ContactsContract
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : ComponentActivity() {
    private val contactPicker = 1001
    private val permissionRequest = 1002

    private lateinit var selectedContact: TextView
    private lateinit var statusText: TextView
    private lateinit var downloadButton: Button
    private lateinit var testVoiceButton: Button
    private lateinit var offlineTts: OfflinePersianTts
    private var ttsPreparing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Vocal Assistant"

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F1115"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        root.addView(container)

        // --- عنوان ---
        container.addView(makeTitle("دستیار صوتی فارسی"))

        // --- کارت وضعیت ---
        val statusCard = makeCard()
        statusCard.addView(makeSectionLabel("وضعیت مدل صدا"))
        statusText = TextView(this).apply {
            text = "در حال بررسی…"
            setTextColor(Color.parseColor("#C9D1D9"))
            textSize = 14f
            setLineSpacing(0f, 1.3f)
        }
        statusCard.addView(statusText)
        container.addView(statusCard)

        // --- کارت مخاطب ---
        val contactCard = makeCard()
        contactCard.addView(makeSectionLabel("مخاطب پیش‌فرض تماس"))
        selectedContact = TextView(this).apply {
            text = "انتخاب نشده"
            setTextColor(Color.parseColor("#C9D1D9"))
            textSize = 15f
            setPadding(0, dp(4), 0, dp(12))
        }
        contactCard.addView(selectedContact)
        contactCard.addView(makeButton("📇 انتخاب مخاطب", ButtonStyle.SECONDARY) {
            startActivityForResult(
                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                contactPicker
            )
        })
        container.addView(contactCard)

        // --- کارت صدا ---
        val voiceCard = makeCard()
        voiceCard.addView(makeSectionLabel("مدل صوتی آفلاین"))
        downloadButton = makeButton("📥 دانلود مدل صدای فارسی", ButtonStyle.PRIMARY) {
            startDownload()
        }
        voiceCard.addView(downloadButton)

        testVoiceButton = makeButton("🔊 آزمایش صدا", ButtonStyle.SECONDARY) {
            testVoice()
        }
        voiceCard.addView(testVoiceButton)

        voiceCard.addView(makeButton("⚙️ تنظیمات TTS گوشی", ButtonStyle.GHOST) {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: Exception) {
                Toast.makeText(this, "تنظیمات TTS پیدا نشد", Toast.LENGTH_SHORT).show()
            }
        })
        container.addView(voiceCard)

        // --- کارت دستیار ---
        val assistantCard = makeCard()
        assistantCard.addView(makeSectionLabel("دستیار صوتی"))
        assistantCard.addView(makeButton("▶️ شروع دستیار", ButtonStyle.SUCCESS) {
            startAssistant()
        })
        assistantCard.addView(makeButton("⏹ توقف دستیار", ButtonStyle.DANGER) {
            stopService(Intent(this@MainActivity, VoiceAssistantService::class.java))
            Toast.makeText(this, "دستیار متوقف شد", Toast.LENGTH_SHORT).show()
        })
        container.addView(assistantCard)

        // --- راهنما ---
        val helpCard = makeCard()
        helpCard.addView(makeSectionLabel("راهنمای دستورات"))
        helpCard.addView(makeHelpText())
        container.addView(helpCard)

        setContentView(root)

        offlineTts = OfflinePersianTts(this)

        refreshContactLabel()
        updateStatus()
        requestPermissionsIfNeeded()
    }

    // ---------------- UI helpers ----------------

    private enum class ButtonStyle { PRIMARY, SECONDARY, SUCCESS, DANGER, GHOST }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun makeTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#E6EDF3"))
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dp(24))
    }

    private fun makeSectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#8B949E"))
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(12))
    }

    private fun makeCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.parseColor("#161B22"))
        setPadding(dp(16), dp(16), dp(16), dp(16))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(16)) }
        layoutParams = params
    }

    private fun makeButton(
        text: String,
        style: ButtonStyle,
        onClick: () -> Unit
    ): Button = Button(this).apply {
        this.text = text
        textSize = 15f
        isAllCaps = false
        setTextColor(Color.parseColor("#FFFFFF"))
        val bg = when (style) {
            ButtonStyle.PRIMARY -> "#1F6FEB"
            ButtonStyle.SECONDARY -> "#30363D"
            ButtonStyle.SUCCESS -> "#238636"
            ButtonStyle.DANGER -> "#DA3633"
            ButtonStyle.GHOST -> "#21262D"
        }
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor(bg))
            cornerRadius = dp(10).toFloat()
        }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(4), 0, dp(4)) }
        layoutParams = params
        setOnClickListener { onClick() }
    }

    private fun makeHelpText(): TextView = TextView(this).apply {
        text = """
            برای فعال شدن بگویید:
              «سلام یولداش»

            دستورات نمونه:
              • ساعت چنده
              • تاریخ چنده
              • شارژ باتری چقدره
              • واتساپ رو باز کن
              • تماس بگیر با [نام مخاطب]

            برای خواندن اعلان‌ها، دسترسی اعلان را از
            تنظیمات گوشی فعال کنید:
            حریم خصوصی → مجوزهای ویژه → دسترسی اعلان
        """.trimIndent()
        setTextColor(Color.parseColor("#8B949E"))
        textSize = 13f
        setLineSpacing(dp(4).toFloat(), 1.4f)
    }

    // ---------------- Status ----------------

    private fun isModelInstalled(): Boolean {
        val m = File(filesDir, "tts/fas/model.onnx")
        val t = File(filesDir, "tts/fas/tokens.txt")
        return m.length() > 1_000_000 && t.length() > 10
    }

    private fun updateStatus() {
        val modelFile = File(filesDir, "tts/fas/model.onnx")
        val tokensFile = File(filesDir, "tts/fas/tokens.txt")
        val modelMb = modelFile.length() / (1024 * 1024)
        val tokensKb = tokensFile.length() / 1024
        val installed = isModelInstalled()

        statusText.text = buildString {
            append(if (installed) "✅  مدل نصب شده است\n" else "❌  مدل نصب نشده است\n")
            append("model.onnx :  $modelMb مگابایت\n")
            append("tokens.txt :  $tokensKb کیلوبایت")
        }
        downloadButton.isEnabled = !ttsPreparing && !installed
        testVoiceButton.isEnabled = !ttsPreparing
    }

    // ---------------- Actions ----------------

    private fun startDownload() {
        if (isModelInstalled()) {
            Toast.makeText(this, "مدل قبلاً نصب شده ✅", Toast.LENGTH_SHORT).show()
            return
        }
        if (ttsPreparing) {
            Toast.makeText(this, "دانلود در جریان است…", Toast.LENGTH_SHORT).show()
            return
        }
        ttsPreparing = true
        downloadButton.isEnabled = false
        statusText.text = "⏳  شروع دانلود…\nبسته به اینترنت چند دقیقه طول می‌کشد."

        Thread {
            try {
                offlineTts.prepare { progress ->
                    runOnUiThread {
                        statusText.text = "⏳  در حال دانلود مدل صدا…\n$progress ٪"
                    }
                }
                runOnUiThread {
                    statusText.text = "✅  مدل با موفقیت دانلود شد"
                    ttsPreparing = false
                    updateStatus()
                    Toast.makeText(this, "دانلود کامل شد ✅", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                Log.e("VocalAssistantTTS", "download failed", error)
                runOnUiThread {
                    statusText.text = "❌  خطای دانلود:\n${error.message}"
                    ttsPreparing = false
                    downloadButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun testVoice() {
        if (ttsPreparing) {
            Toast.makeText(this, "لطفاً صبر کنید…", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isModelInstalled()) {
            Toast.makeText(this, "اول مدل را دانلود کنید", Toast.LENGTH_SHORT).show()
            return
        }
        ttsPreparing = true
        testVoiceButton.isEnabled = false
        Toast.makeText(this, "در حال آماده‌سازی…", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                offlineTts.prepare()
                offlineTts.speak("این یک صدای آزمایشی از دستیار صوتی است")
                runOnUiThread {
                    Toast.makeText(this, "صدای آزمایشی پخش شد ✅", Toast.LENGTH_SHORT).show()
                    ttsPreparing = false
                    testVoiceButton.isEnabled = true
                }
            } catch (error: Exception) {
                Log.e("VocalAssistantTTS", "Offline TTS failed", error)
                runOnUiThread {
                    Toast.makeText(this, "خطا: ${error.message}", Toast.LENGTH_LONG).show()
                    ttsPreparing = false
                    testVoiceButton.isEnabled = true
                }
            }
        }.start()
    }

    // ---------------- Permissions ----------------

    private fun requestPermissionsIfNeeded() {
        val required = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionRequest)
        }
    }

    private fun startAssistant() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded()
            return
        }
        try {
            ContextCompat.startForegroundService(
                this, Intent(this, VoiceAssistantService::class.java)
            )
            Toast.makeText(this, "دستیار فعال شد: سلام یولداش", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "خطا: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- Lifecycle ----------------

    override fun onResume() {
        super.onResume()
        refreshContactLabel()
        updateStatus()
    }

    override fun onDestroy() {
        try { offlineTts.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != contactPicker || resultCode != RESULT_OK || data?.data == null) return
        val uri: Uri = data.data!!
        contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0) ?: "مخاطب"
                val phone = cursor.getString(1) ?: return
                getSharedPreferences("assistant", MODE_PRIVATE).edit()
                    .putString("name", name).putString("phone", phone).apply()
                refreshContactLabel()
            }
        }
    }

    private fun refreshContactLabel() {
        val prefs = getSharedPreferences("assistant", MODE_PRIVATE)
        val name = prefs.getString("name", null)
        selectedContact.text = if (name == null) "انتخاب نشده" else name
    }
}
