package com.prabhat.djmix.library

import android.content.Context
import com.prabhat.djmix.audio.Deck
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Per-track analysis, loudness gain and cues, one small JSON file per track key in filesDir/meta. */
object TrackMeta {
    /** [gainDb] is NaN when the track was analysed before loudness existed; the loader fills it in. */
    class Meta(val bpm: Double, val firstBeat: Double, val hotCues: DoubleArray, val gainDb: Double)

    private fun file(ctx: Context, key: String) = File(File(ctx.filesDir, "meta").apply { mkdirs() }, "$key.json")

    fun load(ctx: Context, key: String): Meta? {
        val f = file(ctx, key)
        if (!f.exists()) return null
        val j = JSONObject(f.readText())
        val hc = j.optJSONArray("hotCues")
        return Meta(j.optDouble("bpm", 0.0), j.optDouble("firstBeat", 0.0),
            DoubleArray(Deck.HOT_CUES) { i -> hc?.optDouble(i, -1.0) ?: -1.0 }, j.optDouble("gainDb", Double.NaN))
    }

    fun delete(ctx: Context, key: String) { file(ctx, key).delete() }

    fun exists(ctx: Context, key: String): Boolean = file(ctx, key).exists()

    /** Analysis only, no cues yet (the prep queue). */
    fun saveAnalysis(ctx: Context, key: String, bpm: Double, firstBeat: Double, gainDb: Double) {
        file(ctx, key).writeText(JSONObject().put("bpm", bpm).put("firstBeat", firstBeat).put("gainDb", gainDb).put("hotCues", JSONArray()).toString())
    }

    fun saveGain(ctx: Context, key: String, gainDb: Double) = merge(ctx, key) { put("gainDb", gainDb) }

    fun save(ctx: Context, key: String, deck: Deck) = merge(ctx, key) {
        put("bpm", deck.bpm).put("firstBeat", deck.firstBeat).put("hotCues", JSONArray(deck.hotCues.toList()))
    }

    /** Rewrites the file keeping every field the edit does not touch. */
    private fun merge(ctx: Context, key: String, edit: JSONObject.() -> Unit) {
        val f = file(ctx, key)
        val j = if (f.exists()) JSONObject(f.readText()) else JSONObject()
        j.edit()
        f.writeText(j.toString())
    }
}
