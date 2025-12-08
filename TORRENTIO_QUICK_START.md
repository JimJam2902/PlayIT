# PlayIT Torrentio Integration - Quick Start & Debugging

## Quick Summary

**What You Implemented:**
- ✅ Torrentio addon manifest integration
- ✅ TMDB show name → IMDB ID conversion
- ✅ Torrentio stream API queries
- ✅ Intelligent stream selection (Torbox preferred)
- ✅ Auto-play next episode
- ✅ Proper fallback to Stremio

**No Setup Needed:** 
Everything uses your existing Stremio setup (Torrentio addon + Torbox account)

---

## Testing the Integration

### 1. Play a TV Show Episode

```bash
# Launch PlayIT with a TV show episode
adb shell am start -a android.intent.action.VIEW \
  -d "https://torrentio.strem.fun/resolve/torbox/[YOUR_TORBOX_ID]/Friends.S03E02.1080p.Bluray.x265-HiQVE.mkv" \
  -n com.example.playit/.PlayerActivityMinimal
```

### 2. Watch Complete End-to-End

1. Start episode playback
2. Let it play to completion
3. Watch logs for auto-play trigger
4. Episode should auto-advance to S03E03

### 3. Monitor Logs

```bash
# All integration logs
adb logcat -v time -s TorrentioClient,TMDBClient,PlayerActivityMinimal | grep -v "^--"

# Specific stages
adb logcat -v time -s TMDBClient | grep "Found\|Error\|IMDB"
adb logcat -v time -s TorrentioClient | grep "streams\|Selected\|Error"
adb logcat -v time -s PlayerActivityMinimal | grep "STATE_ENDED\|completion\|next"
```

---

## Expected Log Sequence

### ✅ Successful Auto-Play

```
12-07 16:59:53.488 D/PlaybackViewModel: Player is ready, duration=1368384 ms
[... episode plays ...]
12-07 17:03:42.123 D/PlayerActivityMinimal: ✓ Playback STATE_ENDED detected at 1368384 / 1368384 ms
12-07 17:03:42.124 D/PlayerActivityMinimal: → TV Show detected: imdbId=null, S3E2
12-07 17:03:42.125 D/PlayerActivityMinimal: → Attempting to load next episode
12-07 17:03:42.200 D/TMDBClient: Fetching next episode stream: Friends S3E3
12-07 17:03:42.350 D/TMDBClient: Searching TMDB: https://api.themoviedb.org/3/search/tv?...
12-07 17:03:43.150 D/TMDBClient: TMDB search result: ID=1668, name=Friends
12-07 17:03:43.200 D/TMDBClient: Fetching IMDB ID from TMDB: https://api.themoviedb.org/3/tv/1668?...
12-07 17:03:44.050 D/TMDBClient: Found IMDB ID: tt0108778
12-07 17:03:44.100 D/TorrentioClient: Fetching streams from Torrentio: https://torrentio.strem.fun/stream/series/tt0108778/3/3.json
12-07 17:03:44.850 D/TorrentioClient: Torrentio response code: 200
12-07 17:03:44.851 D/TorrentioClient: Found 6 streams
12-07 17:03:44.852 D/TorrentioClient: Added stream: StreamInfo(title=📺 Torbox | 1080p | 2.1GB, ...)
12-07 17:03:44.853 D/TorrentioClient: Added stream: StreamInfo(title=🔥 RealDebrid | 1080p | 3GB, ...)
[... more streams ...]
12-07 17:03:44.860 D/TorrentioClient: Selected stream: 📺 Torbox | 1080p | 2.1GB - https://myfiles.torbox.com/...
12-07 17:03:44.861 D/PlayerActivityMinimal: 🚀 Auto-playing next episode: S3E3
12-07 17:03:44.862 D/PlayerActivityMinimal: URL: https://myfiles.torbox.com/[NEXT_EPISODE_URL]
12-07 17:03:45.000 D/PlayerActivityMinimal: Result already explicitly set for next episode, not overwriting
12-07 17:03:45.100 I ActivityManager: Displayed com.example.playit/.PlayerActivityMinimal: +1234ms
```

