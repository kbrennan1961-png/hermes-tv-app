package com.hermes.tvchat

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity() {

    // ---- CONFIG: edit these ----
    private val apiBase = "http://192.168.0.77:8765/v1"   // mini PC bridge
    private val apiKey = "hermes-tv"
    // ----------------------------

    private lateinit var chatView: TextView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var micBtn: Button
    private lateinit var scroll: ScrollView
    private lateinit var mediaOverlay: FrameLayout
    private lateinit var mediaImage: ImageView
    private lateinit var mediaVideo: VideoView
    private lateinit var mediaClose: TextView

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(240, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var tts: TextToSpeech? = null
    private val history = mutableListOf<Pair<String, String>>()

    private val REQ_VOICE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chatView = findViewById(R.id.chatView)
        input = findViewById(R.id.input)
        sendBtn = findViewById(R.id.sendBtn)
        micBtn = findViewById(R.id.micBtn)
        scroll = findViewById(R.id.scroll)
        mediaOverlay = findViewById(R.id.mediaOverlay)
        mediaImage = findViewById(R.id.mediaImage)
        mediaVideo = findViewById(R.id.mediaVideo)
        mediaClose = findViewById(R.id.mediaClose)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
        }

        sendBtn.setOnClickListener { sendText(input.text.toString().trim()) }
        micBtn.setOnClickListener { startVoice() }
        mediaOverlay.setOnClickListener { hideMedia() }
        mediaClose.setOnClickListener { hideMedia() }

        handleVoiceIntent(intent)
        appendChat("Hermes", "Ready. Speak or type. Try: \"draw a cyberpunk city at sunset\", \"video of a rocket launch\", or \"open Netflix\".")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleVoiceIntent(it) }
    }

    private fun handleVoiceIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEARCH, Intent.ACTION_VOICE_COMMAND, RecognizerIntent.ACTION_RECOGNIZE_SPEECH -> {
                val q = intent.getStringExtra(SearchManager.QUERY)
                if (!q.isNullOrBlank()) sendText(q)
                else startVoice()
            }
        }
    }

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to Hermes")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try { startActivityForResult(i, REQ_VOICE) }
        catch (e: Exception) { Toast.makeText(this, "No speech recognizer: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) sendText(spoken)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (mediaOverlay.visibility == View.VISIBLE && keyCode == KeyEvent.KEYCODE_BACK) {
            hideMedia(); return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (currentFocus == micBtn || currentFocus == sendBtn || currentFocus == input) return super.onKeyDown(keyCode, event)
            startVoice(); return true
        }
        if (keyCode == KeyEvent.KEYCODE_SEARCH) { startVoice(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun sendText(text: String) {
        if (text.isBlank()) return
        input.setText("")
        appendChat("You", text)

        val lower = text.lowercase().trim()
        if (lower.startsWith("open ")) {
            val app = lower.removePrefix("open ").trim()
            launchApp(app)
            appendChat("Hermes", "Opening $app…")
            speak("Opening $app")
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val (reply, mediaUrl, mediaType) = withContext(Dispatchers.IO) { callHermes(text) }
                appendChat("Hermes", reply)
                speak(reply)
                if (mediaUrl != null) showMedia(mediaUrl, mediaType)
            } catch (e: Exception) {
                appendChat("Hermes", "Error: ${e.message}")
            }
        }
    }

    private fun callHermes(userText: String): Triple<String, String?, String?> {
        history.add("user" to userText)
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content",
            "You are Hermes on the user's TV. Keep replies short (1-3 sentences) since they're read aloud. If the user asks to draw/image/picture, generate an image and end with 'MEDIA_URL: <url>' on its own line. If they ask for a video/clip, generate a video and end with 'MEDIA_URL: <url>'."))
        history.takeLast(8).forEach { (r, c) -> msgs.put(JSONObject().put("role", r).put("content", c)) }

        val body = JSONObject().apply {
            put("model", "hermes")
            put("messages", msgs)
            put("max_tokens", 500)
        }
        val req = Request.Builder()
            .url("$apiBase/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${resp.body?.string()}")
            val json = JSONObject(resp.body!!.string())
            val replyRaw = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
            val mediaUrl = json.optString("media_url").takeIf { it.isNotBlank() }
                ?: Regex("MEDIA_URL:\\s*(\\S+)").find(replyRaw)?.groupValues?.get(1)
            val mediaType = json.optString("media_type").takeIf { it.isNotBlank() }
                ?: if (mediaUrl != null && (mediaUrl.contains(".mp4") || mediaUrl.contains("video"))) "video" else "image"
            val cleanReply = replyRaw.replace(Regex("MEDIA_URL:\\s*\\S+"), "").trim()
            history.add("assistant" to cleanReply)
            return Triple(cleanReply.ifBlank { "Done." }, mediaUrl, mediaType)
        }
    }

    private fun showMedia(url: String, type: String?) {
        mediaOverlay.visibility = View.VISIBLE
        if (type == "video") {
            mediaImage.visibility = View.GONE
            mediaVideo.visibility = View.VISIBLE
            mediaVideo.setVideoURI(Uri.parse(url))
            mediaVideo.setOnPreparedListener { mp -> mp.isLooping = true; mediaVideo.start() }
        } else {
            mediaVideo.visibility = View.GONE
            mediaImage.visibility = View.VISIBLE
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val req = Request.Builder().url(url).build()
                    http.newCall(req).execute().use { r ->
                        val bytes = r.body!!.bytes()
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        withContext(Dispatchers.Main) { mediaImage.setImageBitmap(bmp) }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Image load failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun hideMedia() {
        mediaVideo.stopPlayback()
        mediaOverlay.visibility = View.GONE
    }

    private fun appendChat(who: String, text: String) {
        chatView.append("$who: $text\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun speak(text: String) {
        val clean = text.replace(Regex("[*#_`>\\-]"), "").take(400)
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "hermes-reply")
    }

    private fun launchApp(name: String) {
        val pkgs = mapOf(
            "netflix" to "com.netflix.ninja",
            "youtube" to "com.google.android.youtube.tv",
            "peacock" to "com.peacocktv.peacockandroid",
            "hulu" to "com.hulu.plus",
            "disney" to "com.disney.disneyplus",
            "prime" to "com.amazon.avod.thirdpartyclient",
            "max" to "com.hbo.hbonow"
        )
        val pkg = pkgs[name]
        if (pkg != null) {
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) { startActivity(i); return }
        }
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }
}