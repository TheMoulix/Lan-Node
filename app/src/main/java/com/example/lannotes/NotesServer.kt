package com.example.lannotes

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class NotesServer(private val ctx: Context, port: Int, private val code: String) : NanoHTTPD(port) {
    private val dir = File(ctx.filesDir, "notes").apply { mkdirs() }
    private val starFile = File(dir, ".stars")
    @Volatile private var fails = 0

    private fun safe(n: String) = n.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60)
    private fun param(s: IHTTPSession, k: String) = s.parameters[k]?.firstOrNull() ?: ""
    private fun stars(): MutableSet<String> =
        if (starFile.exists()) starFile.readLines().filter { it.isNotBlank() }.toMutableSet() else mutableSetOf()
    private fun saveStars(s: Set<String>) = starFile.writeText(s.joinToString("\n"))

    private fun reply(status: Response.IStatus, body: String, type: String = "text/plain; charset=utf-8"): Response =
        newFixedLengthResponse(status, type, body).apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Allow-Headers", "*")
            addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        }

    private fun authed(s: IHTTPSession): Boolean {
        if (fails > 20) return false
        if (param(s, "c") == code) return true
        fails++
        return false
    }

    override fun serve(s: IHTTPSession): Response {
        if (s.method == Method.OPTIONS) return reply(Response.Status.OK, "")
        if (s.uri == "/") {
            val html = ctx.assets.open("index.html").bufferedReader().readText()
            return reply(Response.Status.OK, html, "text/html; charset=utf-8")
        }
        if (s.method == Method.POST) {
            try { s.parseBody(HashMap()) } catch (e: Exception) { return reply(Response.Status.BAD_REQUEST, "bad body") }
        }
        if (!authed(s)) return reply(Response.Status.UNAUTHORIZED, "bad code")

        when {
            s.uri == "/api/notes" -> {
                val q = param(s, "q").trim().lowercase()
                val st = stars()
                val arr = JSONArray()
                dir.listFiles()?.filter { it.extension == "md" }?.sortedByDescending { it.lastModified() }?.forEach {
                    val body = it.readText()
                    if (q.isEmpty() || it.nameWithoutExtension.lowercase().contains(q) || body.lowercase().contains(q)) {
                        arr.put(JSONObject().put("name", it.nameWithoutExtension).put("mtime", it.lastModified())
                            .put("preview", body.replace(Regex("\\s+"), " ").trim().take(120))
                            .put("star", it.nameWithoutExtension in st).put("words", body.split(Regex("\\s+")).count { w -> w.isNotEmpty() }))
                    }
                }
                val out = JSONObject().put("now", System.currentTimeMillis()).put("notes", arr)
                return reply(Response.Status.OK, out.toString(), "application/json; charset=utf-8")
            }
            s.uri.startsWith("/api/note/") -> {
                val f = File(dir, safe(s.uri.removePrefix("/api/note/")) + ".md")
                return reply(Response.Status.OK, if (f.exists()) f.readText() else "")
            }
            s.uri == "/api/save" && s.method == Method.POST -> {
                val name = safe(param(s, "name"))
                if (name.isEmpty()) return reply(Response.Status.BAD_REQUEST, "no name")
                File(dir, "$name.md").writeText(param(s, "text"))
                return reply(Response.Status.OK, "ok")
            }
            s.uri == "/api/delete" && s.method == Method.POST -> {
                val name = safe(param(s, "name"))
                File(dir, "$name.md").delete()
                saveStars(stars().apply { remove(name) })
                return reply(Response.Status.OK, "ok")
            }
            s.uri == "/api/rename" && s.method == Method.POST -> {
                val from = safe(param(s, "name")); val to = safe(param(s, "to"))
                val src = File(dir, "$from.md"); val dst = File(dir, "$to.md")
                if (to.isEmpty() || !src.exists() || (dst.exists() && from != to)) return reply(Response.Status.CONFLICT, "exists")
                src.renameTo(dst)
                saveStars(stars().apply { if (remove(from)) add(to) })
                return reply(Response.Status.OK, to)
            }
            s.uri == "/api/star" && s.method == Method.POST -> {
                val name = safe(param(s, "name"))
                saveStars(stars().apply { if (!remove(name)) add(name) })
                return reply(Response.Status.OK, "ok")
            }
        }
        return reply(Response.Status.NOT_FOUND, "not found")
    }
}
