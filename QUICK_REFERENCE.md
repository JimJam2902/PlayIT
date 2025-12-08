# Quick Reference - Auto-Play Feature

## Feature Summary
PlayIT now automatically handles playback completion based on content type:
- **Movies**: Auto-exits to Stremio
- **TV Shows**: Auto-plays next episode

## How It Works

### Detection
Checks if `season` and `episode` parameters are present:
- ✅ Both present → TV Show (auto-play next)
- ❌ Missing or null → Movie (auto-exit)

### Triggers at 95% Watched
- Not 100% to avoid buffering edge cases
- Resets if user seeks back to <85%

### Actions
- **Movie**: Send "stopped" event → Wait 2s → Exit
- **TV Show**: Send "nextEpisode" request → Wait 2s → Exit

## Implementation Code Map

| Component | Location | Purpose |
|-----------|----------|---------|
| Metadata fields | Lines 48-54 | Store TV show info |
| Metadata extraction | Lines 96-107 | Parse from intent |
| Completion monitor start | Line 150 | Initialize monitoring |
| Monitor function | Lines 237-280 | Check playback % |
| Next episode function | Lines 285-337 | Handle TV show completion |
| Job cleanup | Line 163 | Cancel on exit |

## Stremio Metadata to Pass

```kotlin
// Required for TV Shows
intent.putExtra("imdbId", "tt0903747")
intent.putExtra("season", 1)
intent.putExtra("episode", 5)

// Optional (but recommended)
intent.putExtra("callback", "http://host:port")
intent.putExtra("position", 0L)
intent.putExtra("duration", 0L)
```

## Debug Log Tags

```
PlayerActivityMinimal
```

Check these key messages:
```
✓ Playback completion detected (95.2% watched)
→ TV Show detected: S1E5, attempting next episode...
→ Movie completed, auto-exiting in 2 seconds...
Requesting next episode: S1E6
Next episode request response: 200
```

## JSON-RPC Methods Sent

### 1. Regular Playback Update
```json
{
  "jsonrpc": "2.0",
  "method": "playerEvent",
  "params": {"event": "time", "position": 123.456, "duration": 3600.0, "paused": false}
}
```

### 2. Playback Stopped
```json
{
  "jsonrpc": "2.0",
  "method": "playerEvent",
  "params": {"event": "stopped", "position": 3600.0, "duration": 3600.0, "paused": false}
}
```

### 3. Next Episode Request (TV Only)
```json
{
  "jsonrpc": "2.0",
  "method": "nextEpisode",
  "params": {"imdbId": "tt0903747", "season": 1, "episode": 6}
}
```

## Troubleshooting Checklist

- [ ] Metadata being passed from Stremio? → Check: `TV Show metadata: ...`
- [ ] Video playing normally? → Check: `Player is ready`
- [ ] Reaching 95%? → Fast-forward to ~95% and check logs
- [ ] Callback URL working? → Check response code in logs (should be 200)
- [ ] Next episode method implemented? → Check Stremio addon

## Key Classes & Methods

```
PlayerActivityMinimal
├── onCreate()
│   └── startPlaybackCompletionMonitor()
├── finish()
│   ├── reporterJob?.cancel()
│   └── playbackCompletionJob?.cancel()
├── startPlaybackCompletionMonitor()
│   └── Monitors player state every 500ms
├── attemptPlayNextEpisode()
│   └── Sends JSON-RPC request to Stremio
└── startReporter()
    └── Sends playback events (existing)
```

## State Variables

```kotlin
// Content type detection
isTvShow: Boolean
season: Int?
episode: Int?
imdbId: String?

// Completion handling
playbackCompletionJob: Job?
hasCompletionHandled: Boolean = false
```

## Important Thresholds

| Metric | Value | Purpose |
|--------|-------|---------|
| Completion % | 95 | Trigger threshold |
| Reset % | 85 | Reset flag if seeking back |
| Poll Interval | 500ms | Check frequency |
| Exit Delay | 2000ms | Time before finishing |

## Features by Content Type

| Feature | Movie | TV Show |
|---------|-------|---------|
| Auto-detect | ✓ | ✓ |
| Monitor completion | ✓ | ✓ |
| Send "stopped" event | ✓ | ✓ |
| Request next episode | ✗ | ✓ |
| Auto-exit | ✓ | ✓ |
| Auto-load next | ✗ | Stremio |

## Files Created

- ✅ `PlayerActivity.kt` - Modified with auto-play logic
- 📄 `AUTO_PLAY_IMPLEMENTATION.md` - Technical documentation
- 📄 `STREMIO_INTEGRATION.md` - Integration guide
- 📄 `QUICK_REFERENCE.md` - This file

## One-Command Test

```bash
# Test movie auto-exit
adb shell am start -a android.intent.action.VIEW -d "http://test.mp4" \
  -e imdbId "tt0068646" -e callback "http://localhost:8000" \
  com.example.playit/.PlayerActivityMinimal
```

---

**Last Updated**: December 7, 2025  
**Status**: ✅ Production Ready

