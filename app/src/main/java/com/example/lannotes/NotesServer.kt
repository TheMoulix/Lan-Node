package com.example.lannotes

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream

class NotesServer(private val ctx: Context, port: Int, private val code: String) : NanoHTTPD(port) {
    private val dir = File(ctx.filesDir, "notes").apply { mkdirs() }
    private val starFile = File(dir, ".stars")
    private val root = File(ctx.filesDir, "files").apply { mkdirs() }
    private fun resolve(rel: String): File? {
        val f = File(root, rel.trim('/')).canonicalFile
        val r = root.canonicalPath
        return if (f.path == r || f.path.startsWith(r + File.separator)) f else null
    }
    private fun fname(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().trim('.').take(120)
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
            addHeader("Access-Control-Allow-Private-Network", "true")
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
        val files = HashMap<String, String>()
        if (s.method == Method.POST) {
            try { s.parseBody(files) } catch (e: Exception) { return reply(Response.Status.BAD_REQUEST, "bad body") }
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
            s.uri == "/api/files" -> {
                val d = resolve(param(s, "path")) ?: return reply(Response.Status.BAD_REQUEST, "bad path")
                val arr = JSONArray()
                d.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))?.forEach {
                    arr.put(JSONObject().put("name", it.name).put("dir", it.isDirectory)
                        .put("size", if (it.isDirectory) (it.list()?.size ?: 0).toLong() else it.length())
                        .put("mtime", it.lastModified()))
                }
                return reply(Response.Status.OK, arr.toString(), "application/json; charset=utf-8")
            }
            s.uri == "/api/file" -> {
                val f = resolve(param(s, "path"))
                if (f == null || !f.isFile) return reply(Response.Status.NOT_FOUND, "no file")
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
                val r = newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(f), f.length())
                r.addHeader("Access-Control-Allow-Origin", "*")
                if (param(s, "dl") == "1") r.addHeader("Content-Disposition",
                    "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(f.name, "UTF-8").replace("+", "%20"))
                return r
            }
            s.uri == "/api/mkdir" && s.method == Method.POST -> {
                val d = resolve(param(s, "path")) ?: return reply(Response.Status.BAD_REQUEST, "bad path")
                val n = fname(param(s, "name"))
                if (n.isEmpty()) return reply(Response.Status.BAD_REQUEST, "no name")
                File(d, n).mkdirs()
                return reply(Response.Status.OK, "ok")
            }
            s.uri == "/api/upload" && s.method == Method.POST -> {
                val d = resolve(param(s, "path")) ?: return reply(Response.Status.BAD_REQUEST, "bad path")
                val tmp = files["file"] ?: return reply(Response.Status.BAD_REQUEST, "no file")
                val n = fname(param(s, "file")).ifEmpty { "file" }
                val stem = n.substringBeforeLast('.', n)
                val ext = if (n.contains('.')) "." + n.substringAfterLast('.') else ""
                var dst = File(d, n); var k = 1
                while (dst.exists()) { dst = File(d, "$stem ($k)$ext"); k++ }
                d.mkdirs()
                File(tmp).copyTo(dst)
                return reply(Response.Status.OK, "ok")
            }
            s.uri == "/api/fdelete" && s.method == Method.POST -> {
                val f = resolve(param(s, "path"))
                if (f == null || f == root.canonicalFile) return reply(Response.Status.BAD_REQUEST, "bad path")
                f.deleteRecursively()
                return reply(Response.Status.OK, "ok")
            }
            s.uri == "/api/frename" && s.method == Method.POST -> {
                val f = resolve(param(s, "path"))
                val n = fname(param(s, "to"))
                if (f == null || n.isEmpty() || f == root.canonicalFile) return reply(Response.Status.BAD_REQUEST, "bad")
                val dst = File(f.parentFile, n)
                if (dst.exists()) return reply(Response.Status.CONFLICT, "exists")
                f.renameTo(dst)
                return reply(Response.Status.OK, "ok")
            }
        }
        return reply(Response.Status.NOT_FOUND, "not found")
    }
}
