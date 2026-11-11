package com.prabhat.djmix.library

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Vocal / instrumental separation through the LALAL.AI API v1 (https://www.lalal.ai/api/v1/openapi.json):
 * upload the decoded track as WAV, start a `stem_separator` task for `vocals`, poll `check/` and download the
 * vocal stem and the back track as FLAC next to the library file. Blocking: runs on the prep thread.
 * The licence key lives in app prefs (pasted once in the Load sheet). Billing is per minute of audio.
 */
object Stems {
    private const val BASE = "https://www.lalal.ai/api/v1"
    private val client by lazy { OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.MINUTES).build() }
    private val json = "application/json".toMediaType()

    class Split(val vocals: File, val inst: File)

    fun key(ctx: Context): String? = ctx.getSharedPreferences("stems", Context.MODE_PRIVATE).getString("lalal", null)?.trim()?.takeIf { it.isNotEmpty() }
    fun setKey(ctx: Context, k: String) { ctx.getSharedPreferences("stems", Context.MODE_PRIVATE).edit().putString("lalal", k.trim()).apply() }

    fun voxFile(dir: File, vid: String) = File(dir, "$vid.vox.flac")
    fun instFile(dir: File, vid: String) = File(dir, "$vid.inst.flac")

    /** Processing minutes left on the plan. */
    fun minutesLeft(key: String): Double = post(key, "/limits/minutes_left/", "{}").getDouble("minutes_left")

    /** [pcmCache] is the app's decoded cache file (8-byte header, then little-endian 16-bit stereo at [rate]). */
    fun separate(key: String, pcmCache: File, rate: Int, vid: String, dir: File, progress: (Int) -> Unit): Split {
        val sourceId = upload(key, pcmCache, rate, "$vid.wav")
        try {
            val presets = JSONObject().put("stem", "vocals").put("splitter", "auto").put("encoder_format", "flac")
            val taskId = post(key, "/split/stem_separator/", JSONObject().put("source_id", sourceId).put("presets", presets).toString()).getString("task_id")
            val tracks = poll(key, taskId, progress)
            val vox = voxFile(dir, vid); val inst = instFile(dir, vid)
            download(tracks.getValue("stem"), vox)
            download(tracks.getValue("back"), inst)
            return Split(vox, inst)
        } finally {
            runCatching { post(key, "/delete/", JSONObject().put("source_id", sourceId).toString()) }
        }
    }

    private fun upload(key: String, pcmCache: File, rate: Int, name: String): String {
        val dataLen = pcmCache.length() - 8
        val header = wavHeader(rate, dataLen)
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = header.size + dataLen
            override fun writeTo(sink: BufferedSink) {
                sink.write(header)
                FileInputStream(pcmCache).use { it.skip(8); sink.writeAll(it.source()) }
            }
        }
        val req = Request.Builder().url("$BASE/upload/").header("X-License-Key", key)
            .header("Content-Disposition", "attachment; filename=\"$name\"").post(body).build()
        return execute(req).getString("id")
    }

    /** Waits for the task; returns url by track type ("stem" = vocals, "back" = instrumental). */
    fun poll(key: String, taskId: String, progress: (Int) -> Unit): Map<String, String> {
        val deadline = System.currentTimeMillis() + 30 * 60_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5000)
            val r = post(key, "/check/", JSONObject().put("task_ids", listOf(taskId)).toString()).getJSONObject("result").getJSONObject(taskId)
            when (r.getString("status")) {
                "success" -> return parseTracks(r)
                "progress" -> progress(r.optInt("progress", 0))
                "error" -> throw IOException(r.optJSONObject("error")?.optString("detail") ?: "separation failed")
                "cancelled" -> throw IOException("separation cancelled")
                else -> throw IOException(r.optString("error", "separation failed"))
            }
        }
        throw IOException("separation timed out")
    }

    /** The `tracks` of a successful check result, keyed by type. */
    fun parseTracks(success: JSONObject): Map<String, String> {
        val tracks = success.getJSONObject("result").getJSONArray("tracks")
        val out = HashMap<String, String>()
        for (i in 0 until tracks.length()) tracks.getJSONObject(i).let { out[it.getString("type")] = it.getString("url") }
        if ("stem" !in out || "back" !in out) throw IOException("result has no vocal + back track")
        return out
    }

    private fun download(url: String, to: File) {
        val tmp = File(to.path + ".part")
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("download ${r.code}")
            tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
        }
        if (!tmp.renameTo(to)) throw IOException("could not move ${tmp.name}")
    }

    private fun post(key: String, path: String, body: String): JSONObject =
        execute(Request.Builder().url(BASE + path).header("X-License-Key", key).post(body.toRequestBody(json)).build())

    private fun execute(req: Request): JSONObject = client.newCall(req).execute().use { r ->
        val text = r.body?.string() ?: ""
        if (!r.isSuccessful) throw IOException("LALAL.AI ${r.code}: " + (runCatching { JSONObject(text).optString("detail") }.getOrNull()?.takeIf { it.isNotBlank() } ?: text.take(200)))
        JSONObject(text)
    }

    /** 44-byte canonical WAV header for 16-bit stereo PCM. */
    fun wavHeader(rate: Int, dataLen: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataLen).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
        b.put("data".toByteArray()).putInt(dataLen.toInt())
        return b.array()
    }
}
