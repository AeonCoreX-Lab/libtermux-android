package com.libtermux.os.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.libtermux.os.distro.Distro
import com.libtermux.os.registry.DisplayResolution
import com.libtermux.os.registry.SupportedDistro
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.distroDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "libtermux_distro_settings")

/**
 * Per-distro runtime settings persisted via DataStore.
 *
 * These are user-editable overrides on top of the developer's
 * [SupportedDistro] declaration. For example, the developer may
 * declare HD_720P as default but the user can switch to FHD.
 *
 * Usage:
 * ```kotlin
 * val store = DistroSettingsStore(context)
 *
 * // Observe settings
 * store.getSettings(Distro.Kali).collect { settings ->
 *     println("Resolution: ${settings.displayWidth}x${settings.displayHeight}")
 * }
 *
 * // Update a setting
 * store.update(Distro.Kali) { it.copy(displayWidth = 1920, displayHeight = 1080) }
 * ```
 */
class DistroSettingsStore(private val context: Context) {

    /** Observe settings for [distro] as a Flow */
    fun getSettings(distro: Distro, default: SupportedDistro? = null): Flow<DistroRuntimeSettings> =
        context.distroDataStore.data.map { prefs ->
            val prefix = distro.id
            DistroRuntimeSettings(
                distroId      = distro.id,
                displayWidth  = prefs[intPreferencesKey("${prefix}_width")]
                                    ?: default?.effectiveWidth ?: 1280,
                displayHeight = prefs[intPreferencesKey("${prefix}_height")]
                                    ?: default?.effectiveHeight ?: 720,
                colorDepth    = prefs[intPreferencesKey("${prefix}_depth")]
                                    ?: default?.colorDepth ?: 24,
                vncPort       = prefs[intPreferencesKey("${prefix}_vncPort")]
                                    ?: default?.vncPort ?: 5901,
                vncPassword   = prefs[stringPreferencesKey("${prefix}_vncPass")]
                                    ?: default?.vncPassword ?: "",
                startupCmds   = prefs[stringPreferencesKey("${prefix}_startupCmds")]
                                    ?.split(";;")
                                    ?.filter { it.isNotBlank() }
                                    ?: default?.startupCommands ?: emptyList(),
                showToolbar   = prefs[booleanPreferencesKey("${prefix}_toolbar")] ?: true,
                scaleToFit    = prefs[booleanPreferencesKey("${prefix}_scale")] ?: true,
                vibrateMouse  = prefs[booleanPreferencesKey("${prefix}_vibrate")] ?: false,
            )
        }

    /** Update settings for [distro] */
    suspend fun update(
        distro: Distro,
        transform: (DistroRuntimeSettings) -> DistroRuntimeSettings,
    ) {
        // Read current, transform, write back
        var current = DistroRuntimeSettings(distroId = distro.id)
        context.distroDataStore.edit { prefs ->
            val prefix = distro.id
            current = current.copy(
                displayWidth  = prefs[intPreferencesKey("${prefix}_width")]     ?: current.displayWidth,
                displayHeight = prefs[intPreferencesKey("${prefix}_height")]    ?: current.displayHeight,
                colorDepth    = prefs[intPreferencesKey("${prefix}_depth")]     ?: current.colorDepth,
                vncPort       = prefs[intPreferencesKey("${prefix}_vncPort")]   ?: current.vncPort,
                vncPassword   = prefs[stringPreferencesKey("${prefix}_vncPass")]?: current.vncPassword,
            )
            val updated = transform(current)
            prefs[intPreferencesKey("${prefix}_width")]          = updated.displayWidth
            prefs[intPreferencesKey("${prefix}_height")]         = updated.displayHeight
            prefs[intPreferencesKey("${prefix}_depth")]          = updated.colorDepth
            prefs[intPreferencesKey("${prefix}_vncPort")]        = updated.vncPort
            prefs[stringPreferencesKey("${prefix}_vncPass")]     = updated.vncPassword
            prefs[stringPreferencesKey("${prefix}_startupCmds")] = updated.startupCmds.joinToString(";;")
            prefs[booleanPreferencesKey("${prefix}_toolbar")]    = updated.showToolbar
            prefs[booleanPreferencesKey("${prefix}_scale")]      = updated.scaleToFit
            prefs[booleanPreferencesKey("${prefix}_vibrate")]    = updated.vibrateMouse
        }
    }

    /** Reset all settings for [distro] to defaults */
    suspend fun reset(distro: Distro) {
        val prefix = distro.id
        context.distroDataStore.edit { prefs ->
            prefs.remove(intPreferencesKey("${prefix}_width"))
            prefs.remove(intPreferencesKey("${prefix}_height"))
            prefs.remove(intPreferencesKey("${prefix}_depth"))
            prefs.remove(intPreferencesKey("${prefix}_vncPort"))
            prefs.remove(stringPreferencesKey("${prefix}_vncPass"))
            prefs.remove(stringPreferencesKey("${prefix}_startupCmds"))
            prefs.remove(booleanPreferencesKey("${prefix}_toolbar"))
            prefs.remove(booleanPreferencesKey("${prefix}_scale"))
            prefs.remove(booleanPreferencesKey("${prefix}_vibrate"))
        }
    }
}

/**
 * Runtime-editable settings for a single distro session.
 */
data class DistroRuntimeSettings(
    val distroId: String,
    val displayWidth: Int       = 1280,
    val displayHeight: Int      = 720,
    val colorDepth: Int         = 24,
    val vncPort: Int            = 5901,
    val vncPassword: String     = "",
    val startupCmds: List<String> = emptyList(),
    val showToolbar: Boolean    = true,
    val scaleToFit: Boolean     = true,
    val vibrateMouse: Boolean   = false,
) {
    val resolutionLabel: String get() = "${displayWidth}×${displayHeight}"

    fun matchesPreset(preset: DisplayResolution): Boolean =
        preset != DisplayResolution.CUSTOM &&
        displayWidth == preset.width && displayHeight == preset.height
}
