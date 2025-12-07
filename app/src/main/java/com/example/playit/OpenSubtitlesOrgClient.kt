package com.example.playit

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Retrofit client for OpenSubtitles.org community API
 * No authentication required, simpler API than api.opensubtitles.com
 */
object OpenSubtitlesOrgClient {
    // Using the community search API endpoint
    private const val SEARCH_BASE_URL = "https://www.opensubtitles.org/en/search"

    val instance: OpenSubtitlesOrgService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(SEARCH_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        retrofit.create(OpenSubtitlesOrgService::class.java)
    }
}