### ❌ Common Issues & Logs

**Issue: IMDB ID not found**
```
12-07 17:03:44.050 W/TMDBClient: No IMDB ID in TMDB response
```
**Fix:** Some non-English shows may not have IMDB ID in TMDB. App falls back to returning to Stremio.

**Issue: Torrentio returned no streams**
```
12-07 17:03:44.850 D/TorrentioClient: Torrentio response code: 200
12-07 17:03:44.851 D/TorrentioClient: Found 0 streams
```
**Fix:** Content too new or not available. Manual selection in Stremio needed.

**Issue: Show not found on TMDB**
```
12-07 17:03:43.150 W/TMDBClient: No results found on TMDB for 'Friends'
```
**Fix:** Show name extraction failed. Check if filename has proper S##E## pattern.

**Issue: Network timeout**
```
12-07 17:03:44.100 E/TorrentioClient: Error querying Torrentio: java.net.SocketTimeoutException
```
**Fix:** Internet connection issue. App will retry automatically or fall back to Stremio.

---

## Debugging Checklist

### When auto-play doesn't work:

- [ ] **Player fires STATE_ENDED?**
  ```bash
  adb logcat -s PlayerActivityMinimal | grep "STATE_ENDED"
  ```
  If no output, episode didn't reach natural completion.
  - Fix: Let episode play to 100% naturally, don't force-close

- [ ] **Show metadata detected?**
  ```bash
  adb logcat -s PlayerActivityMinimal | grep "TV Show detected\|season="
  ```
  If "isTvShow=false", season/episode not extracted.
  - Fix: Filename should have S##E## pattern

- [ ] **Show name extracted?**
  ```bash
  adb logcat -s TMDBClient | grep "Extracted show name"
  ```
  If no output, filename parsing failed.
  - Fix: Use standard filename format (Breaking.Bad.S01E01.mkv)

- [ ] **TMDB lookup works?**
  ```bash
  adb logcat -s TMDBClient | grep "TMDB search result\|No results"
  ```
  If "No results found", show name wrong.
  - Fix: Try exact show name with proper capitalization

- [ ] **IMDB ID obtained?**
  ```bash
  adb logcat -s TMDBClient | grep "Found IMDB ID\|No IMDB"
  ```
  If "No IMDB", not available in TMDB database.
  - Fix: Use Stremio manual selection as fallback

- [ ] **Torrentio query succeeds?**
  ```bash
  adb logcat -s TorrentioClient | grep "response code\|Found.*streams"
  ```
  If "response code: 404", IMDB ID wrong.
  If "Found 0 streams", content unavailable.

- [ ] **Stream selected?**
  ```bash
  adb logcat -s TorrentioClient | grep "Selected stream"
  ```
  If no output, selection logic failed.

- [ ] **Auto-play intent fired?**
  ```bash
  adb logcat -s PlayerActivityMinimal | grep "Auto-playing\|🚀"
  ```
  If no output, something wrong before this step.

---

## Network Error Handling

The app automatically retries on network errors:

```
Network Error Detected
    │
    ├─ Type: Socket/Connection/IO error
    │
    ├─ Retry attempt 1/3
    │  └─ Wait 2 seconds
    │  └─ Resume from current position (NOT initial position)
    │
    ├─ Retry attempt 2/3
    │  └─ Wait 2 seconds
    │  └─ Resume from current position
    │
    ├─ Retry attempt 3/3
    │  └─ Wait 2 seconds
    │  └─ Resume from current position
    │
    └─ All retries failed?
       └─ Give up
       └─ Display error to user
       └─ App exits
```

**Log Evidence:**
```
D/PlaybackViewModel: Network error detected at 1234567 ms, retrying (1/3) after 2000ms
D/PlaybackViewModel: Will resume from: 1234567 ms (current position at error)
D/PlaybackViewModel: NOT using initial resume position: 5000 ms
```

**Why this is important:**
- If user started playing at position 5000ms
- Network fails at position 1234567ms
- We retry from 1234567ms (not 5000ms)
- User experiences seamless resumption

---

## When Resume Position Resets

