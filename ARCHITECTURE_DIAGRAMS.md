# Auto-Play Feature - Architecture Diagram

## Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                     STREMIO ADDON/APP                          │
│                                                                 │
│  Detects content type (movie or TV show)                       │
│  Prepares intent with metadata:                                │
│  - imdbId: "tt0903747"                                          │
│  - season: 1 (if TV show)                                       │
│  - episode: 5 (if TV show)                                      │
│  - callback: "http://stremio-host:port"                         │
│  - position, duration (optional)                                │
│                                                                 │
│  Starts PlayIT with Intent                                      │
└────────────────────┬────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│              PlayerActivityMinimal.onCreate()                   │
│                                                                 │
│  1. Extract metadata from intent                                │
│  2. Log all parameters                                          │
│  3. Determine content type:                                     │
│     isTvShow = (season != null && episode != null)              │
│  4. Initialize player                                           │
│  5. Start completion monitor                                    │
└────────────────────┬────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│           startPlaybackCompletionMonitor()                      │
│                                                                 │
│  Launch coroutine that:                                         │
│  - Runs indefinitely                                            │
│  - Checks player state every 500ms                              │
│  - Calculates: percentWatched = (currentPos / duration) * 100   │
│                                                                 │
│  ┌─────────────────────────────────────────┐                   │
│  │ While playback is ongoing...            │                   │
│  │                                         │                   │
│  │ if percentWatched < 85%:                │                   │
│  │   hasCompletionHandled = false          │                   │
│  │                                         │                   │
│  │ if percentWatched >= 95% AND            │                   │
│  │    !hasCompletionHandled:               │                   │
│  │   hasCompletionHandled = true           │                   │
│  │   → COMPLETION DETECTED!                │                   │
│  │   → Check content type (next)           │                   │
│  └─────────────────────────────────────────┘                   │
└────────────────────┬────────────────────────────────────────────┘
                     │
         ┌───────────┴───────────┐
         │                       │
         ▼                       ▼
  ┌─────────────────┐  ┌──────────────────┐
  │   MOVIE PATH    │  │   TV SHOW PATH   │
  │  (isTvShow ==   │  │ (isTvShow == true)
  │     false)      │  │                  │
  └────────┬────────┘  └────────┬─────────┘
           │                    │
           ▼                    ▼
  ┌──────────────────┐ ┌─────────────────────┐
  │  Send "stopped"  │ │ attemptPlayNextEpisode()
  │  JSON-RPC event  │ │                     │
  │  to callback URL │ │ 1. Get metadata:    │
  │                  │ │    - currentSeason  │
  │  {               │ │    - currentEpisode │
  │   method:        │ │    - imdbId         │
  │   playerEvent,   │ │                     │
  │   event: stopped │ │ 2. Construct JSON:  │
  │  }               │ │    nextEpisode S1E6 │
  │                  │ │                     │
  └────────┬─────────┘ │ 3. POST to callback │
           │           │                     │
           │           │ {                   │
           │           │  method: nextEpisode│
           │           │  season: 1          │
           │           │  episode: 6         │
           │           │ }                   │
           │           │                     │
           │           └────────┬────────────┘
           │                    │
           ▼                    ▼
  ┌──────────────────┐ ┌──────────────────────┐
  │ Wait 2 seconds   │ │ Wait 2 seconds       │
  │                  │ │                      │
  │ delay(2000)      │ │ delay(2000)          │
  │                  │ │                      │
  └────────┬─────────┘ └──────────┬───────────┘
           │                      │
           └──────────┬───────────┘
                      │
                      ▼
           ┌──────────────────────┐
           │  finish()            │
           │  - Cancel jobs       │
           │  - Return to Stremio │
           │  - Clean up player   │
           └──────────────────────┘
                      │
                      ▼
        ┌─────────────────────────┐
        │   BACK TO STREMIO       │
        │                         │
        │ Movie: User sees list   │
        │ TV: Next episode loads  │
        └─────────────────────────┘
```

## State Machine Diagram

```
        ┌───────────┐
        │  CREATED  │ (Intent processed, metadata extracted)
        └─────┬─────┘
              │
              ▼
        ┌──────────────┐
        │   PLAYING    │─────┐ (User plays video)
        └──────┬───────┘     │
               │             │ (User pauses)
               │             │
               │ <────────────┘
               │
        ┌──────▼──────────┐
        │ 95% COMPLETED   │ (Playback progress check)
        └──────┬──────────┘
               │
         ┌─────┴─────┐
         │           │
    ┌────▼────┐  ┌───▼────┐
    │  MOVIE  │  │ TV SHOW│
    └────┬────┘  └───┬────┘
         │           │
         ▼           ▼
    ┌─────────┐  ┌──────────┐
    │ EXITING │  │REQUESTING│
    │         │  │NEXT EP.  │
    └────┬────┘  └────┬─────┘
         │           │
         └─────┬─────┘
               │
               ▼
        ┌────────────┐
        │ FINISHED   │
        └────────────┘
