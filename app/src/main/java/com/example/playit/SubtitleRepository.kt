package com.example.playit

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream

// Gemini-fixed: Moved download/file logic from ViewModel into the repository.
class SubtitleRepository(private val context: Context) {

    private val service: OpenSubtitlesService = OpenSubtitlesClient.instance

    // API selection - users can switch between .com and .org
    enum class ApiProvider { COM, ORG }

    // Get API provider preference - default to ORG (no auth needed)
    private fun getApiProvider(): ApiProvider {
        val provider = settingsRepo.getOpenSubtitlesProvider()
        return if (provider == "COM") ApiProvider.COM else ApiProvider.ORG
    }

    // Get API key - prefer custom saved key, fallback to default
    private fun getApiKey(): String {
        val customKey = settingsRepo.getOpenSubtitlesApiKey()
        return if (customKey?.isNotEmpty() == true) customKey else DEFAULT_API_KEY
    }

    companion object {
        // Default API key - get your own at https://www.opensubtitles.com/api
        private const val DEFAULT_API_KEY = "iSFvVbtm0AEl9yODbmIBSy3uTan2X0Aq"
    }

    private var authToken: String? = null

    // Login status exposed to UI
    enum class LoginStatus { UNKNOWN, LOGGED_OUT, LOGGING_IN, LOGGED_IN, FAILED }
    private val _loginStatus = MutableStateFlow(LoginStatus.UNKNOWN)
    val loginStatus: StateFlow<LoginStatus> = _loginStatus.asStateFlow()

    // Settings repository reads encrypted credentials saved by the settings UI
    private val settingsRepo = SettingsRepository(context)

    private suspend fun ensureLoggedIn() {
        val provider = getApiProvider()

        // If using OpenSubtitles.org, no login needed
        if (provider == ApiProvider.ORG) {
            _loginStatus.value = LoginStatus.LOGGED_IN
            Log.d("SubtitleRepository", "Using OpenSubtitles.org API (no authentication required)")
            return
        }

        // For .com API, require login
        // If we already have a token, assume logged in
        if (!authToken.isNullOrEmpty()) {
            _loginStatus.value = LoginStatus.LOGGED_IN
            Log.d("SubtitleRepository", "Already logged in, using cached token")
            return
        }

        val username = settingsRepo.getOpenSubtitlesUsername()?.trim() ?: ""
        val password = settingsRepo.getOpenSubtitlesPassword() ?: ""

        // Add logging to see what credentials are being used
        Log.d("SubtitleRepository", "Attempting login with username: '$username', password length: ${password.length}")

        if (username.isEmpty()) {
            Log.e("SubtitleRepository", "Cannot login: username is empty")
            _loginStatus.value = LoginStatus.LOGGED_OUT
            return
        }

        if (password.isEmpty()) {
            Log.e("SubtitleRepository", "Cannot login: password is empty")
            _loginStatus.value = LoginStatus.LOGGED_OUT
            return
        }

        _loginStatus.value = LoginStatus.LOGGING_IN
        try {
            val apiKey = getApiKey()

            // Validate API key format
            if (apiKey.length < 20) {
                Log.e("SubtitleRepository", "API key appears too short (${apiKey.length} chars). Please check your API key.")
                _loginStatus.value = LoginStatus.FAILED
                return
            }

            Log.d("SubtitleRepository", "Using API key: ${apiKey.take(15)}... (${apiKey.length} chars)")
            Log.d("SubtitleRepository", "Sending login request to OpenSubtitles API")
            val response = withTimeoutOrNull(10_000L) {
                service.login(apiKey, LoginRequest(username, password))
            }

            if (response == null) {
                Log.e("SubtitleRepository", "OpenSubtitles login timed out (10 seconds)")
                _loginStatus.value = LoginStatus.FAILED
                authToken = null
                return
            }

            // Check if HTTP response was successful
            if (!response.isSuccessful) {
                Log.e("SubtitleRepository", "OpenSubtitles login failed with HTTP ${response.code()}: ${response.message()}")

                // Log the error body for debugging
                try {
                    val errorBody = response.errorBody()?.string()
                    Log.e("SubtitleRepository", "Error response body: $errorBody")
                } catch (e: Exception) {
                    Log.e("SubtitleRepository", "Could not read error body: ${e.message}")
                }

                if (response.code() == 401 || response.code() == 403) {
                    Log.e("SubtitleRepository", "Invalid credentials or API key - check username/password/API key")
                }
                _loginStatus.value = LoginStatus.FAILED
                authToken = null
                return
            }

            val loginResp = response.body()
            if (loginResp == null) {
                Log.e("SubtitleRepository", "Login response body is null")
                _loginStatus.value = LoginStatus.FAILED
                authToken = null
                return
            }

            Log.d("SubtitleRepository", "Login response received, token: ${loginResp.token.take(20)}...")

            // Check if token is empty
            if (loginResp.token.isEmpty()) {
                Log.e("SubtitleRepository", "Login response returned empty token")
                _loginStatus.value = LoginStatus.FAILED
                authToken = null
                return
            }

            authToken = "Bearer ${loginResp.token}"
            _loginStatus.value = LoginStatus.LOGGED_IN
            Log.d("SubtitleRepository", "Successfully logged in to OpenSubtitles as $username")
        } catch (ex: Exception) {
            Log.e("SubtitleRepository", "OpenSubtitles login failed: ${ex.message}", ex)
            // Print full stack trace for debugging
            ex.printStackTrace()
            // Keep authToken null on failure
            authToken = null
            _loginStatus.value = LoginStatus.FAILED
        }
    }

