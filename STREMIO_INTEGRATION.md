# Stremio Integration Setup Guide

## Requirements for Auto-Play to Work

For the auto-play feature to work correctly with Stremio, the following metadata **must** be passed from Stremio to PlayIT:

### Required Parameters

#### For TV Shows (to trigger auto-play next episode):
```
- imdbId: String (e.g., "tt0903747" for Breaking Bad)
- season: Int (e.g., 1, 2, 3, etc.)
- episode: Int (e.g., 1, 2, 3, etc.)
```

#### For Movies (to trigger auto-exit):
```
- imdbId: String (optional, used for identification)
- NO season/episode parameters
```

### How to Pass Parameters to PlayIT

You can pass parameters via:

#### 1. **Intent Extras (Recommended)**
```java
Intent intent = new Intent(Intent.ACTION_VIEW);
intent.setData(Uri.parse("video_url"));
intent.putExtra("imdbId", "tt0903747");
intent.putExtra("season", 1);
intent.putExtra("episode", 5);
intent.putExtra("callback", "http://stremio-host:callback-port");
intent.putExtra("position", 0L);      // Resume position in ms
intent.putExtra("duration", 0L);      // Total duration in ms
startActivity(intent);
```

#### 2. **URI Query Parameters**
```
intent://video-url?imdbId=tt0903747&season=1&episode=5&callback=http://stremio-host:port#Intent;...
```

### Optional Parameters

```
- callback: String - URL to send playback events to Stremio
- return_result: Boolean - If true, return playback position via setResult()
- position: Long - Resume position in milliseconds
- duration: Long - Total video duration in milliseconds
```

## How Auto-Play Works

### For Movies
1. PlayIT detects the video is a movie (no season/episode metadata)
2. Video plays normally
3. When 95% of the video is watched:
   - A "stopped" JSON-RPC event is sent to Stremio
   - PlayIT automatically exits after 2 seconds
   - User is returned to Stremio's interface

### For TV Shows
1. PlayIT detects the video is a TV show (season/episode metadata present)
2. Video plays normally
3. When 95% of the video is watched:
   - A "nextEpisode" JSON-RPC request is sent to Stremio with the next episode number
   - PlayIT exits after 2 seconds
   - Stremio receives the request and can load the next episode
   - User stays in Stremio's continuous viewing experience

## JSON-RPC Communication

### Events Sent by PlayIT to Stremio

#### 1. Time Update Event (Every 1 second during playback)
```json
{
  "jsonrpc": "2.0",
  "method": "playerEvent",
  "params": {
    "event": "time",
    "position": 123.456,
    "duration": 3600.0,
    "paused": false
  }
}
```

#### 2. Stopped Event (When playback ends)
```json
{
  "jsonrpc": "2.0",
  "method": "playerEvent",
  "params": {
    "event": "stopped",
    "position": 3600.0,
    "duration": 3600.0,
    "paused": false
  }
}
```

#### 3. Next Episode Request (For TV shows only)
```json
{
  "jsonrpc": "2.0",
  "method": "nextEpisode",
  "params": {
    "imdbId": "tt0903747",
    "season": 1,
    "episode": 2
  }
}
```

### Positions and Durations
- **position**: In seconds with millisecond precision (e.g., 123.456 for 2:03.456)
- **duration**: In seconds (e.g., 3600.0 for 1 hour)

## Stremio Configuration

### Example Stremio Addon Integration

If you're developing a Stremio addon or modifying one to work with PlayIT:

```javascript
// In your addon's player callback handler
app.post('/player-event', (req, res) => {
  const { event, position, duration, paused } = req.body.params;
  
  if (event === 'nextEpisode') {
    const { imdbId, season, episode } = req.body.params;
    console.log(`User finished S${season}E${episode}`);
    // Load next episode and send it back to PlayIT
    // PlayIT will exit and Stremio will load the new content
  } else if (event === 'stopped') {
    console.log(`Playback finished. Position: ${position}/${duration}`);
  }
  
  res.json({ success: true });
});
```

## Testing the Integration

### Manual Testing Steps

1. **Prepare a test video**
   - Get the IMDB ID (e.g., from IMDB.com)
   - Note if it's a movie or TV show
   - Have the video URL available

2. **For Movies**
   ```bash
   # Test auto-exit
   adb shell am start -a android.intent.action.VIEW \
     -d "your-movie-url" \
     -e imdbId "tt0068646" \
     -e callback "http://your-stremio-host:port" \
     com.example.playit/.PlayerActivityMinimal
   ```

3. **For TV Shows**
   ```bash
   # Test auto-play next episode
   adb shell am start -a android.intent.action.VIEW \
     -d "your-episode-url" \
     -e imdbId "tt0903747" \
     -e season "1" \
     -e episode "5" \
     -e callback "http://your-stremio-host:port" \
     com.example.playit/.PlayerActivityMinimal
   ```

4. **Expected Behavior**
   - Video should play normally
   - At ~95% watched, video should auto-transition (exit for movie, request next for TV)
   - Check `adb logcat | grep "PlayerActivityMinimal"` for debug logs

## Troubleshooting

### Auto-exit/Auto-play not triggering
1. Check logcat for the completion detection message:
   ```
   ✓ Playback completion detected (95.2% watched)
   ```

2. Verify metadata is being passed:
   ```
   TV Show metadata: imdbId=tt0903747, season=1, episode=5, isTvShow=true
   ```

3. Make sure video duration is valid:
   ```
   Player is ready, duration > 0
   ```

### Next episode request failing
1. Verify callback URL is correct and accessible
2. Check that `nextEpisode` method is implemented in Stremio addon
3. Look for network errors in logcat:
   ```
   Error requesting next episode: ...
   ```

## Configuration Options (Future)

The following can be added in future updates to make the feature more flexible:

```kotlin
// User preferences
val autoExitMovies: Boolean = true
val autoPlayNextEpisode: Boolean = true
val completionThreshold: Int = 95
val exitDelayMs: Long = 2000
```

## Known Limitations

1. **Next Episode Detection**: Currently increments episode by 1. Doesn't handle:
   - Season finale → next season
   - Special episodes with non-sequential numbers
   - Multiple episode files per episode number

   Solution: Stremio addon should handle episode progression logic

2. **Network Issues**: If Stremio callback fails:
   - App still exits but may not load next episode
   - User would need to manually select next episode

3. **No User Prompt**: Auto-play happens automatically with no confirmation
   - Future update could add optional dialog

## Support

For issues or feature requests related to auto-play:
1. Check the implementation in `PlayerActivity.kt`
2. Review logcat output for debugging
3. Ensure Stremio addon supports the required JSON-RPC methods

