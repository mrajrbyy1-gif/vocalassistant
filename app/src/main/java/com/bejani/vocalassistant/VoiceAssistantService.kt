package com.bejani.vocalassistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.ContactsContract
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    private var pendingNotificationRead = false
    private var pendingNotificationText: String? = null
    private var pendingNotificationApp: String? = null
    private var pendingNotificationSender: String? = null

    private val handler = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_TEST_TTS -> {
                    testMode = true
                    serviceActive = false
                    try { recognizer?.cancel() } catch (_: Exception) {}
                    speak("این یک صدای آزمایشی از دستیار صوتی است")
                }
                ACTION_ANNOUNCE_NOTIFICATION -> {
                    val app = intent.getStringExtra(EXTRA_NOTIF_APP) ?: "یک برنامه"
                    val sender = intent.getStringExtra(EXTRA_NOTIF_SENDER)
                    val body = intent.getStringExtra(EXTRA_NOTIF_TEXT) ?: ""
                    announceNotification(app, sender, body)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VocalAssistantSTT", "onStartCommand failed", e)
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            offlineTts = OfflinePersianTts(this)
            startForegroundSafely("در انتظار سلام یولداش")
            startListening()
        } catch (e: Exception) {
            android.util.Log.e("VocalAssistantSTT", "onCreate failed", e)
        }
    }

    private fun startForegroundSafely(text: String) {
        val notif = notification(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(10, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(10, notif)
            }
        } catch (e: Exception) {
            android.util.Log.e("VocalAssistantSTT", "startForeground with type failed", e)
            try {
                startForeground(10, notif)
            } catch (e2: Exception) {
                android.util.Log.e("VocalAssistantSTT", "fallback startForeground failed", e2)
            }
        }
    }

    // ---------------- Speech Recognition ----------------

    private fun startListening() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                updateNotification("سرویس تشخیص گفتار روی این گوشی در دسترس نیست")
                return
            }
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { speech ->
                speech.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        try {
                            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.joinToString(" ")?.lowercase(Locale("fa", "IR")) ?: ""
                            android.util.Log.d("VocalAssistantSTT", "final text=$text")
                            if (!testMode && text.isNotBlank()) {
                                updateNotification("شنیده شد: ${text.take(70)}")
                            }
                            if (!testMode) handleText(text)
                            if (!testMode) scheduleRestart()
                        } catch (e: Exception) {
                            android.util.Log.e("VocalAssistantSTT", "onResults failed", e)
                            scheduleRestart()
                        }
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
                        try {
                            val text = partialResults
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.joinToString(" ")?.lowercase(Locale("fa", "IR")) ?: ""
                            if (!testMode && text.isNotBlank()) {
                                updateNotification("در حال تشخیص: ${text.take(60)}")
                            }
                        } catch (_: Exception) {}
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
        } catch (e: Exception) {
            android.util.Log.e("VocalAssistantSTT", "startListening failed", e)
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        try {
            if (testMode) return
            if (restarting) return
            restarting = true
            handler.postDelayed({
                restarting = false
                if (serviceActive) {
                    try { startListening() } catch (e: Exception) {
                        android.util.Log.e("VocalAssistantSTT", "restart listen failed", e)
                    }
                }
            }, 400)
        } catch (_: Exception) {}
    }

    // ---------------- Wake Phrase ----------------

    private fun isWakePhrase(text: String): Boolean {
        val normalized = text.lowercase(Locale("fa", "IR"))
            .replace("ي", "ی")
            .replace("ئ", "ی")
            .replace("ك", "ک")
            .replace(Regex("[^a-z0-9آ-ی]"), "")
        return normalized.contains("سلامیولداش") || normalized.contains("salamyoldas")
    }

    // ---------------- Dispatcher ----------------

    private fun handleText(text: String) {
        try {
            val normalized = normalizeCommandForMatching(text)
            android.util.Log.d(
                "VocalAssistantSTT",
                "handleText=$normalized; conf=$confirmationMode; await=$awaitingCommand; pendingNotif=$pendingNotificationRead"
            )

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
                    if (body.isBlank()) speak("متن پیام خالی بود.")
                    else speak("پیام از $sender: $body")
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
                pendingNotificationRead = false
            }

            if (isWakePhrase(normalized)) {
                confirmationMode = false
                awaitingCommand = true
                val prompt = "بله، در خدمتم. فرمان را بگویید."
                updateNotification(prompt)
                speak(prompt)
                return
            }

            if (!confirmationMode && handleOpenAppCommand(normalized)) {
                awaitingCommand = true
                return
            }

            if (!confirmationMode && handleInfoCommand(normalized)) {
                awaitingCommand = true
                return
            }

            if (!confirmationMode && isCallCommand(normalized)) {
                awaitingCommand = false
                val contact = findContact(normalized)
                if (contact == null) {
                    awaitingCommand = true
                    val prompt = "مخاطبی با این نام پیدا نشد. نام را دوباره بگویید."
                    updateNotification(prompt); speak(prompt)
                    return
                }
                pendingName = contact.first
                pendingPhone = contact.second
                awaitingCommand = false
                confirmationMode = true
                val prompt = "برای تماس با ${contact.first} بگویید تأیید می‌کنم."
                updateNotification(prompt); speak(prompt)
                return
            }

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
                updateNotification(prompt); speak(prompt)
                return
            }

            if (!confirmationMode && awaitingCommand) {
                awaitingCommand = false
                val prompt = "این فرمان را متوجه نشدم."
                updateNotification(prompt); speak(prompt)
            }
        } catch (e: Exception) {
            android.util.Log.e("VocalAssistantSTT", "handleText failed", e)
        }
    }

    // ---------------- Open App ----------------

    private data class LaunchableApp(val label: String, val packageName: String)

    private val appAliases: Map<String, List<String>> = mapOf(
        "whatsapp"  to listOf("واتساپ", "واتس اپ", "whatsapp"),
        "telegram"  to listOf("تلگرام", "telegram"),
        "instagram" to listOf("اینستاگرام", "اینستا", "instagram"),
        "chrome"    to listOf("کروم", "chrome"),
        "youtube"   to listOf("یوتیوب", "youtube"),
        "camera"    to listOf("دوربین", "camera"),
        "gallery"   to listOf("گالری", "photos", "gallery"),
        "phone"     to listOf("تلفن", "phone", "dialer"),
        "messages"  to listOf("پیام", "messages", "sms"),
        "settings"  to listOf("تنظیمات", "settings"),
        "clock"     to listOf("ساعت", "clock"),
        "calculator" to listOf("ماشین حساب", "calculator"),
        "maps"      to listOf("نقشه", "maps"),
        "browser"   to listOf("مرورگر", "browser"),
        "music"     to listOf("موسیقی", "music"),
        "calendar"  to listOf("تقویم", "calendar")
    )

    private fun handleOpenAppCommand(text: String): Boolean {
        try {
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
                updateNotification(prompt); speak(prompt)
                return true
            }

            val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
            if (launchIntent == null) {
                val prompt = "برنامه «${app.label}» قابل باز کردن نیست."
                updateNotification(prompt); speak(prompt)
                return true
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(launchIntent)
                val prompt = "در حال باز کردن ${app.label}."
                updateNotification(prompt); speak(prompt)
            } catch (e: Exception) {
                val prompt = "نتوانستم ${app.label} را باز کنم."
                updateNotification(prompt); speak(prompt)
            }
            return true
        } catch (e: Exception) {
            android.util.Log.e("VocalAssistantSTT", "openApp failed", e)
            return false
        }
    }

    private fun findLaunchableApp(query: String): LaunchableApp? {
        return try {
            val normalizedQuery = normalizeName(query)
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = packageManager.queryIntentActivities(
                launcherIntent, PackageManager.MATCH_ALL
            ).mapNotNull { info: ResolveInfo ->
                val label = info.loadLabel(packageManager)?.toString()?.trim()
                val pkg = info.activityInfo?.packageName
                if (label.isNullOrBlank() || pkg.isNullOrBlank()) null
                else LaunchableApp(label, pkg)
            }.distinctBy { it.packageName }

            apps.firstOrNull { normalizeName(it.label) == normalizedQuery }?.let { return it }

            for ((pkgKey, aliases) in appAliases) {
                if (aliases.any { normalizeName(it) == normalizedQuery || normalizedQuery.contains(normalizeName(it)) }) {
                    val match = apps.firstOrNull { app ->
                        val label = normalizeName(app.label)
                        val pkg = app.packageName.lowercase(Locale.US)
                        aliases.any { a ->
                            val an = normalizeName(a)
                            label.contains(an) || pkg.contains(an.replace(" ", ""))
                        } || pkg.contains(pkgKey)
                    }
                    if (match != null) return match
                }
            }

            apps.firstOrNull {
                val label = normalizeName(it.label)
                label.contains(normalizedQuery) || normalizedQuery.contains(label)
            }?.let { return it }

            val queryTokens = normalizedQuery.split(" ").filter { it.length > 1 }
            var best: LaunchableApp? = null
            var bestScore = 0
            for (app in apps) {
                val label = normalizeName(app.label)
                val pkg = app.packageName.lowercase(Locale.US)
                val score = queryTokens.count { t -> label.contains(t) || pkg.contains(t) }
                if (score > bestScore) { bestScore = score; best = app }
            }
            if (bestScore > 0) best else null
        } catch (e: Exception) {
            android.util.Log.e("VocalAssistantSTT", "findApp failed", e)
            null
        }
    }

    // ---------------- Info Commands ----------------

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

    // ============ اعداد به کلمه ============

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
        if (n >= 100) { parts.add(sadgan[n / 100]); n %= 100 }
        if (n >= 20) { parts.add(dahgan[n / 10]); n %= 10 }
        else if (n >= 10) { parts.add(dahYek[n - 10]); n = 0 }
        if (n in 1..9) parts.add(yekan[n])

        return parts.joinToString(" و ")
    }

    private fun speakCurrentTime() {
        val now = java.time.LocalTime.now()
        val h = numberToPersianWords(now.hour)
        val m = if (now.minute == 0) "" else " و ${numberToPersianWords(now.minute)} دقیقه"
        val answer = "الان ساعت $h$m است."
        updateNotification(answer); speak(answer)
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
        updateNotification(answer); speak(answer)
    }

    private fun speakBatteryLevel() {
        try {
            val batteryIntent = registerReceiver(
                null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            if (batteryIntent == null) {
                val a = "نتوانستم میزان شارژ باتری را بخوانم."
                updateNotification(a); speak(a); return
            }
            val level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            val status = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val chargingText = when (status) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING -> " و در حال شارژ است"
                android.os.BatteryManager.BATTERY_STATUS_FULL -> " و کاملاً شارژ شده است"
                else -> ""
            }
            val answer = if (percent >= 0)
                "شارژ باتری ${numberToPersianWords(percent)} درصد است$chargingText."
            else "نتوانستم میزان شارژ باتری را بخوانم."
            updateNotification(answer); speak(answer)
        } catch (e: Exception) {
            android.util.Log.e("VocalAssistantSTT", "battery failed", e)
        }
    }

    private fun gregorianToJalali(gyInput: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        var gy = gyInput
        var jy = if (gy > 1600) 979 else 0
        if (gy > 1600) gy -= 1600 else gy -= 621
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 - 80 + gd + gdm[gm - 1]
        jy += 33 * (days / 12053); days %= 12053
        jy += 4 * (days / 1461); days %= 1461
        if (days > 365) { jy += (days - 1) / 365; days = (days - 1) % 365 }
        val jm: Int; val jd: Int
        if (days < 186) { jm = 1 + days / 31; jd = 1 + days % 31 }
        else { jm = 7 + (days - 186) / 30; jd = 1 + (days - 186) % 30 }
        return Triple(jy, jm, jd)
    }

    // ---------------- Calls ----------------

    private fun isCallCommand(text: String): Boolean = listOf(
        "تماس", "تماس بگیر", "تماس بزن", "زنگ", "زنگ بزن", "زنگ بگیر", "تلفن"
    ).any(text::contains)

    private fun findContact(command: String): Pair<String, String>? {
        return try {
            val query = command
                .replace("سلام یولداش", "", ignoreCase = true)
                .replace("تماس بگیر", "", ignoreCase = true)
                .replace("تماس بزن", "", ignoreCase = true)
                .replace("زنگ بزن به", "", ignoreCase = true)
                .replace("زنگ بزن", "", ignoreCase = true)
                .replace("زنگ بگیر", "", ignoreCase = true)
                .replace(Regex("^(به|با)\\s+"), "")
                .trim()
            if (query.isBlank()) return null
            val q = normalizeName(query)
            if (q.isBlank()) return null

            var best: Pair<String, String>? = null
            var bestScore = 0
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    val phone = cursor.getString(1) ?: continue
                    val n = normalizeName(name)
                    if (n.isBlank()) continue
                    val score = when {
                        n == q -> 100
                        n.contains(q) || q.contains(n) -> 80
                        else -> q.split(" ").filter { it.length > 1 }.count { n.contains(it) } * 10
                    }
                    if (score > bestScore) { bestScore = score; best = name to phone }
                }
            }
            if (bestScore >= 20) best else null
        } catch (e: Exception) {
            android.util.Log.e("VocalAssistantSTT", "findContact failed", e)
            null
        }
    }

    private fun normalizeName(value: String): String = value.lowercase(Locale("fa", "IR"))
        .replace("ي", "ی").replace("ك", "ک")
        .replace("ۀ", "ه").replace("ة", "ه")
        .replace("ö", "o").replace("ü", "u")
        .replace("ş", "s").replace("ç", "c").replace("ğ", "g")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun normalizeCommandForMatching(value: String): String = value
        .replace("ي", "ی").replace("ك", "ک")
        .replace("ۀ", "ه").replace("ة", "ه")
        .replace("\u200c", " ")
        .replace("؟", " ").replace("?", " ")
        .replace(Regex("\\s+"), " ").trim()
        .lowercase(Locale("fa", "IR"))

    private fun isConfirmation(text: String): Boolean = listOf(
        "تایید", "تأیید", "تایید می کنم", "تأیید می‌کنم",
        "بله", "آره", "حتما", "حتماً", "بخوان"
    ).any(text::contains)

    private fun isRejection(text: String): Boolean = listOf(
        "لغو", "نه", "خیر", "انصراف", "نمی‌خوام", "نمیخوام", "نخوان"
    ).any(text::contains)

    private fun placeCall() {
        try {
            val phone = pendingPhone
                ?: getSharedPreferences("assistant", MODE_PRIVATE).getString("phone", null)
            if (phone.isNullOrBlank()) {
                updateNotification("مخاطب انتخاب نشده است"); return
            }
            if (ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.CALL_PHONE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                updateNotification("مجوز تماس فعال نیست"); speak("مجوز تماس فعال نیست"); return
            }
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(phone)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            updateNotification("در حال برقراری تماس با ${pendingName ?: "مخاطب"}")
            speak("در حال برقراری تماس")
            pendingPhone = null
            pendingName = null
        } catch (e: SecurityException) {
            updateNotification("تماس به دلیل مجوز امنیتی انجام نشد"); speak("تماس انجام نشد")
        } catch (e: android.content.ActivityNotFoundException) {
            updateNotification("برنامه تلفن پیدا نشد"); speak("برنامه تلفن پیدا نشد")
        } catch (e: Exception) {
            updateNotification("تماس انجام نشد"); speak("تماس انجام نشد")
        }
    }

    // ---------------- Notifications ----------------

    private fun announceNotification(app: String, sender: String?, body: String) {
        try {
            pendingNotificationApp = app
            pendingNotificationSender = sender
            pendingNotificationText = body
            pendingNotificationRead = true

            val fromText = if (!sender.isNullOrBlank()) "از $sender در $app" else "از $app"
            val prompt = "یک پیام $fromText رسید. متن پیام را بخوانم؟ بگویید بله یا نه."
            updateNotification(prompt); speak(prompt)
        } catch (e: Exception) {
            android.util.Log.e("VocalAssistantSTT", "announce failed", e)
        }
    }

    // ---------------- TTS ----------------

    private fun speak(text: String) {
        try {
            pendingSpeech = text
            if (ttsPreparing) return
            ttsPreparing = true
            Thread {
                try {
                    offlineTts.prepare { progress ->
                        updateNotification("دانلود مدل صدای فارسی: $progress%")
                    }
                    ttsReady = true
                    val queued = pendingSpeech
                    pendingSpeech = null
                    if (!queued.isNullOrBlank()) offlineTts.speak(queued)
                    updateNotification("دستیار فعال است؛ در انتظار سلام یولداش")
                } catch (e: Exception) {
                    ttsReady = false
                    updateNotification("خطای صدای آفلاین: ${e.message}")
                    android.util.Log.e("OfflinePersianTts", "speak failed", e)
                } finally {
                    ttsPreparing = false
                }
            }.start()
        } catch (e: Exception) {
            android.util.Log.e("VocalAssistantSTT", "speak outer failed", e)
        }
    }

    // ---------------- Foreground Notification ----------------

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, "voice")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Vocal Assistant")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(10, notification(text))
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel("voice", "دستیار صوتی", NotificationManager.IMPORTANCE_LOW)
                )
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        serviceActive = false
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        try { offlineTts.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_TEST_TTS = "com.bejani.vocalassistant.TEST_TTS"
        const val ACTION_ANNOUNCE_NOTIFICATION = "com.bejani.vocalassistant.ANNOUNCE_NOTIFICATION"
        const val EXTRA_NOTIF_APP = "notif_app"
        const val EXTRA_NOTIF_SENDER = "notif_sender"
        const val EXTRA_NOTIF_TEXT = "notif_text"
    }
}
