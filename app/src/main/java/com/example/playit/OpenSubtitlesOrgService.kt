package com.example.playit

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.QueryMap

/**
 * Retrofit service for OpenSubtitles.org (community API)
 * This API doesn't require authentication and is simpler to use
 * Documentation: https://www.opensubtitles.org/en/ssearch
 */
interface OpenSubtitlesOrgService {

    /**
     * Search for subtitles on opensubtitles.org
     * No authentication required
     */
    @GET("search")
    suspend fun search(
        @QueryMap options: Map<String, String>
    ): OpenSubtitlesOrgSearchResponse
}

data class OpenSubtitlesOrgSearchResponse(
    val data: List<OpenSubtitlesOrgSubtitle>? = null
)

data class OpenSubtitlesOrgSubtitle(
    val id: String,
    val attributes: OpenSubtitlesOrgAttributes
)

data class OpenSubtitlesOrgAttributes(
    val name: String,
    val language: String,
    @SerializedName("upload_count") val uploadCount: Int = 0,
    @SerializedName("download_count") val downloadCount: Int = 0,
    val files: List<OpenSubtitlesOrgFile> = emptyList()
)

data class OpenSubtitlesOrgFile(
    val file_id: Long? = null,
    @SerializedName("file_name") val fileName: String? = null
)

