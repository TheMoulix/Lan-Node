package com.example.lannotes

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient

class NotesActivity : Activity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val bg = 0xFF0A0A0B.toInt()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val web = WebView(this).apply {
            setBackgroundColor(bg)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }
        setContentView(web)
        web.loadUrl(intent.getStringExtra("url") ?: "about:blank")
    }
}
