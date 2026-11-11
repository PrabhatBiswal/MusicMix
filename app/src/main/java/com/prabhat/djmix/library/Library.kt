package com.prabhat.djmix.library

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Downloaded tracks: filesDir/library/<vid>.<ext> plus a <vid>.json index entry. Playlists are folders over the same
 * files: each entry lists the playlists it belongs to; names live in prefs so an empty playlist survives too.
 */
object Library {
    /** [stems] = a vocal and an instrumental FLAC sit next to the file (see [Stems]); [addedAt] = when the entry was written. */
    class Entry(val vid: String, val title: String, val durationMs: Long, val file: File, val lists: List<String> = emptyList(), val stems: Boolean = false, val addedAt: Long = 0)

    fun dir(ctx: Context): File = File(ctx.filesDir, "library").apply { mkdirs() }
    fun indexFile(ctx: Context, vid: String) = File(dir(ctx), "$vid.json")

    fun list(ctx: Context): List<Entry> =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { read(ctx, it) }
            ?.sortedBy { it.title.lowercase() } ?: emptyList()

    fun read(ctx: Context, index: File): Entry? = runCatching {
        val j = JSONObject(index.readText())
        val file = File(dir(ctx), j.getString("file"))
        val lists = j.optJSONArray("lists")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        if (file.exists()) Entry(j.getString("vid"), j.getString("title"), j.getLong("durationMs"), file, lists, stemFiles(ctx, j.getString("vid")) != null, index.lastModified()) else null
    }.getOrNull()

    /** Vocal and instrumental files for [vid], when both exist. */
    fun stemFiles(ctx: Context, vid: String): Pair<File, File>? {
        val v = Stems.voxFile(dir(ctx), vid); val i = Stems.instFile(dir(ctx), vid)
        return if (v.exists() && i.exists()) v to i else null
    }

    /** Removes the audio, its index entry, stems, saved cues and the decoded cache. The cloud copy stays. */
    fun delete(ctx: Context, e: Entry) {
        e.file.delete()
        Stems.voxFile(dir(ctx), e.vid).delete(); Stems.instFile(dir(ctx), e.vid).delete()
        File(ctx.cacheDir, "${e.vid}.vox.pcm").delete(); File(ctx.cacheDir, "${e.vid}.inst.pcm").delete()
        indexFile(ctx, e.vid).delete()
        TrackMeta.delete(ctx, e.vid)
        File(ctx.cacheDir, "${e.vid}.pcm").delete()
    }

    fun add(ctx: Context, vid: String, title: String, durationMs: Long, audio: File): Entry {
        indexFile(ctx, vid).writeText(
            JSONObject().put("vid", vid).put("title", title).put("durationMs", durationMs).put("file", audio.name).toString()
        )
        return Entry(vid, title, durationMs, audio)
    }

    // ---- playlists ---------------------------------------------------------------------------------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)

    /** Every playlist name: the ones created plus any a track mentions. */
    fun playlists(ctx: Context, entries: List<Entry> = list(ctx)): List<String> =
        (prefs(ctx).getStringSet("playlists", emptySet())!! + entries.flatMap { it.lists }).sortedBy { it.lowercase() }

    fun addPlaylist(ctx: Context, name: String) {
        prefs(ctx).edit().putStringSet("playlists", (playlists(ctx) + name).toSet()).apply()
    }

    /** Rewrites the track's own index entry with its playlist membership. */
    fun setLists(ctx: Context, vid: String, lists: Collection<String>) {
        val f = indexFile(ctx, vid)
        if (!f.exists()) return
        f.writeText(JSONObject(f.readText()).put("lists", JSONArray(lists.toList())).toString())
    }
}
