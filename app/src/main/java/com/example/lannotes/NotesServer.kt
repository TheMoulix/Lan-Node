package com.example.lannotes

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class NotesServer(private val ctx: Context, port: Int, private val code: String) : NanoHTTPD(port) {
    private val dir = File(ctx.filesDir, "notes").apply { mkdirs() }
    @Volatile private var fails = 0

    private fun safe(n: String) = n.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60)
    private fun param(s: IHTTPSession, k: String) = s.parameters[k]?.firstOrNull() ?: ""
    private fun text(status: Response.IStatus, body: String, type: String = "text/plain; charset=utf-8") =
        newFixedLengthResponse(status, type, body)

    private fun authed(s: IHTTPSession): Boolean {
        if (fails > 20) return false
        if (param(s, "c") == code) return true
        fails++
        return false
    }

    override fun serve(s: IHTTPSession): Response {
        if (s.uri == "/") {
            val html = ctx.assets.open("index.html").bufferedReader().readText()
            return text(Response.Status.OK, html, "text/html; charset=utf-8")
        }
        if (s.method == Method.POST) {
            try { s.parseBody(HashMap()) } catch (e: Exception) { return text(Response.Status.BAD_REQUEST, "bad body") }
        }
        if (!authed(s)) return text(Response.Status.UNAUTHORIZED, "bad code")

        when {
            s.uri == "/api/notes" -> {
                val arr = JSONArray()
                dir.listFiles()?.filter { it.extension == "md" }?.sortedByDescending { it.lastModified() }?.forEach {
                    arr.put(JSONObject().put("name", it.nameWithoutExtension)
                        .put("mtime", it.lastModified()).put("preview", it.readText().take(120)))
                }
                val out = JSONObject().put("now", System.currentTimeMillis()).put("notes", arr)
                return text(Response.Status.OK, out.toString(), "application/json; charset=utf-8")
            }
            s.uri.startsWith("/api/note/") -> {
                val f = File(dir, safe(s.uri.removePrefix("/api/note/")) + ".md")
                return text(Response.Status.OK, if (f.exists()) f.readText() else "")
            }
            s.uri == "/api/save" && s.method == Method.POST -> {
                val name = safe(param(s, "name"))
                if (name.isEmpty()) return text(Response.Status.BAD_REQUEST, "no name")
                File(dir, "$name.md").writeText(param(s, "text"))
                return text(Response.Status.OK, "ok")
            }
            s.uri == "/api/delete" && s.method == Method.POST -> {
                File(dir, safe(param(s, "name")) + ".md").delete()
                return text(Response.Status.OK, "ok")
            }
        }
        return text(Response.Status.NOT_FOUND, "not found")
    }
}
