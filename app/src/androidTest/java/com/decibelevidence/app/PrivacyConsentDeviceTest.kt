package com.decibelevidence.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyConsentDeviceTest {
    private val preferences = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("privacy_consent_test", Context.MODE_PRIVATE)

    @Before fun clearBefore() { preferences.edit().clear().commit() }
    @After fun clearAfter() { preferences.edit().clear().commit() }

    @Test fun defaultStateRequiresExplicitConsent() {
        assertFalse(PrivacyConsentStore(preferences).hasConsent())
    }

    @Test fun acceptanceSurvivesStoreRecreation() {
        PrivacyConsentStore(preferences).accept()
        assertTrue(PrivacyConsentStore(preferences).hasConsent())
    }

    @Test fun earlierNoticeVersionRequiresFreshConsent() {
        preferences.edit().putInt("accepted_version", PrivacyConsentStore.NOTICE_VERSION - 1).commit()
        assertFalse(PrivacyConsentStore(preferences).hasConsent())
    }

    @Test fun revocationPersistsAndRequiresAcceptanceAgain() {
        val store = PrivacyConsentStore(preferences)
        store.accept()
        store.revoke()
        assertFalse(PrivacyConsentStore(preferences).hasConsent())
        store.accept()
        assertTrue(PrivacyConsentStore(preferences).hasConsent())
    }
}