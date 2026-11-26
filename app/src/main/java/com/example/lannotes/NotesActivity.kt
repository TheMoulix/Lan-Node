package com.example.lannotes

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

class NotesActivity : Activity() {
    private var chooser: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val bg = 0xFF0A0A0B.toInt()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val web = WebView(this)
        web.setBackgroundColor(bg)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        @Suppress("DEPRECATION")
        web.settings.allowUniversalAccessFromFileURLs = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView?, cb: ValueCallback<Array<Uri>>?, p: FileChooserParams?): Boolean {
                chooser?.onReceiveValue(null)
                chooser = cb
                return try { startActivityForResult(p!!.createIntent(), 77); true } catch (e: Exception) { chooser = null; false }
            }
        }
        web.setDownloadListener { dlUrl, _, cd, mime, _ ->
            val name = URLUtil.guessFileName(dlUrl, cd, mime)
            val req = DownloadManager.Request(Uri.parse(dlUrl)).setMimeType(mime).setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        }
        setContentView(web)
        web.loadUrl(intent.getStringExtra("url") ?: "about:blank")
    }

    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        if (rc == 77) {
            chooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data))
            chooser = null
        } else super.onActivityResult(rc, res, data)
    }
}
