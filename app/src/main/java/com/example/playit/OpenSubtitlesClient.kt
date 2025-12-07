package com.example.playit

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object OpenSubtitlesClient {
    private const val BASE_URL = "https://api.opensubtitles.com/api/v1/"

    val instance: OpenSubtitlesService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        retrofit.create(OpenSubtitlesService::class.java)
    }
}
