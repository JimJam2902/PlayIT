package com.example.playit

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * TMDB (The Movie Database) API client for fetching next episode information
 * Provides fallback episode lookup when Stremio callback URL is not available
 */
class TMDBClient {
    companion object {
        private const val TAG = "TMDBClient"
        // TMDB API endpoint
        private const val TMDB_API_BASE = "https://api.themoviedb.org/3"
        // Free API key (rate-limited, for demo/fallback only)
        // In production, consider using a backend proxy or user's own API key
        private const val TMDB_API_KEY = "a6ef79916e094f89dea9a7de8d80f5d6"
    }

    /**
     * Fetch next episode URL from Torrentio addon
     * Uses TMDB for show lookup → gets IMDB ID → queries Torrentio for streams
     *
     * Integration flow:
     * 1. Search TMDB for show name to get TMDB ID
     * 2. Get IMDB ID from TMDB (needed for Torrentio)
     * 3. Query Torrentio addon manifest for available streams
     * 4. Torrentio returns HTTP URLs (already resolved by debrid services)
     * 5. Return best quality stream URL
     */
    suspend fun fetchNextEpisodeStreamUrl(
        showName: String,
        season: Int,
        episode: Int
    ): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching next episode stream: $showName S${season}E${episode}")

            // Step 1: Search for TV show on TMDB to get show ID
            val showId = searchShowOnTMDB(showName)
            if (showId == null) {
                Log.e(TAG, "Could not find show '$showName' on TMDB")
                return@withContext null
            }

            Log.d(TAG, "Found show ID: $showId for '$showName'")

            // Step 2: Get IMDB ID from TMDB
            val imdbId = fetchImdbIdFromTMDB(showId)
            if (imdbId == null || imdbId.isEmpty()) {
                Log.w(TAG, "Could not find IMDB ID for $showName")
                return@withContext null
            }

            Log.d(TAG, "Found IMDB ID: $imdbId")

            // Step 3: Use Torrentio to fetch streams for this episode
            val torrentioClient = TorrentioClient()
            val streamUrl = torrentioClient.fetchEpisodeStream(imdbId, season, episode)

            if (streamUrl != null) {
                Log.d(TAG, "✓ Found stream via Torrentio: $streamUrl")
            } else {
                Log.w(TAG, "⚠ Torrentio found no streams for S${season}E${episode}")
            }

            return@withContext streamUrl
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching next episode: ${e.message}", e)
            null
        }
    }

    /**
     * Search for a TV show by name on TMDB
     * Returns the TMDB show ID or null if not found
     */
    private fun searchShowOnTMDB(showName: String): Int? {
        return try {
            val encodedName = java.net.URLEncoder.encode(showName, "UTF-8")
            val url = "$TMDB_API_BASE/search/tv?api_key=$TMDB_API_KEY&query=$encodedName"

            Log.d(TAG, "Searching TMDB: $url")

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val jsonObj = JSONObject(response)
            val results = jsonObj.optJSONArray("results")

            if (results != null && results.length() > 0) {
                val firstResult = results.getJSONObject(0)
                val id = firstResult.optInt("id", -1)
                if (id > 0) {
                    Log.d(TAG, "TMDB search result: ID=$id, name=${firstResult.optString("name")}")
                    return id
                }
            }

            Log.w(TAG, "No results found on TMDB for '$showName'")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error searching TMDB: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch IMDB ID from TMDB show details
     * Torrentio uses IMDB IDs, not TMDB IDs
     */
    private fun fetchImdbIdFromTMDB(showId: Int): String? {
        return try {
            val url = "$TMDB_API_BASE/tv/$showId?api_key=$TMDB_API_KEY"

            Log.d(TAG, "Fetching IMDB ID from TMDB: $url")

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val jsonObj = JSONObject(response)
            val imdbId = jsonObj.optString("external_ids.imdb_id", "")
                .ifEmpty { jsonObj.optString("imdb_id", "") }

            if (imdbId.isNotEmpty()) {
                Log.d(TAG, "Found IMDB ID: $imdbId")
                imdbId
            } else {
                Log.w(TAG, "No IMDB ID in TMDB response")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching IMDB ID: ${e.message}", e)
            null
        }
    }

    /**
     * Extract show name from media URL/filename
     * Useful for determining what show is being played
     */
    fun extractShowNameFromUrl(urlOrFilename: String): String? {
        return try {
            // Extract filename from URL
            val filename = urlOrFilename.substringAfterLast('/').substringBefore('?')

            // Try to extract show name from patterns like:
            // "Breaking.Bad.S01E01.1080p.mkv" → "Breaking Bad"
            // "The.Office.S05E14.mkv" → "The Office"

            val cleanName = filename
                .substringBefore('.')
                .replace(Regex("\\d{3,4}p"), "") // Remove resolution (720p, 1080p)
                .replace(Regex("[Ss]\\d{2}[Ee]\\d{2}"), "") // Remove season/episode
                .replace(Regex("\\[.*?]"), "") // Remove brackets like [1080p]
                .replace(Regex("\\(.*?\\)"), "") // Remove parentheses
                .replace(".", " ")
                .trim()

            if (cleanName.isNotEmpty()) {
                Log.d(TAG, "Extracted show name: '$cleanName' from '$filename'")
                cleanName
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting show name: ${e.message}", e)
            null
        }
    }
}

