package com.example.lannotes

import android.Manifest
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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(r).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun ripple(d: GradientDrawable, tint: Int) =
        RippleDrawable(ColorStateList.valueOf(tint), d, null)

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

        val logo = text(SpannableString("Pocket Notes").apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(MUTE), 6, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }, 18f, INK)
        val title = text("Сервер заметок", 32f, INK, true)
        val sub = text("Запустите сервер и откройте адрес на компьютере в той же Wi-Fi сети.", 15f, MUTE).apply {
            setLineSpacing(0f, 1.15f)
        }

        val dot = View(this).apply { background = shape(MUTE, 8) }
        val status = text("Остановлен", 14f, MUTE)
        val statusRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)))
            addView(status, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) })
        }

        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val qrBox = FrameLayout(this).apply {
            background = shape(Color.WHITE, 16); setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(img, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        val addr = text("", 20f, INK, true)
        val codeView = text("", 34f, INK, true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); letterSpacing = 0.08f }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(PANEL, 20, LINE); setPadding(dp(20), dp(20), dp(20), dp(20))
            visibility = View.GONE
            addView(qrBox, lp(0, MATCH_PARENT, 0).apply { height = dp(264) })
            addView(text("Адрес", 13f, MUTE), lp(20))
            addView(addr, lp(2))
            addView(text("Код доступа", 13f, MUTE), lp(16))
            addView(codeView, lp(2))
        }

        val btn = text("Запустить", 16f, BG, true).apply {
            gravity = Gravity.CENTER
            background = ripple(shape(INK, 14), 0x33000000)
            isClickable = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            addView(logo)
            addView(title, lp(36)); addView(sub, lp(8)); addView(statusRow, lp(20))
            addView(card, lp(20))
            addView(btn, lp(24, MATCH_PARENT, dp(54)))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(content) })

        btn.setOnClickListener {
            if (running) {
                stopService(Intent(this, NotesService::class.java))
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                card.visibility = View.GONE
                dot.background = shape(MUTE, 8); status.text = "Остановлен"; status.setTextColor(MUTE)
                btn.text = "Запустить"; btn.setTextColor(BG); btn.background = ripple(shape(INK, 14), 0x33000000)
                running = false
                return@setOnClickListener
            }
            val ip = localIp()
            if (ip == null) { status.text = "Нет Wi-Fi. Подключитесь к сети или включите точку доступа."; return@setOnClickListener }
            val code = Random.nextInt(100000, 1000000).toString()
            startForegroundService(Intent(this, NotesService::class.java).putExtra("code", code))
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            img.setImageBitmap(qr("http://$ip:$PORT/?c=$code"))
            addr.text = "$ip:$PORT"
            codeView.text = code.chunked(3).joinToString(" ")
            card.visibility = View.VISIBLE
            dot.background = shape(GREEN, 8); status.text = "Работает"; status.setTextColor(INK)
            btn.text = "Остановить"; btn.setTextColor(INK); btn.background = ripple(shape(PANEL, 14, LINE), 0x22FFFFFF)
            running = true
        }
    }
}
