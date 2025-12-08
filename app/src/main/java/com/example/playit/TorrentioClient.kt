package com.example.playit

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Torrentio Addon Client - Uses Torrentio's public manifest API
 *
 * How it works:
 * 1. Queries Torrentio's stream endpoint: https://torrentio.strem.fun/stream/series/[IMDB_ID]/[SEASON]/[EPISODE].json
 * 2. Torrentio returns available streams (already integrated with Torbox/Debrid services)
 * 3. Filters streams by quality and source
 * 4. Returns playable HTTP URL
 *
 * The magic: Torrentio addon already has built-in support for Real-Debrid, Alldebrid, Torbox, etc.
 * When you configure these services in Stremio, Torrentio automatically uses them to resolve streams.
 * We're just querying the same API Stremio uses!
 */
class TorrentioClient {
    companion object {
        private const val TAG = "TorrentioClient"
        private const val TORRENTIO_BASE = "https://torrentio.strem.fun"
        // Torrentio supports multiple debrid services via configuration
        // The public API works with all of them
    }

    /**
     * Fetch available streams for a specific TV show episode using Torrentio manifest
     *
     * @param imdbId IMDB ID of the show (e.g., "tt0903747" for Breaking Bad)
     * @param season Season number
     * @param episode Episode number
     * @return Stream URL or null if not found
     */
    suspend fun fetchEpisodeStream(
        imdbId: String,
        season: Int,
        episode: Int
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Construct the Torrentio stream endpoint
            // Format: /stream/series/[IMDB_ID]/[SEASON]/[EPISODE].json
            val url = "$TORRENTIO_BASE/stream/series/$imdbId/$season/$episode.json"
            Log.d(TAG, "Fetching streams from Torrentio: $url")

            val streams = queryTorrentioStreams(url)
            if (streams.isEmpty()) {
                Log.w(TAG, "No streams found for $imdbId S${season}E${episode}")
                return@withContext null
            }

            // Filter and select the best stream
            val bestStream = selectBestStream(streams)
            if (bestStream != null) {
                Log.d(TAG, "Selected stream: ${bestStream.title} - ${bestStream.url}")
                return@withContext bestStream.url
            }

            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching episode stream: ${e.message}", e)
            null
        }
    }

    /**
     * Query Torrentio API and parse the response
     * Returns list of available streams
     */
    private fun queryTorrentioStreams(url: String): List<StreamInfo> {
        val streams = mutableListOf<StreamInfo>()

        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "PlayIT/1.0")

            val responseCode = conn.responseCode
            Log.d(TAG, "Torrentio response code: $responseCode")

            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val jsonObj = JSONObject(response)
                val streamsArray = jsonObj.optJSONArray("streams")

                if (streamsArray != null) {
                    Log.d(TAG, "Found ${streamsArray.length()} streams")

                    for (i in 0 until streamsArray.length()) {
                        val streamObj = streamsArray.getJSONObject(i)
                        val title = streamObj.optString("title", "Unknown")
                        val url = streamObj.optString("url", "")

                        if (url.isNotEmpty()) {
                            // Parse title to extract quality and source
                            // Typical format: "📺 Torbox | S01E01 | 1080p | 5GB"
                            // or "⚡ RealDebrid | S01E01 | 720p | 2GB"
                            val quality = extractQuality(title)
                            val source = extractSource(title)
                            val size = extractSize(title)

                            val stream = StreamInfo(
                                title = title,
                                url = url,
                                quality = quality,
                                source = source,
                                size = size
                            )
                            streams.add(stream)
                            Log.d(TAG, "Added stream: $stream")
                        }
                    }
                }
            } else {
                Log.e(TAG, "Torrentio returned HTTP $responseCode")
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying Torrentio: ${e.message}", e)
        }

        return streams
    }

    /**
     * Select the best stream based on:
     * 1. Preferred debrid service (Torbox > RealDebrid > Alldebrid)
     * 2. Quality (1080p > 720p > 480p)
     * 3. File size (reasonable file size)
     */
    private fun selectBestStream(streams: List<StreamInfo>): StreamInfo? {
        if (streams.isEmpty()) return null

        // Preference order for debrid services
        val sourcePreference = mapOf(
            "torbox" to 3,
            "real-debrid" to 2,
            "alldebrid" to 1,
            "torrent" to 0  // Last resort: direct torrent
        )

        // Quality preference
        val qualityPreference = mapOf(
            "1080p" to 3,
            "720p" to 2,
            "480p" to 1,
            "" to 0
        )

        // Sort by: source preference → quality → size
        return streams.sortedWith(
            compareBy<StreamInfo>(
                { -(sourcePreference[it.source.lowercase()] ?: -1) },  // Higher preference first
                { -(qualityPreference[it.quality.lowercase()] ?: -1) }, // Higher quality first
                { it.size }  // Smaller size first
            )
        ).firstOrNull()
    }

    /**
     * Extract quality from stream title
     * Examples: "1080p", "720p", "480p"
     */
    private fun extractQuality(title: String): String {
        val regex = Regex("""(\d{3,4}p)""")
        return regex.find(title)?.value ?: ""
    }

    /**
     * Extract debrid service source from stream title
     * Examples: "Torbox", "RealDebrid", "Alldebrid", "Torrent"
     */
    private fun extractSource(title: String): String {
        return when {
            title.contains("torbox", ignoreCase = true) -> "Torbox"
            title.contains("real-debrid", ignoreCase = true) -> "RealDebrid"
            title.contains("alldebrid", ignoreCase = true) -> "Alldebrid"
            title.contains("torrent", ignoreCase = true) -> "Torrent"
            else -> "Unknown"
        }
    }

    /**
     * Extract file size from stream title
     * Returns size in bytes (as rough estimate for comparison)
     * Examples: "5GB", "2.5GB", "750MB"
     */
    private fun extractSize(title: String): Long {
        val regex = Regex("""([\d.]+)\s*(GB|MB)""", RegexOption.IGNORE_CASE)
        val match = regex.find(title) ?: return Long.MAX_VALUE

        val value = match.groupValues[1].toDoubleOrNull() ?: return Long.MAX_VALUE
        val unit = match.groupValues[2].uppercase()

        return when (unit) {
            "GB" -> (value * 1_000_000_000).toLong()
            "MB" -> (value * 1_000_000).toLong()
            else -> Long.MAX_VALUE
        }
    }

    /**
     * Data class representing a stream from Torrentio
     */
    data class StreamInfo(
        val title: String,
        val url: String,
        val quality: String,
        val source: String,
        val size: Long
    )
}