```

## Component Interaction Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                   PlayerActivityMinimal                      │
│  ┌──────────────────────────────────────────────────────┐  │
│  │                                                      │  │
│  │  Fields:                                            │  │
│  │  ├─ imdbId: String?                                │  │
│  │  ├─ season: Int?                                   │  │
│  │  ├─ episode: Int?                                  │  │
│  │  ├─ isTvShow: Boolean                              │  │
│  │  ├─ playbackCompletionJob: Job?                    │  │
│  │  ├─ hasCompletionHandled: Boolean                  │  │
│  │  ├─ stremioCallbackUrl: String?                    │  │
│  │  └─ playbackViewModel: PlaybackViewModel           │  │
│  │                                                      │  │
│  ├──────────────────────────────────────────────────────┤  │
│  │                                                      │  │
│  │  Methods:                                           │  │
│  │  ├─ onCreate()                                      │  │
│  │  │  └─ Extract metadata                             │  │
│  │  │  └─ Start monitoring                             │  │
│  │  │                                                  │  │
│  │  ├─ startPlaybackCompletionMonitor()                │  │
│  │  │  └─ Loop: Check progress every 500ms             │  │
│  │  │  └─ At 95%: Call appropriate handler             │  │
│  │  │                                                  │  │
│  │  ├─ attemptPlayNextEpisode()                        │  │
│  │  │  └─ POST JSON-RPC to callback                    │  │
│  │  │  └─ Wait for response                            │  │
│  │  │  └─ Exit                                         │  │
│  │  │                                                  │  │
│  │  ├─ finish()                                        │  │
│  │  │  └─ Cancel jobs                                  │  │
│  │  │  └─ Send final events                            │  │
│  │  │                                                  │  │
│  │  └─ startReporter() [existing]                      │  │
│  │     └─ Send time updates                            │  │
│  │                                                      │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
         │                              │
         └──────────┬───────────────────┘
                    │
        ┌───────────▼───────────┐
        │  PlaybackViewModel    │
        │  ├─ player: ExoPlayer │
        │  ├─ position          │
        │  ├─ duration          │
        │  └─ isPlaying         │
        └───────────────────────┘
```

## Data Flow Diagram

```
STREMIO
  │
  │ Intent + Metadata
  │ ┌─────────────────────────┐
  │ │ imdbId: "tt0903747"     │
  │ │ season: 1               │
  │ │ episode: 5              │
  │ │ callback: http://...    │
  │ │ position: 0             │
  │ │ duration: 3600000       │
  │ └─────────────────────────┘
  │
  ▼
PlayIT onCreate()
  │
  ├─ Extract & Validate
  │  └─ isTvShow = (season != null && episode != null)
  │
  ├─ Initialize Player
  │  └─ Load video
  │
  ├─ Start Reporter
  │  └─ Every 1s: {event: time, position: X, duration: Y}
  │
  └─ Start Completion Monitor
     └─ Every 500ms: Check percentWatched
        │
        ├─ If percentWatched >= 95% AND not handled:
        │  │
        │  ├─ If isTvShow:
        │  │  └─ attemptPlayNextEpisode()
        │  │     └─ POST: {method: nextEpisode, season: 1, episode: 6}
        │  │        │
        │  │        └─ STREMIO receives request
        │  │           └─ Loads S1E6
        │  │
        │  └─ Else (Movie):
        │     └─ finish()
        │        └─ Exit to STREMIO
        │
        └─ If percentWatched < 85%:
           └─ hasCompletionHandled = false (allow retry if seeking)
```

## Timing Diagram

```
TIME    PLAYER STATE        MONITOR CHECK           ACTION
────────────────────────────────────────────────────────────────
  0s    [▓░░░░░░░░░░░]     0%  ✗                 Continue
  30s   [▓▓▓░░░░░░░░░]     30% ✗                 Continue
  60s   [▓▓▓▓▓░░░░░░░]     50% ✗                 Continue
  90s   [▓▓▓▓▓▓▓░░░░░]     70% ✗                 Continue
  120s  [▓▓▓▓▓▓▓▓░░░░]     80% ✗ (reset flag)    Continue
  135s  [▓▓▓▓▓▓▓▓▓░░░]     90% ✗                 Continue
  150s  [▓▓▓▓▓▓▓▓▓▓░░]     95% ✓ TRIGGER!        
        
        ┌─ Movie Path ─────────────┬─ TV Show Path ────────┐
        │                           │                       │
        ▼                           ▼                       ▼
   Send "stopped"              Send "nextEpisode"    Update STREMIO
   Wait 2 seconds              Wait 2 seconds        Load next ep.
        │                           │
        └──────────┬────────────────┘
                   │
                   ▼ (after 2s)
              finish()
              Exit to STREMIO
```

## Legend

```
▓ = Video watched
░ = Video remaining
✓ = Condition met
✗ = Condition not met
→ = Process flow
│ = Connection
```

---

This architecture ensures smooth playback transitions while maintaining clean separation between movie and TV show handling paths.

