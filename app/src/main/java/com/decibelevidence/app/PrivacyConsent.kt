package com.decibelevidence.app

import android.content.SharedPreferences

internal class PrivacyConsentStore(private val preferences: SharedPreferences) {
    fun hasConsent(): Boolean = preferences.getInt("accepted_version", 0) == NOTICE_VERSION
    fun accept() { preferences.edit().putInt("accepted_version", NOTICE_VERSION).apply() }
    fun revoke() { preferences.edit().remove("accepted_version").apply() }

    companion object {
        const val NOTICE_VERSION = 1
    }
}