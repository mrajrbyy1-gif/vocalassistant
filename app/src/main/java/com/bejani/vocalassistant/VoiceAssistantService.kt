package com.bejani.vocalassistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ResolveInfo
import android.provider.ContactsContract
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.net.Uri
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.util.Locale

class VoiceAssistantService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private var confirmationMode = false
    private var awaitingCommand = false
    private var restarting = false
    private lateinit var offlineTts: OfflinePersianTts
    private var ttsReady = false
    private var ttsPreparing = false
    private var pendingSpeech: String? = null
    private var serviceActive = true
    private var testMode = false
    private var pendingPhone: String? = null
    private var pendingName: String? = null

    // برای خواندن اعلان و پیشنهاد خواندن متن
    private var pendingNotificationRead = false
    private var pendingNotificationText: String? = null
    private var pendingNotificationApp: String? = null
    private var pendingNotificationSender: String? = null

    private val handler = Handler(android.os.Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST_TTS -> {
                testMode = true
                serviceActive = false
                recognizer?.cancel()
                speak("این یک صدای آزمایشی از دستیار صوتی است")
            }
            ACTION_ANNOUNCE_NOTIFICATION -> {
                val app = intent.getStringExtra(EXTRA_NOTIF_APP) ?: "یک برنامه"
                val sender = intent.getStringExtra(EXTRA_NOTIF_SENDER)
                val body = intent.getStringExtra(EXTRA_NOTIF_TEXT) ?: ""
                announceNotification(app, sender, body)
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        offlineTts = OfflinePersianTts(this)
        startForeground(10, notification("در انتظار سلام یولداش"))
        startListening()
    }

    // ---------------- Speech recognition ----------------

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("سرویس تشخیص گفتار روی این گوشی در دسترس نیست")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.joinToString(" ")?.lowercase(Locale("fa", "IR")) ?: ""
                    android.util.Log.d("VocalAssistantSTT", "final text=$text")
                    if (!testMode && text.isNotBlank()) updateNotification("شنیده شد: ${text.take(70)}")
                    if (!testMode) handleText(text)
                    if (!testMode) scheduleRestart()
                }

                override fun onError(error: Int) {
                    android.util.Log.w("VocalAssistantSTT", "recognizer error=$error")
                    if (!testMode) {
                        updateNotification("در حال شنیدن فرمان…")
                        scheduleRestart()
                    }
                }

                override fun onReadyForSpeech(params: Bundle?) {
                    if (!testMode) updateNotification("در حال شنیدن فرمان…")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.joinToString(" ")?.lowercase(Locale("fa", "IR")) ?: ""
                    if (!testMode && text.isNotBlank()) {
                        updateNotification("در حال تشخیص: ${text.take(60)}")
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
        }
        recognizer?.startListening(intent)
    }

    private fun scheduleRestart() {
        if (testMode) return
        if (restarting) return
        restarting = true
        handler.postDelayed({
            restarting = false
            if (serviceActive) startListening()
        }, 400)
    }

    // ---------------- Wake phrase ----------------

    private fun isWakePhrase(text: String): Boolean {
        val normalized = text.lowercase(Locale("fa", "IR"))
            .replace("ي", "ی")
            .replace("ئ", "ی")
            .replace("ك", "ک")
            .replace(Regex("[^a-z0-9آ-ی]"), "")
        return normalized.contains("سلامیولداش") ||
               normalized.contains("salamyoldas") ||
               normalized.contains("سلامیولداش")
    }

    // ---------------- Main dispatcher ----------------

    private fun handleText(text: String) {
        android.util.Log.d("VocalAssistantSTT", "handleText=$text; confirmation=$confirmationMode; awaiting=$awaitingCommand; pendingNotif=$pendingNotificationRead")

        val normalized = normalizeCommandForMatching(text)

        // 0) پاسخ به پیشنهاد خواندن اعلان
        if (pendingNotificationRead) {
            if (isConfirmation(normalized)) {
                val body = pendingNotificationText.orEmpty()
                val sender = pendingNotificationSender ?: pendingNotificationApp ?: "فرستنده"
                pendingNotificationRead = false
                pendingNotificationText = null
                pendingNotificationApp = null
                pendingNotificationSender = null
                awaitingCommand = false
                confirmationMode = false
                if (body.isBlank()) {
                    speak("متن پیام خالی بود.")
                } else {
                    speak("پیام از $sender: $body")
                }
                return
            }
            if (isRejection(normalized)) {
                pendingNotificationRead = false
                pendingNotificationText = null
                pendingNotificationApp = null
                pendingNotificationSender = null
                speak("باشه، نمی‌خوانم.")
                return
            }
            // اگر چیز دیگری گفت، پیشنهاد را رها کن و ادامه بده
            pendingNotificationRead = false
        }

        // 1) Wake phrase
        if (isWakePhrase(normalized)) {
            confirmationMode = false
            awaitingCommand = true
            val prompt = "بله، در خدمتم. فرمان را بگویید."
            updateNotification(prompt)
            speak(prompt)
            return
        }

        // 2) باز کردن برنامه
        if (!confirmationMode && handleOpenAppCommand(normalized)) {
            awaitingCommand = true
            return
        }

        // 3) اطلاعات (ساعت، تاریخ، باتری)
        if (!confirmationMode && handleInfoCommand(normalized)) {
            awaitingCommand = true
            return
        }

        // 4) تماس
        if (!confirmationMode && isCallCommand(normalized)) {
            awaitingCommand = false
            val contact = findContact(normalized)
            if (contact == null) {
                awaitingCommand = true
                val prompt = "مخاطبی با این نام پیدا نشد. نام را دوباره بگویید."
                updateNotification(prompt)
                speak(prompt)
                return
            }
            pendingName = contact.first
            pendingPhone = contact.second
            awaitingCommand = false
            confirmationMode = true
            val prompt = "برای تماس با ${contact.first} بگویید تأیید می‌کنم."
            updateNotification(prompt)
            speak(prompt)
            return
        }

        // 5) تأیید/لغو تماس
        if (confirmationMode && isConfirmation(normalized)) {
            confirmationMode = false
            placeCall()
            return
        }
        if (confirmationMode && isRejection(normalized)) {
            confirmationMode = false
            awaitingCommand = true
            pendingPhone = null
            pendingName = null
            val prompt = "تماس لغو شد."
            updateNotification(prompt)
            speak(prompt)
            return
        }

        // 6) دستور ناشناخته
        if (!confirmationMode && awaitingCommand) {
            awaitingCommand = false
            val prompt = "این فرمان را متوجه نشدم."
            updateNotification(prompt)
            speak(prompt)
        }
    }

    // ---------------- Open app ----------------

    private data class LaunchableApp(val label: String, val packageName: String)

    // نام‌های مستعار رایج (کاربر ممکن است بگوید "واتساپ"، بسته نصب‌شده "WhatsApp" باشد)
    private val appAliases: Map<String, List<String>> = mapOf(
        "whatsapp" to listOf("واتساپ", "واتس اپ", "whatsapp"),
        "telegram" to listOf("تلگرام", "telegram"),
        "instagram" to listOf("اینستاگرام", "اینستا", "instagram"),
        "chrome" to listOf("کروم", "chrome"),
        "youtube" to listOf("یوتیوب", "youtube"),
        "camera" to listOf("دوربین", "camera"),
        "gallery" to listOf("گالری", "گالری تصاویر", "photos", "gallery"),
        "phone" to listOf("تلفن", "تماس", "phone", "dialer"),
        "messages" to listOf("پیام", "پیام‌ها", "messages", "sms"),
        "settings" to listOf("تنظیمات", "settings"),
        "clock" to listOf("ساعت", "زمان", "clock"),
        "calculator" to listOf("ماشین حساب", "حساب", "calculator"),
        "maps" to listOf("نقشه", "گوگل مپ", "maps"),
        "browser" to listOf("مرورگر", "browser"),
        "music" to listOf("موسیقی", "آهنگ", "music"),
        "calendar" to listOf("تقویم", "calendar")
    )

    private fun handleOpenAppCommand(text: String): Boolean {
        val command = normalizeCommandForMatching(text)

        val openWords = listOf("باز کن", "بازش کن", "اجرا کن", "بیار", "بیاور", "برو به", "رو باز", "را باز")
        val hasOpenIntent = openWords.any { command.contains(it) }
        if (!hasOpenIntent) return false

        var appName = command
            .replace("برنامه", "")
            .replace("اپلیکیشن", "")
            .replace("اپ ", "")
            .replace("رو", "")
            .replace("را", "")
            .replace("باز کن", "")
            .replace("بازش کن", "")
            .replace("اجرا کن", "")
            .replace("بیار", "")
            .replace("بیاور", "")
            .replace("برو به", "")
            .replace("به", "")
            .trim()

        if (appName.isBlank()) {
            speak("نام برنامه را نگفتید.")
            return true
        }

        val app = findLaunchableApp(appName)
        if (app == null) {
            val prompt = "برنامه «$appName» را پیدا نکردم."
            updateNotification(prompt)
            speak(prompt)
            return true
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent == null) {
            val prompt = "برنامه «${app.label}» قابل باز کردن نیست."
            updateNotification(prompt)
            speak(prompt)
            return true
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launchIntent)
            val prompt = "در حال باز کردن ${app.label}."
            updateNotification(prompt)
            speak(prompt)
        } catch (error: Exception) {
            android.util.Log.e("VocalAssistantSTT", "Could not open app ${app.packageName}", error)
            val prompt = "نتوانستم ${app.label} را باز کنم."
            updateNotification(prompt)
            speak(prompt)
        }
        return true
    }

    private fun findLaunchableApp(query: String): LaunchableApp? {
        val normalizedQuery = normalizeName(query)

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val apps = packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.MATCH_ALL
        ).mapNotNull { info: ResolveInfo ->
            val label = info.loadLabel(packageManager)?.toString()?.trim()
            val packageName = info.activityInfo?.packageName
            if (label.isNullOrBlank() || packageName.isNullOrBlank()) null
            else LaunchableApp(label, packageName)
        }.distinctBy { it.packageName }

        // 1) match مستقیم روی label
        apps.firstOrNull { normalizeName(it.label) == normalizedQuery }?.let { return it }

        // 2) match با نام‌های مستعار
        for ((_, aliases) in appAliases) {
            if (aliases.any { normalizeName(it) == normalizedQuery || normalizedQuery.contains(normalizeName(it)) }) {
                // دنبال اپی بگرد که label آن شامل یکی از alias‌ها یا کلید انگلیسی باشد
                val match = apps.firstOrNull { app ->
                    val label = normalizeName(app.label)
                    val pkg = app.packageName.lowercase(Locale.US)
                    aliases.any { alias ->
                        val a = normalizeName(alias)
                        label.contains(a) || pkg.contains(a.replace(" ", ""))
                    } || pkg.contains(aliases.first().replace(" ", "").lowercase(Locale.US))
                }
                if (match != null) return match
            }
        }

        // 3) partial label
        apps.firstOrNull {
            val label = normalizeName(it.label)
            label.contains(normalizedQuery) || normalizedQuery.contains(label)
        }?.let { return it }

        // 4) کلمه‌به‌کلمه
        val queryTokens = normalizedQuery.split(" ").filter { it.length > 1 }
        var best: LaunchableApp? = null
        var bestScore = 0
        for (app in apps) {
            val label = normalizeName(app.label)
            val pkg = app.packageName.lowercase(Locale.US)
            val score = queryTokens.count { token -> label.contains(token) || pkg.contains(token) }
            if (score > bestScore) {
                bestScore = score
                best = app
            }
        }
        return if (bestScore > 0) best else null
    }

    // ---------------- Info: time / date / battery ----------------

    private fun handleInfoCommand(text: String): Boolean {
        val command = normalizeCommandForMatching(text)

        val asksTime = listOf(
            "ساعت چنده", "ساعت چند است", "ساعت چند", "الان ساعت چنده",
            "زمان چنده", "زمان چند است", "وقت چنده", "وقت چند است"
        ).any { command.contains(it) } || command == "ساعت"

        val asksDate = listOf(
            "تاریخ چنده", "تاریخ چند است", "تاریخ امروز", "امروز چندمه",
            "امروز چندم است", "امروز چه روزیه", "امروز چه روزی است",
            "امروز چه روزی هست"
        ).any { command.contains(it) }

        val asksBattery = listOf(
            "شارژ باتری", "باتری چقدره", "باتری چند درصده", "باتری چند درصد است",
            "درصد باتری", "شارژم چقدره", "شارژ گوشی", "باتری گوشی"
        ).any { command.contains(it) }

        return when {
            asksTime -> { speakCurrentTime(); true }
            asksDate -> { speakCurrentDate(); true }
            asksBattery -> { speakBatteryLevel(); true }
            else -> false
        }
    }

    // ============ اعداد به کلمه فارسی ============

    private fun numberToPersianWords(number: Int): String {
        if (number == 0) return "صفر"
        if (number < 0) return "منفی " + numberToPersianWords(-number)

        val yekan = arrayOf("", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه")
        val dahgan = arrayOf("", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود")
        val dahYek = arrayOf("ده", "یازده", "دوازده", "سیزده", "چهارده", "پانزده", "شانزده", "هفده", "هجده", "نوزده")
        val sadgan = arrayOf("", "صد", "دویست", "سیصد", "چهارصد", "پانصد", "ششصد", "هفتصد", "هشتصد", "نهصد")

        var n = number
        val parts = mutableListOf<String>()

        if (n >= 1_000_000_000) {
            val b = n / 1_000_000_000
            parts.add(if (b == 1) "یک میلیارد" else "${numberToPersianWords(b)} میلیارد")
            n %= 1_000_000_000
        }
        if (n >= 1_000_000) {
            val m = n / 1_000_000
            parts.add(if (m == 1) "یک میلیون" else "${numberToPersianWords(m)} میلیون")
            n %= 1_000_000
        }
        if (n >= 1000) {
            val h = n / 1000
            parts.add(if (h == 1) "هزار" else "${numberToPersianWords(h)} هزار")
            n %= 1000
        }
        if (n >= 100) {
            parts.add(sadgan[n / 100])
            n %= 100
        }
        if (n >= 20) {
            parts.add(dahgan[n / 10])
            n %= 10
        } else if (n >= 10) {
            parts.add(dahYek[n - 10])
            n = 0
        }
        if (n in 1..9) {
            parts.add(yekan[n])
        }
        return parts.joinToString(" و ")
    }

    private fun speakCurrentTime() {
        val now = java.time.LocalTime.now()
        val h = numberToPersianWords(now.hour)
        val m = if (now.minute == 0) "" else " و ${numberToPersianWords(now.minute)} دقیقه"
        val answer = "الان ساعت $h$m است."
        updateNotification(answer)
        speak(answer)
    }

    private fun speakCurrentDate() {
        val today = java.time.LocalDate.now()
        val jalali = gregorianToJalali(today.year, today.monthValue, today.dayOfMonth)
        val weekdays = arrayOf(
            "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه", "یکشنبه"
        )
        val months = arrayOf(
            "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
        )
        val weekday = weekdays[today.dayOfWeek.value - 1]
        val answer = "امروز $weekday، ${numberToPersianWords(jalali.third)} ${months[jalali.second - 1]} ${numberToPersianWords(jalali.first)} است."
        updateNotification(answer)
        speak(answer)
    }

    private fun speakBatteryLevel() {
        val batteryIntent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent == null) {
            val answer = "نتوانستم میزان شارژ باتری را بخوانم."
            updateNotification(answer); speak(answer); return
        }
        val level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        val status = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        va
