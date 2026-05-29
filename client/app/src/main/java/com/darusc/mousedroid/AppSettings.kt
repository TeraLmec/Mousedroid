package com.darusc.mousedroid

import android.content.Context

object AppSettings {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_VOLUME_BUTTONS_CONTROL_PC = "volume_buttons_control_pc"

    fun volumeButtonsControlPc(context: Context): Boolean {
        return context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VOLUME_BUTTONS_CONTROL_PC, false)
    }

    fun setVolumeButtonsControlPc(context: Context, enabled: Boolean) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VOLUME_BUTTONS_CONTROL_PC, enabled)
            .apply()
    }
}
