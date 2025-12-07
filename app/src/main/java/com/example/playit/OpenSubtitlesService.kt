package com.example.playit

import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.QueryMap
import retrofit2.http.Url

// --- Data Classes for API ---

data class LoginRequest(
    val username: String,
    val password: String
)

data class LoginResponse(
    val token: String,
    val user: UserInfo? = null
)

data class UserInfo(
    val user_id: Long? = null,
    val username: String? = null
)

data class SubtitleSearchResponse(
    val data: List<Subtitle>
)

data class Subtitle(
    val id: String,
    val attributes: SubtitleAttributes
)

data class SubtitleAttributes(
    @SerializedName("subtitle_id") val subtitleId: String,
    val language: String,
    @SerializedName("download_count") val downloadCount: Int,
    val files: List<SubtitleFile>
)

data class SubtitleFile(
    @SerializedName("file_id") val fileId: Long
)

data class DownloadRequest(
    @SerializedName("file_id") val fileId: Long
)

data class DownloadResponse(
    val link: String,
    @SerializedName("file_name") val fileName: String
)

// --- Retrofit Service Interface ---

/**
 * Retrofit interface for the OpenSubtitles API.
 * This should be used via OpenSubtitlesClient.
 */
interface OpenSubtitlesService {

    // NOTE: A real app needs proper credentials management.
    // The API key should be stored securely and not hardcoded.
    companion object {
        const val API_KEY_HEADER = "Api-Key"
        const val AUTH_HEADER = "Authorization"
    }

    @POST("login")
    suspend fun login(
        @Header(API_KEY_HEADER) apiKey: String,
        @Body loginRequest: LoginRequest
    ): Response<LoginResponse>

    @GET("subtitles")
    suspend fun search(
        @Header(API_KEY_HEADER) apiKey: String,
        @Header(AUTH_HEADER) authToken: String,
        @QueryMap options: Map<String, String>
    ): SubtitleSearchResponse

    @POST("download")
    suspend fun requestDownload(
        @Header(API_KEY_HEADER) apiKey: String,
        @Header(AUTH_HEADER) authToken: String,
        @Body downloadRequest: DownloadRequest
    ): DownloadResponse

    @GET
    suspend fun downloadSubtitleFile(@Url url: String): Response<ResponseBody>
}
