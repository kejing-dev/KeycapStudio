package io.embodit.keycapstudio.domain

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import io.embodit.keycapstudio.platform.KsLog
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One saved scheme in the list (design `panels.schemes`). */
data class SavedScheme(val id: String, val name: String, val layout: Int, val savedAt: Long, val thumb: File)

enum class Lang { SYSTEM, ZH, EN }

/** App settings (not part of a scheme). */
data class Settings(val lang: Lang = Lang.SYSTEM, val volume: Float = 1.0f, val muted: Boolean = false, val defaultTilt: Float = 7f, val coachSeen: Boolean = false)

/**
 * Local persistence (design `persistence`): files/current.json (autosave), files/schemes/<uuid>.json + .png (≤ 50),
 * files/import/ (picture copies). Exports go to Download/KeycapStudio/ through MediaStore; imports are read from the
 * app's external files folder `import/` (adb push / file manager), see ui-audit for why not the photo picker.
 */
class Repo(private val context: Context) {
    private val files = context.filesDir
    private val schemesDir = File(files, "schemes").apply { mkdirs() }
    val importCopies = File(files, "import").apply { mkdirs() }
    /** Where the user drops pictures / JSON to import: /sdcard/Android/data/<pkg>/files/import */
    val inbox: File get() = File(context.getExternalFilesDir(null), "import").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun loadSettings() = Settings(
        lang = runCatching { Lang.valueOf(prefs.getString("lang", "SYSTEM")!!) }.getOrDefault(Lang.SYSTEM),
        volume = prefs.getFloat("volume", 1.0f), muted = prefs.getBoolean("muted", false),
        defaultTilt = prefs.getFloat("tilt", 7f), coachSeen = prefs.getBoolean("coach", false),
    )

    fun saveSettings(s: Settings) = prefs.edit().putString("lang", s.lang.name).putFloat("volume", s.volume).putBoolean("muted", s.muted)
        .putFloat("tilt", s.defaultTilt).putBoolean("coach", s.coachSeen).apply()

    fun loadCurrent(): Scheme? = runCatching { Scheme.fromJson(JSONObject(File(files, "current.json").readText())) }
        .onFailure { if (File(files, "current.json").exists()) KsLog.w("current.json unreadable: $it") }.getOrNull()

    /** Atomic write: temp file, then rename. */
    fun saveCurrent(s: Scheme) = write(File(files, "current.json"), s.toJson().toString())

    private fun write(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun list(): List<SavedScheme> = schemesDir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
        runCatching {
            val o = JSONObject(f.readText())
            SavedScheme(f.nameWithoutExtension, o.optString("name"), o.optInt("layout", 87), o.optLong("savedAt", f.lastModified()), File(schemesDir, f.nameWithoutExtension + ".png"))
        }.getOrNull()
    }.sortedByDescending { it.savedAt }

    /** Returns null when the list is full (50, design `rules.save`). [id] overwrites an existing entry. */
    fun save(s: Scheme, thumb: Bitmap, id: String? = null): String? {
        if (id == null && list().size >= MAX_SCHEMES) return null
        val uid = id ?: UUID.randomUUID().toString()
        write(File(schemesDir, "$uid.json"), s.toJson().put("savedAt", System.currentTimeMillis()).toString())
        File(schemesDir, "$uid.png").outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return uid
    }

    fun load(id: String): Scheme = Scheme.fromJson(JSONObject(File(schemesDir, "$id.json").readText()))

    fun delete(id: String) { File(schemesDir, "$id.json").delete(); File(schemesDir, "$id.png").delete() }

    /** Writes into Download/KeycapStudio/ (MediaStore, no permission). Returns the visible path. */
    fun export(name: String, mime: String, bytes: ByteArray): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/KeycapStudio")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        return "Download/KeycapStudio/$name"
    }

    /** Newest *.json in the inbox, parsed (throws on bad content). */
    fun importJson(): Pair<String, Scheme>? {
        val f = inbox.listFiles { x -> x.extension.equals("json", true) }.orEmpty().maxByOrNull { it.lastModified() } ?: return null
        return f.name to Scheme.fromJson(JSONObject(f.readText()))
    }

    /** Newest jpg / png in the inbox → scaled to ≤ 2048 → private copy. Returns the copy's file name. */
    fun importImage(): String? {
        val f = inbox.listFiles { x -> x.extension.lowercase() in setOf("jpg", "jpeg", "png") }.orEmpty().maxByOrNull { it.lastModified() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192) { "size" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("decode")
        val out = "img_${System.currentTimeMillis()}.jpg"
        File(importCopies, out).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        return out
    }

    companion object {
        const val MAX_SCHEMES = 50
    }
}
