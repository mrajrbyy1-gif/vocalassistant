package com.bejani.vocalassistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Offline Persian neural TTS backed by sherpa-onnx and a VITS ONNX model. */
class OfflinePersianTts(private val context: Context) {
    companion object {
        private const val TAG = "OfflinePersianTts"
        private const val MODEL_URL = "https://github.com/bejani/vocalassistant/releases/download/tts-model-v1/fas-model.onnx"
        private const val TOKENS_URL = "https://github.com/bejani/vocalassistant/releases/download/tts-model-v1/fas-tokens.txt"
    }

    private val modelDir = File(context.filesDir, "tts/fas")
    @Volatile private var engine: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var preparing = false

    fun isInstalled(): Boolean {
        return try {
            File(modelDir, "model.onnx").length() > 1_000_000 &&
                File(modelDir, "tokens.txt").length() > 10
        } catch (_: Exception) { false }
    }

    /** Downloads the model once and initializes the native engine. Call off the main thread. */
    @Synchronized
    fun prepare(onProgress: ((Int) -> Unit)? = null) {
        try {
            modelDir.mkdirs()
            if (!isInstalled()) {
                download(MODEL_URL, File(modelDir, "model.onnx"), onProgress, 100)
                download(TOKENS_URL, File(modelDir, "tokens.txt"), null, 0)
            }
            if (engine == null) {
                val modelFile = File(modelDir, "model.onnx")
                val tokensFile = File(modelDir, "tokens.txt")
                if (!modelFile.exists() || !tokensFile.exists()) {
                    Log.e(TAG, "Model files missing after download")
                    return
                }
                val vits = OfflineTtsVitsModelConfig(
                    model = modelFile.absolutePath,
                    lexicon = "",
                    tokens = tokensFile.absolutePath,
                    dataDir = "",
                    noiseScale = 0.667f,
                    noiseScaleW = 0.8f,
                    lengthScale = 1.0f,
                )
                val created = OfflineTts(
                    config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            vits = vits,
                            numThreads = 2,
                            provider = "cpu",
                        )
                    )
                )
                engine = created
                Log.i(TAG, "Offline Persian TTS initialized; sampleRate=${created.sampleRate()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "prepare failed", e)
            engine = null
            throw e
        }
    }

    /** Generates and plays speech. Call off the main thread. */
    @Synchronized
    fun speak(text: String) {
        try {
            prepare()
        } catch (e: Exception) {
            Log.e(TAG, "prepare inside speak failed", e)
            return
        }
        val eng = engine ?: run {
            Log.e(TAG, "engine is null; cannot speak")
            return
        }

        val normalizedText = try { normalizeText(text) } catch (e: Exception) {
            Log.e(TAG, "normalizeText failed", e); text
        }
        Log.d(TAG, "speak raw='$text' normalized='$normalizedText'")
        if (normalizedText.isBlank()) {
            Log.w(TAG, "Normalized text is empty; nothing to speak")
            return
        }

        val audio = try {
            eng.generate(text = normalizedText, sid = 0, speed = 1.0f)
        } catch (e: Exception) {
            Log.e(TAG, "engine.generate failed", e); return
        }

        if (audio.samples.isEmpty()) {
            Log.w(TAG, "TTS generated no audio for: $normalizedText")
            return
        }
        val sampleRate = audio.sampleRate
        if (sampleRate <= 0) {
            Log.w(TAG, "Invalid sample rate: $sampleRate")
            return
        }

        try {
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            ).coerceAtLeast(audio.samples.size * 4)

            try { track?.release() } catch (_: Exception) {}
            track = null

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val newTrack = AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            track = newTrack
            newTrack.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
            newTrack.play()
            Log.d(TAG, "Played ${audio.samples.size} samples at $sampleRate Hz")
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack playback failed", e)
            try { track?.release() } catch (_: Exception) {}
            track = null
        }
    }

    @Synchronized
    fun release() {
        try { track?.release() } catch (_: Exception) {}
        track = null
        try { engine?.release() } catch (_: Exception) {}
        engine = null
    }

    // ============ پیش‌پردازش متن برای TTS ============

    private fun normalizeText(input: String): String {
        var s = input
        s = s.replace("ي", "ی")
             .replace("ك", "ک")
             .replace("ۀ", "ه")
             .replace("ة", "ه")
             .replace("\u200c", " ")

        s = convertDigitsToLatin(s)
        s = replaceNumbersWithWords(s)

        s = s.replace("%", " درصد ")
             .replace(":", " و ")
             .replace("؛", " ").replace(";", " ")
             .replace("/", " ").replace("\\", " ")
             .replace("-", " ").replace("_", " ")
             .replace("(", " ").replace(")", " ")
             .replace("[", " ").replace("]", " ")
             .replace("{", " ").replace("}", " ")
             .replace("\"", " ").replace("'", " ")
             .replace("«", " ").replace("»", " ")
             .replace("=", " ")
             .replace("+", " و ").replace("&", " و ")
             .replace("@", " ").replace("#", " ")
             .replace("*", " ").replace("^", " ")
             .replace("~", " ").replace("|", " ")
             .replace("<", " ").replace(">", " ")
             .replace("$", " ")

        s = s.replace("،", "، ")
             .replace(".", ". ")
             .replace("?", "؟ ")
             .replace("؟", "؟ ")
             .replace("!", "! ")

        s = s.replace(Regex("[^\\u0600-\\u06FF\\s\\.,،؟!]"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    private fun convertDigitsToLatin(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val mapped = when (ch) {
                '۰','٠' -> '0'; '۱','١' -> '1'; '۲','٢' -> '2'; '۳','٣' -> '3'; '۴','٤' -> '4'
                '۵','٥' -> '5'; '۶','٦' -> '6'; '۷','٧' -> '7'; '۸','٨' -> '8'; '۹','٩' -> '9'
                else -> ch
            }
            sb.append(mapped)
        }
        return sb.toString()
    }

    private fun replaceNumbersWithWords(input: String): String {
        val regex = Regex("\\d+")
        return regex.replace(input) { match ->
            val num = match.value.toLongOrNull() ?: return@replace match.value
            if (num in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                numberToPersianWords(num.toInt())
            } else {
                match.value.map { digitToPersianWord(it) }.joinToString(" و ")
            }
        }
    }

    private fun digitToPersianWord(ch: Char): String = when (ch) {
        '0' -> "صفر"; '1' -> "یک"; '2' -> "دو"; '3' -> "سه"; '4' -> "چهار"
        '5' -> "پنج"; '6' -> "شش"; '7' -> "هفت"; '8' -> "هشت"; '9' -> "نه"
        else -> ""
    }

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

    // ============ دانلود مدل ============

    private fun download(url: String, destination: File, onProgress: ((Int) -> Unit)?, progressWeight: Int) {
        val temporary = File(destination.parentFile, destination.name + ".part")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) { "Download failed: HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong
            var received = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } >= 0) {
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        received += read
                        if (total > 0 && onProgress != null && progressWeight > 0) {
                            onProgress(((received * progressWeight) / total).toInt().coerceIn(0, progressWeight))
                        }
                    }
                }
            }
            check(temporary.length() > 0) { "Downloaded file is empty" }
            check(temporary.renameTo(destination)) { "Could not finalize ${destination.name}" }
        } finally {
            connection.disconnect()
            if (temporary.exists() && !destination.exists()) temporary.delete()
        }
    }
}
