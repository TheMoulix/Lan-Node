package com.example.lannotes

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random

class MainActivity : Activity() {
    companion object { const val PORT = 8080 }

    private val BG = 0xFF0A0A0B.toInt()
    private val PANEL = 0xFF111113.toInt()
    private val LINE = 0xFF212126.toInt()
    private val INK = 0xFFF4F4F5.toInt()
    private val MUTE = 0xFF8B8B93.toInt()
    private val GREEN = 0xFF4ADE80.toInt()

    private var running = false
    private var lastCode = ""
    private var pulse: ObjectAnimator? = null

    private lateinit var dot: View
    private lateinit var status: TextView
    private lateinit var card: LinearLayout
    private lateinit var img: ImageView
    private lateinit var addr: TextView
    private lateinit var codeView: TextView
    private lateinit var openBtn: TextView
    private lateinit var btn: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(r).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun ripple(d: GradientDrawable, tint: Int) = RippleDrawable(ColorStateList.valueOf(tint), d, null)

    private fun text(t: CharSequence, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun lp(top: Int = 0, w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun localIp(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress

    private fun qr(content: String): Bitmap {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 640, 640)
        val bmp = Bitmap.createBitmap(640, 640, Bitmap.Config.RGB_565)
        for (x in 0 until 640) for (y in 0 until 640) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val logo = text(SpannableString("PocketNotes").apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(MUTE), 6, 11, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }, 18f, INK)
        val logoRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_logo) }, LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(logo, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(12) })
        }
        val title = text("Сервер заметок", 32f, INK, true)
        val sub = text("Запустите сервер и откройте адрес на компьютере в той же Wi-Fi сети.", 15f, MUTE).apply {
            setLineSpacing(0f, 1.15f)
        }

        dot = View(this).apply { background = shape(MUTE, 8) }
        status = text("Остановлен", 14f, MUTE)
        val statusRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)))
            addView(status, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) })
        }

        img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val qrBox = FrameLayout(this).apply {
            background = shape(Color.WHITE, 16); setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(img, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        addr = text("", 20f, INK, true)
        codeView = text("", 34f, INK, true).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); letterSpacing = 0.08f
        }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(PANEL, 20, LINE); setPadding(dp(20), dp(20), dp(20), dp(20))
            visibility = View.GONE
            addView(qrBox, lp(0, MATCH_PARENT, dp(264)))
            addView(text("Адрес", 13f, MUTE), lp(20))
            addView(addr, lp(2))
            addView(text("Код доступа", 13f, MUTE), lp(16))
            addView(codeView, lp(2))
        }

        btn = text("Запустить", 16f, BG, true).apply {
            gravity = Gravity.CENTER
            background = ripple(shape(INK, 14), 0x33000000)
            isClickable = true
        }
        openBtn = text("Открыть заметки на телефоне", 16f, INK, true).apply {
            gravity = Gravity.CENTER
            background = ripple(shape(PANEL, 14, LINE), 0x22FFFFFF)
            isClickable = true
            visibility = View.GONE
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            addView(logoRow)
            addView(title, lp(36)); addView(sub, lp(8)); addView(statusRow, lp(20))
            addView(card, lp(20))
            addView(openBtn, lp(16, MATCH_PARENT, dp(54)))
            addView(btn, lp(24, MATCH_PARENT, dp(54)))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(content) })

        openBtn.setOnClickListener { openNotes(false) }
        btn.setOnClickListener { if (running) stopServer() else startServer() }

        NotesService.code?.let { lastCode = it; showRunning(it, false) }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val c = NotesService.code
        if (c != null && !running) { lastCode = c; showRunning(c, false) }
        else if (c == null && running && lastCode.isNotEmpty()) showStopped()
    }

    // Запуск из виджета: поднимаем сервер при необходимости и сразу открываем новую заметку
    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("quick", false) != true) return
        i.removeExtra("quick")
        val was = running
        if (!was && !startServer()) return
        Handler(Looper.getMainLooper()).postDelayed({ openNotes(true) }, if (was) 0L else 700L)
    }

    private fun startServer(): Boolean {
        if (localIp() == null) {
            status.text = "Нет Wi-Fi. Подключитесь к сети или включите точку доступа."
            return false
        }
        val code = Random.nextInt(100000, 1000000).toString()
        lastCode = code
        startForegroundService(Intent(this, NotesService::class.java).putExtra("code", code))
        showRunning(code, true)
        return true
    }

    private fun stopServer() {
        stopService(Intent(this, NotesService::class.java))
        showStopped()
    }

    private fun openNotes(newNote: Boolean) {
        if (lastCode.isEmpty()) return
        val url = "http://127.0.0.1:$PORT/?c=$lastCode" + if (newNote) "&new=1" else ""
        startActivity(Intent(this, NotesActivity::class.java).putExtra("url", url))
    }

    private fun showRunning(code: String, animate: Boolean) {
        running = true
        val ip = localIp() ?: "—"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        img.setImageBitmap(qr("http://$ip:$PORT/?c=$code"))
        addr.text = "$ip:$PORT"
        codeView.text = code.chunked(3).joinToString(" ")
        card.visibility = View.VISIBLE
        if (animate) {
            card.alpha = 0f; card.scaleX = 0.94f; card.scaleY = 0.94f; card.translationY = dp(18).toFloat()
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(420).setInterpolator(DecelerateInterpolator()).start()
        }
        dot.background = shape(GREEN, 8)
        status.text = "Работает"; status.setTextColor(INK)
        pulse?.cancel()
        pulse = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.3f).apply {
            duration = 900; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start()
        }
        btn.text = "Остановить"; btn.setTextColor(INK); btn.background = ripple(shape(PANEL, 14, LINE), 0x22FFFFFF)
        openBtn.visibility = View.VISIBLE
    }

    private fun showStopped() {
        running = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pulse?.cancel(); dot.alpha = 1f
        card.visibility = View.GONE; openBtn.visibility = View.GONE
        dot.background = shape(MUTE, 8); status.text = "Остановлен"; status.setTextColor(MUTE)
        btn.text = "Запустить"; btn.setTextColor(BG); btn.background = ripple(shape(INK, 14), 0x33000000)
    }
}
