# Sync format

The format itself is documented once, in the Android app's repository, because both
implementations have to follow the same words:

<https://github.com/sharap/auraai/blob/main/docs/SYNC-FORMAT.md>

Two things in this repository are bound by it:

- `composeApp/src/commonMain/kotlin/music/ai/recommend/ai/Excerpt.kt` and `Mp3Excerpt.kt` beside it
  are copies of the Android app's files of the same name. Each must stay identical, line for line,
  apart from the sentence that says which side the copy is on. Diff them when either changes — an
  embedding is only worth carrying between devices if both devices embedded the same samples, and
  these two files are what decide which samples those are. The second is also why mp3 does not go
  through ffmpeg here: the platform decoders disagree by tens of milliseconds about where an mp3
  frame sits, and JLayer is the same code on both sides.
- The mp3 decoder's golden test lives in the Android repository, where the test source set can be
  built: it decodes a committed fixture and checks the sha256 of the result. Run it there after
  touching either copy.
- `ANALYSIS_VERSION` in `composeApp/src/desktopMain/kotlin/music/ai/recommend/db/AppDatabase.kt`
  must match the Android app's. A vector may only be accepted from another device when the analysis
  version, the model and the excerpt all agree.

Note that this repository's `desktopTest` source set cannot be compiled without network access to
Maven Central, so the tests covering the excerpt rule live on the Android side, where they run.