    /**
     * Attempts to login using currently saved credentials and updates loginStatus.
     * Returns true on success, false otherwise.
     */
    suspend fun testLogin(): Boolean {
        Log.d("SubtitleRepository", "testLogin() called")
        // Clear any cached token so ensureLoggedIn will attempt fresh login
        authToken = null
        _loginStatus.value = LoginStatus.LOGGING_IN
        return try {
            ensureLoggedIn()
            val success = _loginStatus.value == LoginStatus.LOGGED_IN
            Log.d("SubtitleRepository", "testLogin result: success=$success, status=${_loginStatus.value}")
            success
        } catch (e: Exception) {
            Log.e("SubtitleRepository", "testLogin exception", e)
            _loginStatus.value = LoginStatus.FAILED
            false
        }
    }

    /**
     * Searches for subtitles, downloads the best-rated one, and saves it to a temp file.
     * @return The File object of the downloaded subtitle, or null on failure.
     */
    suspend fun searchAndDownloadBestSubtitle(
        query: String,
        language: String = "en",
        tmdbId: String?,
        year: Int?
    ): File? = withContext(Dispatchers.IO) {
        ensureLoggedIn()

        val apiKey = getApiKey()
        val searchParams = mutableMapOf("query" to query, "languages" to language)
        tmdbId?.let { searchParams["tmdb_id"] = it }
        year?.let { searchParams["year"] = it.toString() }

        // 1. Search for subtitles
        val searchResults = try {
            service.search(apiKey, authToken ?: "", searchParams).data
        } catch (e: Exception) {
            Log.e("SubtitleRepository", "Subtitle search failed", e)
            return@withContext null
        }

        // 2. Find the best subtitle to download (e.g., highest download count)
        val bestSubtitle = searchResults.maxByOrNull { it.attributes.downloadCount }
        val fileId = bestSubtitle?.attributes?.files?.firstOrNull()?.fileId ?: return@withContext null

        // 3. Request the download link
        val downloadLink = try {
            val downloadRequest = DownloadRequest(fileId)
            service.requestDownload(apiKey, authToken ?: "", downloadRequest).link
        } catch (e: Exception) {
            Log.e("SubtitleRepository", "Requesting download link failed", e)
            return@withContext null
        }

        // 4. Download the actual file and save it
        return@withContext try {
            val response = service.downloadSubtitleFile(downloadLink)
            val body = response.body()?.byteStream() ?: return@withContext null

            val tempFile = File.createTempFile("subtitle", ".srt", context.cacheDir)
            FileOutputStream(tempFile).use { output ->
                body.copyTo(output)
            }
            Log.d("SubtitleRepository", "Subtitle downloaded to ${tempFile.path}")
            tempFile
        } catch (e: Exception) {
            Log.e("SubtitleRepository", "File download failed", e)
            null
        }
    }

