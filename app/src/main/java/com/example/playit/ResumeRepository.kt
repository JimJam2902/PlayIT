package com.example.playit

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "resume_points")

class ResumeRepository(private val context: Context) {

    suspend fun getResumePosition(mediaId: String): Long {
        val key = longPreferencesKey(mediaId)
        val position = context.dataStore.data.map { preferences ->
            preferences[key] ?: 0L
        }.first()
        android.util.Log.d("ResumeRepository", "getResumePosition: mediaId=$mediaId position=$position")
        return position
    }

    suspend fun saveResumePosition(mediaId: String, position: Long) {
        val key = longPreferencesKey(mediaId)
        context.dataStore.edit { settings ->
            settings[key] = position
        }
        android.util.Log.d("ResumeRepository", "saveResumePosition: mediaId=$mediaId position=$position")
    }

    // Try multiple key variants to find a saved resume position (greater than zero).
    suspend fun getBestResumePosition(mediaId: String): Long {
        val candidates = mutableListOf<String>()
        candidates.add(mediaId)
        try {
            // Strip query parameters
            val noQuery = mediaId.substringBefore('?')
            if (noQuery != mediaId) candidates.add(noQuery)

            // URL-decode last path segment (filename)
            val decoded = java.net.URLDecoder.decode(mediaId, "UTF-8")
            if (decoded != mediaId) candidates.add(decoded)

            val lastSegment = decoded.substringAfterLast('/').takeIf { it.isNotEmpty() }
            if (lastSegment != null && lastSegment != decoded) candidates.add(lastSegment)

            // Add SHA-256 hash of mediaId as a fallback key (some hosts use hashed ids)
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val hash = md.digest(mediaId.toByteArray()).joinToString("") { "%02x".format(it) }
            candidates.add(hash)
        } catch (e: Exception) {
            // ignore: fall back to original only
        }

        // Read all prefs once and check candidates in order
        val prefs = context.dataStore.data.map { it }.first()
        for (c in candidates) {
            val key = longPreferencesKey(c)
            val pos = prefs[key] ?: 0L
            android.util.Log.d("ResumeRepository", "getBestResumePosition: trying key='$c' pos=$pos")
            if (pos > 0L) {
                android.util.Log.d("ResumeRepository", "getBestResumePosition: matched key='$c' pos=$pos")
                return pos
            }
        }
        return 0L
    }
}