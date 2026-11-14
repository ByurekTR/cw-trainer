package com.cwtrainer.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cwtrainer.core.koch.Koch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

data class Settings(
    val charWpm: Int = 20,
    /** 0 = automatic (follows the Koch level); otherwise a fixed effective speed. */
    val effOverride: Float = 0f,
    val pitchHz: Int = 600,
    val groupLength: Int = 5,
    val sessionMinutes: Int = 5,
    val band: String = "Clean",
    val fist: Boolean = false,
    val haptics: Boolean = true,
    val kochLevel: Int = Koch.MIN_LEVEL,
    val sendIambic: Boolean = false,
    val sendWpm: Int = 15,
    val swapPaddles: Boolean = false,
    val lastDrill: String = "",
) {
    /** Effective speed actually used for copy drills. */
    fun effectiveWpm(level: Int = kochLevel): Double =
        if (effOverride > 0f) effOverride.toDouble().coerceAtMost(charWpm.toDouble())
        else Koch.effectiveWpm(level, charWpm.toDouble())
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object K {
        val charWpm = intPreferencesKey("char_wpm")
        val effOverride = floatPreferencesKey("eff_override")
        val pitch = intPreferencesKey("pitch_hz")
        val group = intPreferencesKey("group_length")
        val minutes = intPreferencesKey("session_minutes")
        val band = stringPreferencesKey("band")
        val fist = booleanPreferencesKey("fist")
        val haptics = booleanPreferencesKey("haptics")
        val level = intPreferencesKey("koch_level")
        val iambic = booleanPreferencesKey("send_iambic")
        val sendWpm = intPreferencesKey("send_wpm")
        val swap = booleanPreferencesKey("swap_paddles")
        val lastDrill = stringPreferencesKey("last_drill")
    }

    val flow: Flow<Settings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { read(it) }

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val s = transform(read(p))
            p[K.charWpm] = s.charWpm.coerceIn(15, 30)
            p[K.effOverride] = s.effOverride
            p[K.pitch] = s.pitchHz.coerceIn(400, 900)
            p[K.group] = s.groupLength.coerceIn(2, 8)
            p[K.minutes] = s.sessionMinutes.coerceIn(5, 30)
            p[K.band] = s.band
            p[K.fist] = s.fist
            p[K.haptics] = s.haptics
            p[K.level] = Koch.clamp(s.kochLevel)
            p[K.iambic] = s.sendIambic
            p[K.sendWpm] = s.sendWpm.coerceIn(5, 35)
            p[K.swap] = s.swapPaddles
            p[K.lastDrill] = s.lastDrill
        }
    }

    private fun read(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            charWpm = p[K.charWpm] ?: d.charWpm,
            effOverride = p[K.effOverride] ?: d.effOverride,
            pitchHz = p[K.pitch] ?: d.pitchHz,
            groupLength = p[K.group] ?: d.groupLength,
            sessionMinutes = p[K.minutes] ?: d.sessionMinutes,
            band = p[K.band] ?: d.band,
            fist = p[K.fist] ?: d.fist,
            haptics = p[K.haptics] ?: d.haptics,
            kochLevel = p[K.level] ?: d.kochLevel,
            sendIambic = p[K.iambic] ?: d.sendIambic,
            sendWpm = p[K.sendWpm] ?: d.sendWpm,
            swapPaddles = p[K.swap] ?: d.swapPaddles,
            lastDrill = p[K.lastDrill] ?: d.lastDrill,
        )
    }
}
