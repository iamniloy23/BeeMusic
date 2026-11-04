package com.example.musicplayer

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer

/** The service owns the audio session; the UI (same process) tunes it through this object. */
object EqController {
    var eq: Equalizer? = null
        private set
    var bass: BassBoost? = null
        private set
    private var prefs: SharedPreferences? = null

    fun attach(ctx: Context, session: Int) {
        release()
        val p = ctx.applicationContext.getSharedPreferences("eq", Context.MODE_PRIVATE)
        prefs = p
        val on = p.getBoolean("on", false)
        try {
            eq = Equalizer(0, session).also { q ->
                p.getString("levels", null)?.split(",")?.forEachIndexed { i, s ->
                    if (i < q.numberOfBands) s.toShortOrNull()?.let { q.setBandLevel(i.toShort(), it) }
                }
                q.setEnabled(on)
            }
        } catch (e: Exception) { eq = null }
        try {
            bass = BassBoost(0, session).also { b ->
                if (b.strengthSupported) b.setStrength(p.getInt("bass", 0).toShort())
                b.setEnabled(on)
            }
        } catch (e: Exception) { bass = null }
    }

    fun setOn(on: Boolean) {
        eq?.setEnabled(on); bass?.setEnabled(on)
        prefs?.edit()?.putBoolean("on", on)?.apply()
    }
    fun setBand(i: Int, level: Int) { eq?.setBandLevel(i.toShort(), level.toShort()); saveLevels() }
    fun usePreset(i: Int) { eq?.usePreset(i.toShort()); saveLevels() }
    fun setBass(v: Int) { bass?.setStrength(v.toShort()); prefs?.edit()?.putInt("bass", v)?.apply() }

    private fun saveLevels() {
        val q = eq ?: return
        val s = (0 until q.numberOfBands).joinToString(",") { q.getBandLevel(it.toShort()).toString() }
        prefs?.edit()?.putString("levels", s)?.apply()
    }

    fun release() {
        try { eq?.release(); bass?.release() } catch (e: Exception) { }
        eq = null; bass = null
    }
}