    // New: Search for subtitles and return raw results to the caller for selection.
    suspend fun searchSubtitles(
        query: String,
        language: String = "en",
        tmdbId: String? = null,
        year: Int? = null
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val provider = getApiProvider()
        Log.d("SubtitleRepository", "searchSubtitles: provider=$provider, query='$query', language=$language")

        ensureLoggedIn()

        try {
            return@withContext when (provider) {
                ApiProvider.ORG -> {
                    // Use OpenSubtitles.org API (no authentication needed)
                    Log.d("SubtitleRepository", "Using OpenSubtitles.org API for search")
                    val orgService = OpenSubtitlesOrgClient.instance
                    val searchParams = mutableMapOf("query" to query, "languages" to language)
                    tmdbId?.let { searchParams["tmdb_id"] = it }
                    year?.let { searchParams["year"] = it.toString() }

                    Log.d("SubtitleRepository", "Searching with params: $searchParams")
                    val response = orgService.search(searchParams)
                    Log.d("SubtitleRepository", "OpenSubtitles.org search response: ${response.data?.size ?: 0} results")

                    response.data?.mapNotNull { orgSubtitle ->
                        // Convert OpenSubtitlesOrgSubtitle to Subtitle format
                        Subtitle(
                            id = orgSubtitle.id,
                            attributes = SubtitleAttributes(
                                name = orgSubtitle.attributes.name,
                                language = orgSubtitle.attributes.language,
                                downloadCount = orgSubtitle.attributes.downloadCount,
                                uploadCount = orgSubtitle.attributes.uploadCount,
                                files = orgSubtitle.attributes.files.mapNotNull { file ->
                                    // Extract file ID from file_id field
                                    file.file_id?.let { fileId ->
                                        SubtitleFile(fileId = fileId, fileName = file.fileName)
                                    }
                                }
                            )
                        )
                    } ?: emptyList()
                }
                ApiProvider.COM -> {
                    // Use OpenSubtitles.com API (requires authentication)
                    Log.d("SubtitleRepository", "Using OpenSubtitles.com API for search")
                    val apiKey = getApiKey()
                    val searchParams = mutableMapOf("query" to query, "languages" to language)
                    tmdbId?.let { searchParams["tmdb_id"] = it }
                    year?.let { searchParams["year"] = it.toString() }

                    Log.d("SubtitleRepository", "Searching with params: $searchParams")
                    val response = service.search(apiKey, authToken ?: "", searchParams)
                    Log.d("SubtitleRepository", "OpenSubtitles.com search response: ${response.data?.size ?: 0} results")
                    response.data ?: emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e("SubtitleRepository", "Subtitle search failed: ${e.message}", e)
            e.printStackTrace()
            emptyList()
        }
    }

    // New: Download a subtitle by the fileId (long) and return the saved File or null.
    suspend fun downloadSubtitleByFileId(fileId: Long): File? = withContext(Dispatchers.IO) {
        ensureLoggedIn()

        val apiKey = getApiKey()
        val downloadLink = try {
            val downloadRequest = DownloadRequest(fileId)
            service.requestDownload(apiKey, authToken ?: "", downloadRequest).link
        } catch (e: Exception) {
            Log.e("SubtitleRepository", "Requesting download link failed", e)
            return@withContext null
        }

        try {
            val response = service.downloadSubtitleFile(downloadLink)
            val body = response.body()?.byteStream() ?: return@withContext null

            val tempFile = File.createTempFile("subtitle", ".srt", context.cacheDir)
            FileOutputStream(tempFile).use { output ->
                body.copyTo(output)
            }
            Log.d("SubtitleRepository", "Subtitle downloaded to ${tempFile.path}")
            tempFile
        } catch (e: Exception) {
            Log.e("SubtitleRepository", "File download failed", e)
            null
        }
    }
}