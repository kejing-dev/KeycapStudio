package io.embodit.keycapstudio.scene

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import io.embodit.keycapstudio.domain.CapRow
import io.embodit.keycapstudio.domain.Key
import io.embodit.keycapstudio.domain.StudioStore
import io.embodit.keycapstudio.domain.SwitchType
import io.embodit.keycapstudio.platform.KsLog
import kotlin.math.pow
import kotlin.random.Random

/**
 * Switch and UI sounds (design `assets.sounds`, `content.soundRules`). Every file peaks near −1 dBFS; layering is done
 * with playback gain (five-stones v0.1.5 template §15.2). Key presses are polyphonic (≥ 16 voices, design `rules.press`);
 * UI sounds keep one instance per kind.
 */
class SoundBank(context: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(20)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<String, Int>()
    private val variants = HashMap<SwitchType, List<Int>>()
    private var lastVariant = -1
    private val uiStreams = HashMap<String, Int>()

    init {
        val res = context.resources
        fun load(name: String): Int? {
            val rid = res.getIdentifier(name, "raw", context.packageName)
            if (rid == 0) { KsLog.w("SOUND missing raw/$name"); return null }
            return pool.load(context, rid, 1).also { ids[name] = it }
        }
        SwitchType.entries.forEach { t ->
            variants[t] = (1..3).mapNotNull { load("sw_${t.name.lowercase()}_$it") }
        }
        listOf("sw_space", "ui_swoosh", "ui_slide", "ui_tick").forEach { load(it) }
    }

    private fun gain(base: Float): Float = if (StudioStore.muted) 0f else (base * StudioStore.volume).coerceIn(0f, 1f)

    /** Rate for a key: space −4 semitones, ≥ 2u −2, F row +1, ±3 % random (design `soundRules`). */
    private fun rateFor(key: Key?): Float {
        var semis = 0f
        if (key != null) when {
            key.id == "KC_SPC" -> semis = -4f
            key.w >= 2f || key.h >= 2f -> semis = -2f
            key.row == CapRow.F -> semis = 1f
        }
        val r = 2f.pow(semis / 12f) * (1f + (Random.nextFloat() - 0.5f) * 0.06f)
        return r.coerceIn(0.5f, 2f)
    }

    /** Key reaches the 2 mm actuation point. */
    fun press(type: SwitchType, key: Key?) {
        val list = variants[type].orEmpty()
        if (list.isEmpty()) return
        var v = Random.nextInt(list.size)
        if (list.size > 1 && v == lastVariant) v = (v + 1) % list.size
        lastVariant = v
        val db = -Random.nextFloat() * 3f // 0 … −3 dB (never above the −1 dBFS file peak)
        val g = gain(if (type == SwitchType.SILENT) 0.6f else 1f) * 10f.pow(db / 20f)
        val sample = if (key?.id == "KC_SPC") ids["sw_space"] ?: list[v] else list[v]
        pool.play(sample, g, g, 1, 0, rateFor(key))
    }

    /** Upstroke: about −6 dB of a variant (−10 dB measured too quiet, loudness gate); clicky switches click again (design `switches.clicky`). */
    fun release(type: SwitchType, key: Key?) {
        val list = variants[type].orEmpty()
        if (list.isEmpty() || type == SwitchType.SILENT) return
        val g = gain(if (type == SwitchType.CLICKY) 0.6f else 0.5f)
        pool.play(list[(lastVariant + 1).mod(list.size)], g, g, 0, 0, (rateFor(key) * 1.12f).coerceAtMost(2f))
    }

    /** One instance per UI kind: a new one stops the previous (template §15.2). */
    fun ui(name: String, base: Float) {
        val id = ids[name] ?: return
        uiStreams[name]?.let { pool.stop(it) }
        val g = gain(base)
        uiStreams[name] = pool.play(id, g, g, 2, 0, 1f)
    }

    fun swoosh() = ui("ui_swoosh", 0.5f)
    fun slide() = ui("ui_slide", 0.45f)
    fun tick() = ui("ui_tick", 0.7f)

    fun stopAll() { uiStreams.values.forEach { pool.stop(it) }; uiStreams.clear(); pool.autoPause() }
    fun resume() = pool.autoResume()
    fun release() = pool.release()
}
