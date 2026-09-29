package com.bejani.vocalassistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.provider.ContactsContract
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telecom.TelecomManager
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST_TTS) {
            testMode = true
            serviceActive = false
            recognizer?.cancel()
            speak("این یک صدای آزمایشی از دستیار صوتی است")
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
                    android.util.Log.d("VocalAssistantSTT", "final text=$text; awaiting=$awaitingCommand; confirmation=$confirmationMode")
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
                    android.util.Log.d("VocalAssistantSTT", "partial text=$text")
                    if (!testMode && text.isNotBlank()) updateNotification("در حال تشخیص: ${text.take(60)}")
                    if (isWakePhrase(text)) {
                        handleText(text)
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
        }
        recognizer?.startListening(intent)
    }

    private fun isWakePhrase(text: String): Boolean {
        val normalized = text.lowercase(Locale("fa", "IR"))
            .replace("ي", "ی")
            .replace("ئ", "ی")
            .replace("ك", "ک")
            .replace(Regex("[^a-z0-9آ-ی]"), "")
        return normalized.contains("سلامیولداش") || normalized.contains("salamyoldas")
    }

    private fun handleText(text: String) {
        android.util.Log.d("VocalAssistantSTT", "handleText=$text")
        if (isWakePhrase(text)) {
            confirmationMode = false
            awaitingCommand = true
            val prompt = "بله، در خدمتم. فرمان را بگویید. برای تماس بگویید تماس بگیر."
            updateNotification(prompt)
            speak(prompt)
            return
        }
        val normalizedCommand = text
            .replace("ي", "ی")
            .replace("ك", "ک")
            .replace("‌", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // Information commands: time, date and battery.
        // These are handled before the call parser so commands such as
        // "شارژ باتری چقدره" never fall through to the generic response.
        if (!confirmationMode && handleInfoCommand(normalizedCommand)) {
            awaitingCommand = true
            return
        }

        // A clear call command must be accepted even if a recognizer restart
        // accidentally reset the conversational state after the wake word.
        if (!confirmationMode && isCallCommand(normalizedCommand)) {
            awaitingCommand = false
            android.util.Log.d("VocalAssistantSTT", "call command=$normalizedCommand")
            val contact = findContact(normalizedCommand)
            if (contact == null) {
                awaitingCommand = true
                val prompt = "مخاطبی با این نام پیدا نشد. نام را دوباره بگویید."
                updateNotification(prompt)
                speak(prompt)
                return
            }
            android.util.Log.d("VocalAssistantSTT", "matched contact=${contact.first}")
            pendingName = contact.first
            pendingPhone = contact.second
            awaitingCommand = false
            confirmationMode = true
            val prompt = "برای تماس با ${contact.first} بگویید تأیید می‌کنم."
            updateNotification(prompt)
            speak(prompt)
            return
        }
        android.util.Log.d("VocalAssistantSTT", "confirmation check: mode=$confirmationMode text=$text")
        if (confirmationMode && isConfirmation(text)) {
            android.util.Log.d("VocalAssistantSTT", "confirmation accepted")
            confirmationMode = false
            placeCall()
        } else if (confirmationMode && isRejection(text)) {
            confirmationMode = false
            val prompt = "تماس لغو شد."
            awaitingCommand = true
            updateNotification(prompt)
            speak(prompt)
        } else if (!confirmationMode && awaitingCommand) {
            awaitingCommand = false
            val prompt = "این فرمان را متوجه نشدم. برای تماس بگویید تماس بگیر."
            updateNotification(prompt)
            speak(prompt)
        }
    }

    /**
     * Handles simple device-information questions without internet or AI.
     * The values come directly from the phone at the moment of the question.
     */
    private fun handleInfoCommand(text: String): Boolean {
        val command = normalizeCommandForMatching(text)

        val asksTime = listOf(
            "ساعت چنده", "ساعت چند است", "ساعت چند", "الان ساعت چنده",
            "زمان چنده", "زمان چند است", "وقت چنده", "وقت چند است"
        ).any { command.contains(it) } || command == "ساعت"

        val asksDate = listOf(
            "تاریخ چنده", "تاریخ چند است", "تاریخ امروز", "امروز چندمه",
            "امروز چندم است", "امروز چه روزیه", "امروز چه روزی است",
            "امروز چه روزی هست", "امروز چه روزیه"
        ).any { command.contains(it) }

        val asksBattery = listOf(
            "شارژ باتری", "باتری چقدره", "باتری چند درصده", "باتری چند درصد است",
            "درصد باتری", "شارژم چقدره", "شارژ گوشی", "باتری گوشی"
        ).any { command.contains(it) }

        return when {
            asksTime -> {
                speakCurrentTime()
                true
            }
            asksDate -> {
                speakCurrentDate()
                true
            }
            asksBattery -> {
                speakBatteryLevel()
                true
            }
            else -> false
        }
    }

    private fun normalizeCommandForMatching(value: String): String = value
        .replace("ي", "ی")
        .replace("ك", "ک")
        .replace("ۀ", "ه")
        .replace("ة", "ه")
        .replace("‌", " ")
        .replace("؟", " ")
        .replace("?", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase(Locale("fa", "IR"))

    private fun speakCurrentTime() {
        val now = java.time.LocalTime.now()
        val answer = "الان ساعت ${toPersianDigits(String.format(Locale.US, "%02d:%02d", now.hour, now.minute))} است."
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
        val answer = "امروز $weekday، ${toPersianDigits(jalali.third.toString())} ${months[jalali.second - 1]} ${toPersianDigits(jalali.first.toString())} است."
        updateNotification(answer)
        speak(answer)
    }

    private fun speakBatteryLevel() {
        val batteryIntent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent == null) {
            val answer = "نتوانستم میزان شارژ باتری را بخوانم."
            updateNotification(answer)
            speak(answer)
            return
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

        val answer = if (percent >= 0) {
            "شارژ باتری ${toPersianDigits(percent.toString())} درصد است$chargingText."
        } else {
            "نتوانستم میزان شارژ باتری را بخوانم."
        }
        updateNotification(answer)
        speak(answer)
    }

    private fun toPersianDigits(value: String): String = value.map { ch ->
        when (ch) {
            '0' -> '۰'; '1' -> '۱'; '2' -> '۲'; '3' -> '۳'; '4' -> '۴'
            '5' -> '۵'; '6' -> '۶'; '7' -> '۷'; '8' -> '۸'; '9' -> '۹'
            else -> ch
        }
    }.joinToString("")

    /** Gregorian date -> Persian (Jalali) date. */
    private fun gregorianToJalali(gyInput: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        var gy = gyInput
        var jy = if (gy > 1600) 979 else 0
        if (gy > 1600) gy -= 1600 else gy -= 621

        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 +
            (gy2 + 399) / 400 - 80 + gd + gdm[gm - 1]

        jy += 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461

        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }

        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return Triple(jy, jm, jd)
    }

    private fun isCallCommand(text: String): Boolean = listOf(
        "تماس", "تماس بگیر", "تماس بزن", "زنگ", "زنگ بزن", "زنگ بگیر", "تلفن"
    ).any(text::contains)

    private fun findContact(command: String): Pair<String, String>? {
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
        val queryNormalized = normalizeName(query)
        var best: Pair<String, String>? = null
        var bestScore = 0
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val phone = cursor.getString(1) ?: continue
                val normalizedName = normalizeName(name)
                val score = when {
                    normalizedName == queryNormalized -> 100
                    normalizedName.contains(queryNormalized) || queryNormalized.contains(normalizedName) -> 80
                    else -> normalizeName(query).split(" ").count { token -> token.length > 1 && normalizedName.contains(token) } * 10
                }
                if (score > bestScore) {
                    bestScore = score
                    best = name to phone
                }
            }
        }
        android.util.Log.d("VocalAssistantSTT", "contact query=$query; best=$best; score=$bestScore")
        return if (bestScore >= 20) best else null
    }

    private fun normalizeName(value: String): String = value.lowercase(Locale("fa", "IR"))
        .replace("ي", "ی")
        .replace("ك", "ک")
        .replace("ۀ", "ه")
        .replace("ة", "ه")
        .replace("ö", "o")
        .replace("ü", "u")
        .replace("ş", "s")
        .replace("ç", "c")
        .replace("ğ", "g")
        // Keep Persian/Arabic letters and digits; removing them makes every
        // Persian contact name become empty and prevents matching.
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun isConfirmation(text: String): Boolean = listOf("تایید", "تأیید", "تایید می کنم", "تأیید می‌کنم", "بله", "حتما", "حتماً").any(text::contains)
    private fun isRejection(text: String): Boolean = listOf("لغو", "نه", "خیر", "انصراف").any(text::contains)

    private fun placeCall() {
        val phone = pendingPhone ?: getSharedPreferences("assistant", MODE_PRIVATE).getString("phone", null)
        if (phone.isNullOrBlank()) {
            updateNotification("مخاطب انتخاب نشده است")
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.e("VocalAssistantSTT", "CALL_PHONE permission missing")
            updateNotification("مجوز تماس فعال نیست؛ از تنظیمات اجازه تماس را فعال کنید")
            speak("مجوز تماس فعال نیست")
            return
        }
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(phone)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            android.util.Log.d("VocalAssistantSTT", "placing call to phone=$phone")
            startActivity(intent)
            updateNotification("در حال برقراری تماس با ${pendingName ?: "مخاطب"}")
            speak("در حال برقراری تماس")
            pendingPhone = null
            pendingName = null
        } catch (error: SecurityException) {
            android.util.Log.e("VocalAssistantSTT", "ACTION_CALL security failure", error)
            updateNotification("تماس به دلیل مجوز امنیتی انجام نشد")
            speak("تماس انجام نشد؛ مجوز تماس را بررسی کنید")
        } catch (error: android.content.ActivityNotFoundException) {
            android.util.Log.e("VocalAssistantSTT", "No phone app can handle ACTION_CALL", error)
            updateNotification("برنامه تلفن برای تماس پیدا نشد")
            speak("برنامه تلفن پیدا نشد")
        } catch (error: Exception) {
            android.util.Log.e("VocalAssistantSTT", "Call failed", error)
            updateNotification("تماس انجام نشد: ${error.message ?: "خطای ناشناخته"}")
            speak("تماس انجام نشد")
        }
    }

    private fun speak(text: String) {
        pendingSpeech = text
        if (ttsPreparing) return
        ttsPreparing = true
        Thread {
            try {
                offlineTts.prepare { progress -> updateNotification("دانلود مدل صدای فارسی: $progress%") }
                ttsReady = true
                val queued = pendingSpeech
                pendingSpeech = null
                if (!queued.isNullOrBlank()) offlineTts.speak(queued)
                updateNotification("دستیار فعال است؛ در انتظار سلام یولداش")
            } catch (error: Exception) {
                ttsReady = false
                updateNotification("خطای صدای آفلاین: ${error.message}")
                android.util.Log.e("OfflinePersianTts", "Speech failed", error)
            } finally {
                ttsPreparing = false
            }
        }.start()
    }

    private fun scheduleRestart() {
        if (testMode) return
        if (restarting) return
        restarting = true
        android.os.Handler(mainLooper).postDelayed({
            restarting = false
            if (serviceActive) startListening()
        }, 450)
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, "voice")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Vocal Assistant")
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(10, notification(text))
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("voice", "دستیار صوتی", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        serviceActive = false
        recognizer?.destroy()
        recognizer = null
        offlineTts.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_TEST_TTS = "com.bejani.vocalassistant.TEST_TTS"
    }
}