### ✅ Normal save flow
```
Playback position saved:
  ├─ Every 30 seconds (background job)
  ├─ On pause
  ├─ On stop
  └─ At natural completion
```

### ❌ When it might reset
```
Scenario 1: Force-close during playback
└─ App saves last checkpoint (every 30s)
└─ If closed between checkpoints, you lose ~30s

Scenario 2: Error at end of file
└─ App detects "error near end" (last 1MB)
└─ Treats it as completion (not error)
└─ Saves position as completed

Scenario 3: Manual network retry
└─ Uses current position (not initial)
└─ Saves that position
└─ ✅ Correct behavior
```

---

## Real Torrentio Manifests Examples

### What Torrentio Returns

For `Friends` (tt0108778) S03E03:

```json
{
  "streams": [
    {
      "title": "📺 Torbox | S03E03 | 1080p | 2.1GB",
      "url": "https://myfiles.torbox.com/resolve/torbox/[TOKEN]/[HASH]/Friends.S03E03.1080p.mkv"
    },
    {
      "title": "🔥 RealDebrid | S03E03 | 1080p | 3GB",
      "url": "https://myfiles.real-debrid.com/..."
    },
    {
      "title": "📺 Torbox | S03E03 | 720p | 1.5GB",
      "url": "https://myfiles.torbox.com/..."
    },
    {
      "title": "⚡ Alldebrid | S03E03 | 480p | 900MB",
      "url": "https://myfiles.alldebrid.com/..."
    },
    {
      "title": "📺 Torbox | S03E03 | 720p | 1.2GB",
      "url": "https://myfiles.torbox.com/..."
    }
  ]
}
```

### Selection Process

```
Available streams ranked:
1. 📺 Torbox | 1080p | 2.1GB       ← Score: 3+3+small = 🏆 SELECTED
2. 🔥 RealDebrid | 1080p | 3GB     ← Score: 2+3 = good, but Torbox preferred
3. 📺 Torbox | 720p | 1.5GB        ← Score: 3+2 = good, but lower quality
4. 📺 Torbox | 720p | 1.2GB        ← Score: 3+2 = same as above
5. ⚡ Alldebrid | 480p | 900MB      ← Score: 1+1 = last resort
```

---

## Quick Testing Commands

### Full integration test
```bash
# 1. Clear app data (fresh start)
adb shell pm clear com.example.playit

# 2. Install fresh APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Monitor logs
adb logcat -v time -s TorrentioClient,TMDBClient,PlayerActivityMinimal &

# 4. Launch test episode
adb shell am start -a android.intent.action.VIEW \
  -d "https://torrentio.strem.fun/resolve/torbox/85262591-b813-4269-8959-3855e764f06e/TEST/Breaking.Bad.S01E01.mkv"  \
  -n com.example.playit/.PlayerActivityMinimal

# 5. Let it play naturally to completion
# 6. Watch auto-play trigger in logs
```

### Specific component tests
```bash
# Test only TMDB lookup
adb logcat -v time -s TMDBClient

# Test only Torrentio
adb logcat -v time -s TorrentioClient

# Test only activity
adb logcat -v time -s PlayerActivityMinimal

# Test playback errors
adb logcat -v time -s PlaybackViewModel
```

---

## Important: What Works WITHOUT Extra Setup

✅ Your Torrentio addon (already installed in Stremio)
✅ Your Torbox account (already configured)
✅ Your Stremio settings (already saved)

**PlayIT uses them automatically!**

When you click a Torrentio stream in Stremio:
```
Stremio launches PlayIT with URL
    ↓
URL contains Torbox token: /resolve/torbox/[YOUR_TOKEN]/[HASH]/filename.mkv
    ↓
PlayIT streams from Torbox using YOUR account
    ↓
At completion, PlayIT queries Torrentio again
    ↓
Torrentio uses YOUR Stremio settings (already has Torbox configured)
    ↓
Returns streams pre-resolved by Torbox
    ↓
PlayIT plays next episode automatically
```

**No additional API keys needed!**
**No additional configuration needed!**
**Everything is automatic!**

