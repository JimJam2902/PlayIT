package com.example.playit

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object OpenSubtitlesOrgClient {
    // API base URL for opensubtitles.org (community API - no auth required)
    private const val BASE_URL = "https://www.opensubtitles.org/api/v1/"

    val instance: OpenSubtitlesOrgService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        retrofit.create(OpenSubtitlesOrgService::class.java)
    }
}

