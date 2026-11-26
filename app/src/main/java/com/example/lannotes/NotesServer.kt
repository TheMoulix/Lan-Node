package com.example.lannotes

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet

class NotesServer(private val ctx: Context, port: Int, private val code: String) : NanoWSD(port) {
    private val dir = File(ctx.filesDir, "notes").apply { mkdirs() }
    private val sockets = CopyOnWriteArraySet<Ws>()
    @Volatile private var fails = 0

    private fun safe(n: String) = n.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60)

    private fun authed(s: IHTTPSession): Boolean {
        if (fails > 20) return false
        val c = s.parameters["c"]?.firstOrNull()
        if (c == code) return true
        fails++
        return false
    }

    override fun serveHttp(s: IHTTPSession): Response {
        if (s.uri == "/") {
            val html = ctx.assets.open("index.html").bufferedReader().readText()
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }
        if (!authed(s)) return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "bad code")
        if (s.uri == "/api/notes") {
            val arr = JSONArray()
            dir.listFiles()?.filter { it.extension == "md" }?.sortedByDescending { it.lastModified() }?.forEach {
                arr.put(JSONObject().put("name", it.nameWithoutExtension)
                    .put("mtime", it.lastModified()).put("preview", it.readText().take(120)))
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
        }
        if (s.uri.startsWith("/api/note/")) {
            val f = File(dir, safe(s.uri.removePrefix("/api/note/")) + ".md")
            val text = if (f.exists()) f.readText() else ""
            return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", text)
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
    }

    override fun openWebSocket(h: IHTTPSession): WebSocket = Ws(h, authed(h))

    private fun broadcast(from: Ws, msg: JSONObject) {
        val out = msg.toString()
        sockets.forEach { if (it !== from) try { it.send(out) } catch (_: IOException) {} }
    }

    inner class Ws(h: IHTTPSession, private val ok: Boolean) : WebSocket(h) {
        override fun onOpen() {
            if (ok) sockets.add(this) else close(WebSocketFrame.CloseCode.PolicyViolation, "code", false)
        }
        override fun onClose(c: WebSocketFrame.CloseCode?, r: String?, remote: Boolean) { sockets.remove(this) }
        override fun onMessage(m: WebSocketFrame) {
            val j = JSONObject(m.textPayload)
            val name = safe(j.getString("name"))
            if (name.isEmpty()) return
            if (j.optString("op") == "delete") {
                File(dir, "$name.md").delete()
                broadcast(this, JSONObject().put("name", name).put("deleted", true))
                return
            }
            val text = j.getString("text")
            File(dir, "$name.md").writeText(text)
            broadcast(this, JSONObject().put("name", name).put("text", text))
        }
        override fun onPong(p: WebSocketFrame?) {}
        override fun onException(e: IOException?) {}
    }
}
