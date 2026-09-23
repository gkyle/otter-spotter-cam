package com.kylescholz.otterspotter

import android.content.Context
import android.content.SharedPreferences

data class OtterUser(
    val id: String,
    val email: String,
    val displayName: String,
    val photoUrl: String? = null,
    val idToken: String? = null,
    val isGuest: Boolean = false
)

object AuthManager {
    private const val PREFS_NAME = "otter_auth_prefs"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_IS_GUEST = "is_guest"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_EMAIL = "email"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_PHOTO_URL = "photo_url"
    private const val KEY_ID_TOKEN = "id_token"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isLoggedIn(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_LOGGED_IN, false)
    }

    fun isGuest(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_GUEST, false)
    }

    fun getUser(context: Context): OtterUser? {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_IS_LOGGED_IN, false)) return null

        val isGuest = prefs.getBoolean(KEY_IS_GUEST, false)
        return OtterUser(
            id = prefs.getString(KEY_USER_ID, if (isGuest) "guest" else "") ?: "",
            email = prefs.getString(KEY_EMAIL, if (isGuest) "Offline Guest" else "") ?: "",
            displayName = prefs.getString(KEY_DISPLAY_NAME, if (isGuest) "Guest Field Biologist" else "") ?: "",
            photoUrl = prefs.getString(KEY_PHOTO_URL, null),
            idToken = prefs.getString(KEY_ID_TOKEN, null),
            isGuest = isGuest
        )
    }

    fun saveUser(context: Context, user: OtterUser) {
        getPrefs(context).edit().apply {
            putBoolean(KEY_IS_LOGGED_IN, true)
            putBoolean(KEY_IS_GUEST, user.isGuest)
            putString(KEY_USER_ID, user.id)
            putString(KEY_EMAIL, user.email)
            putString(KEY_DISPLAY_NAME, user.displayName)
            putString(KEY_PHOTO_URL, user.photoUrl)
            putString(KEY_ID_TOKEN, user.idToken)
            apply()
        }
    }

    fun setGuestMode(context: Context) {
        val guest = OtterUser(
            id = "guest_${System.currentTimeMillis()}",
            email = "offline_field_mode@otterspotter.local",
            displayName = "Guest Researcher",
            photoUrl = null,
            idToken = null,
            isGuest = true
        )
        saveUser(context, guest)
    }

    fun signOut(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
