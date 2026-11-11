package com.prabhat.djmix.instagram

import com.prabhat.djmix.youtube.YtAudio
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Instagram audio on the phone: a reel / post / IGTV link shared to the app becomes a library track with the id
 * `ig_<shortcode>`. The video (H.264 + AAC, one file) comes from Instagram's own web API, `api/v1/media/<pk>/info/`,
 * the call the instagram.com player makes. Most posts sit behind a login wall, so the call carries the instagram.com
 * cookies that [InstagramLoginSheet] collects. Pure JVM like [YtAudio]; everything blocks, call off the main thread.
 */
object IgAudio {
    const val PREFIX = "ig_"
    internal const val UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    /** instagram.com's own web client id; the API refuses calls without it. */
    private const val APP_ID = "936619743392459"
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val LINK = Regex("""instagram\.com/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""")
    private val client by lazy { OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build() }

    /** Instagram wants a session for this post: the UI opens the login page on the next shared link. */
    class LoginRequired : IOException("Instagram login needed: share the link again")

    fun isId(vid: String) = vid.startsWith(PREFIX)

    /** `ig_<shortcode>` for the first Instagram post link in [text] (a share-sheet text), else null. */
    fun idFromText(text: String): String? = LINK.find(text)?.groupValues?.get(1)?.let { PREFIX + it }

    fun url(vid: String) = "https://www.instagram.com/reel/${vid.removePrefix(PREFIX)}/"

    /** The post's numeric media id: a shortcode is that number in a base-64 alphabet (anything past 11 chars is a private-post suffix). */
    fun pk(shortcode: String): Long = shortcode.take(11).fold(0L) { n, c ->
        val i = ALPHABET.indexOf(c)
        if (i < 0) throw IOException("bad shortcode $shortcode")
        n * 64 + i
    }

    fun resolve(vid: String, cookie: String?): YtAudio.Info {
        val b = okhttp3.Request.Builder().url("https://www.instagram.com/api/v1/media/${pk(vid.removePrefix(PREFIX))}/info/")
            .header("User-Agent", UA).header("x-ig-app-id", APP_ID)
        if (cookie != null) {
            b.header("Cookie", cookie)
            Regex("""csrftoken=([^;]+)""").find(cookie)?.let { b.header("x-csrftoken", it.groupValues[1]) }
        }
        val body = client.newCall(b.build()).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw LoginRequired()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} from Instagram")
            r.body?.string() ?: throw IOException("empty reply from Instagram")
        }
        if (!body.startsWith("{")) throw LoginRequired() // the login page's HTML after a redirect
        return parse(vid, body)
    }

    /** The largest video version (they all carry the same AAC track) and the best name the post offers. */
    internal fun parse(vid: String, json: String): YtAudio.Info {
        val root = JSONObject(json)
        if (root.optString("message") == "login_required") throw LoginRequired()
        val item = root.getJSONArray("items").getJSONObject(0)
        val slides = item.optJSONArray("carousel_media")?.let { a -> List(a.length(), a::getJSONObject) } ?: emptyList()
        val video = (listOf(item) + slides).firstOrNull { it.has("video_versions") } ?: throw IOException("no video in this post")
        val versions = video.getJSONArray("video_versions")
        val best = List(versions.length(), versions::getJSONObject).maxByOrNull { it.optInt("height") * it.optInt("width") }!!
        val clips = item.optJSONObject("clips_metadata")
        val music = clips?.optJSONObject("music_info")?.optJSONObject("music_asset_info")
        val title = music?.let { m -> listOf(m.optString("display_artist"), m.optString("title")).filter(String::isNotBlank).joinToString(" - ") }?.ifBlank { null }
            ?: clips?.optJSONObject("original_sound_info")?.optString("original_audio_title")?.ifBlank { null }
            ?: item.optJSONObject("caption")?.optString("text")?.lineSequence()?.firstOrNull()?.trim()?.ifBlank { null }
            ?: item.optJSONObject("user")?.optString("username")?.ifBlank { null }?.let { "@$it ${vid.removePrefix(PREFIX)}" }
            ?: vid
        return YtAudio.Info(vid, title.take(120), (video.optDouble("video_duration", 0.0) * 1000).toLong(), best.getString("url"), "mp4", 0)
    }
}
