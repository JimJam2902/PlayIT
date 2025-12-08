# Auto-Play Implementation for PlayIT

## Overview
This document describes the implementation of auto-play behavior for TV shows and auto-exit for movies when using PlayIT with Stremio.

## Changes Made

### 1. **Stremio Metadata Extraction** (PlayerActivity.kt)
The app now extracts TV show metadata from the intent to determine whether content is a movie or TV show:

```kotlin
private var imdbId: String? = null
private var season: Int? = null
private var episode: Int? = null
private var isTvShow: Boolean = false
```

The metadata is extracted from both:
- Intent extras: `intent?.getStringExtra("imdbId")`, `intent?.getIntExtra("season")`, etc.
- URI query parameters: `dataUri?.getQueryParameter("imdbId")`, etc.

Detection logic:
```kotlin
isTvShow = (season != null && episode != null)
```

### 2. **Playback Completion Monitoring**
A new coroutine job (`playbackCompletionJob`) monitors the playback state every 500ms:

```kotlin
startPlaybackCompletionMonitor()
```

The monitor detects when 95% or more of the video has been watched:
- **For Movies**: Automatically exits the player after 2 seconds
- **For TV Shows**: Attempts to play the next episode

### 3. **Auto-Exit for Movies**
When a movie finishes:
```kotlin
if (percentWatched >= 95 && !hasCompletionHandled) {
    hasCompletionHandled = true
    if (isTvShow) {
        attemptPlayNextEpisode()
    } else {
        delay(2000)
        finish()  // Auto-exit back to Stremio
    }
}
```

The 2-second delay allows the final "stopped" JSON-RPC event to be sent to Stremio.

### 4. **Auto-Play Next Episode for TV Shows**
For TV shows, the app sends a JSON-RPC method call to Stremio requesting the next episode:

```kotlin
attemptPlayNextEpisode()
```

This method:
- Constructs a JSON-RPC request with the next episode number
- Sends it to the callback URL
- Waits 2 seconds for Stremio to process
- Then exits the player, allowing Stremio to load the next episode

**JSON-RPC Request Format:**
```json
{
  "jsonrpc": "2.0",
  "method": "nextEpisode",
  "params": {
    "imdbId": "tt1234567",
    "season": 1,
    "episode": 2
  }
}
```

## Behavior Summary

| Content Type | Behavior |
|---|---|
| **Movie** | Plays to completion → Auto-exits to Stremio after 2 seconds |
| **TV Show** | Plays to completion → Requests next episode from Stremio → Auto-exits |

## Configuration

No configuration is needed. The app automatically detects content type based on whether season/episode metadata is present in the intent.

## Logging

Debug logs are available with the tag `"PlayerActivityMinimal"`:
- `✓ Playback completion detected (95.2% watched)`
- `→ TV Show detected: S01E01, attempting next episode...`
- `→ Movie completed, auto-exiting in 2 seconds...`
- `Requesting next episode: S01E02`

## Fallback Behavior

- If no callback URL is available and it's a TV show, the app still exits gracefully after 1 second
- If the JSON-RPC request fails, the app exits normally, allowing manual selection of the next episode

## Technical Details

### Metadata Sources
The app looks for metadata in this order:
1. Intent extras (direct Bundle parameters)
2. URI query parameters (URL-encoded parameters)

### Completion Detection Threshold
- 95% watched to detect completion
- 85% threshold to reset the completion flag (if user seeks back)

This prevents accidental triggers if the video doesn't reach 100% due to buffering or timing.

### Job Management
Both `reporterJob` and `playbackCompletionJob` are properly canceled in the `finish()` method to ensure clean resource cleanup.

## Future Enhancements

Possible improvements:
1. Add user preference to disable auto-exit/auto-play
2. Add confirmation dialog for next episode (optional)
3. Support for multi-season show progression detection
4. Analytics tracking for auto-play behavior

