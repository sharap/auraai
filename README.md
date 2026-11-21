# Aura AI

An offline music player for Android that understands how its library *sounds*.

<https://github.com/sharap/auraai>

Everything runs on the device. There is no account, no server and no telemetry: the library is
analysed locally with a CLAP neural network, and the embeddings, the listening history and every
playlist built from them stay in the app's own storage.

## What it does

- **Search by description.** "calm piano", "energetic workout" — the query is encoded into the same
  space as the tracks, so it finds music that sounds like what was asked for, not files whose names
  happen to match. Plain text search over titles and artists runs alongside it, inside the open
  album or across the whole library.
- **…in Russian too.** CLAP only ever saw English, so a Russian query goes through a small encoder
  trained here to answer the way CLAP's text tower answers for the English translation of the same
  phrase. It is a separate 33 MB download, fetched only once something is typed in Russian. Both
  ports use the same file and decide the same way which door a query goes through — see
  `shared/.../ai/RussianText.kt`, which is that decision and nothing else.
- **Smart albums.** The library is grouped by sound with DBSCAN over the track embeddings, and each
  group is named zero-shot ("Транс", "Классика · Secret Garden") by comparing its centre with a
  vocabulary of genre and mood descriptions. The grouping radius is adjustable in settings.
- **Playlist of the day.** 28 tracks, fixed for the day and rebuilt at midnight, balancing what the
  listening history says against variety and a share of tracks not heard in a long time.
- **My wave.** An endless stream that steers itself: a track heard to the end pulls it towards
  that sound, a skip pushes it away, and the next pick is made from the new direction. It starts
  from your taste, from the track playing, or from a description, and survives closing the app.
- **A player.** Media3 playback with a background service, equalizer, adaptive text colours
  computed from the wallpaper, pause on headphone disconnect and configurable audio focus.

## Two ports and one core

| Module | What it is |
| --- | --- |
| `:app` | The Android app: screens, services, Room, MediaStore, the release signing. |
| `:desktop` | The desktop player, Compose Multiplatform, with [its own README](desktop/README.md): its screens, vlcj for playback, jaudiotagger, the Secret Service for the sync phrase. |
| `:shared` | What both of them are: the sync format and its socket transport, the excerpt rule, the decoders, the analysis, the daily mix. Two targets, Android and JVM, from one set of files. |
| `:strings` | The words both interfaces show, as Compose Multiplatform resources — so a smart album cannot be called one thing on the phone and another on the desktop. |

```bash
./gradlew :app:assembleDebug   # the phone
./gradlew :desktop:run         # the desktop
./gradlew build                # everything, with the tests
```

The two were separate repositories until November 2026, which meant 9091 lines existed twice and a
script checked they had not drifted — and six files had drifted anyway, because nothing fails when
two copies disagree. `:shared` is where those lines went. What may not go in there is anything
platform-bound: no Room, no MediaStore, no Android framework, no AWT. Where the common code needs
the platform it asks through an interface, and each port answers in its own source set — which is
also why the track's identity is a type parameter, a MediaStore id on the phone and a path on the
desktop.

See [`docs/SYNC-FORMAT.md`](docs/SYNC-FORMAT.md) for what exactly the two agree on when they hand
each other vectors and listening history.

## Building

```bash
./gradlew assembleRelease
```

The release build is split per ABI, so `app/build/outputs/apk/release/` holds one APK per
architecture plus a universal one. During development the release APK is signed with the debug key
so it can be installed without a keystore.

Useful build flags (see `gradle.properties`):

| Flag | Effect |
| --- | --- |
| `-Pauraai.bundleModels=false` | Leave the ~390 MB of CLAP weights out of the APK; the app downloads them on first use. |
| `-Pauraai.abi=arm64-v8a` | Package native libraries for one architecture only. |
| `-Pauraai.abiSplits=false` | One APK carrying every architecture. |
| `-Pauraai.signReleaseWithDebugKey=false` | Required for a distributable build; wire up a real signing config. |
| `-Pauraai.appIdSuffix=sync` | A debug build is `music.ai.recommend.debug` by default and installs beside the real app, with its own storage and launcher entry — so trying a branch never costs the phone its analysed library. This gives a different suffix, for two dev builds at once. |
| `-Pauraai.appIdSuffix=` | Puts the debug build back on the real package. The one way to read the installed app's own database: signed with the release key (above) it installs over the release build and keeps the data. |
| `-Pauraai.hf.repo=…` | Where the CLAP weights are fetched from. |
| `-Pauraai.hf.ruRepo=…` | Where the Russian query encoder is fetched from. |

### Model weights

The CLAP weights are **not** in this repository — they are ~390 MB, which is no place for git. Put
`audio_model.onnx` and `text_model.onnx` into `app/src/main/assets/` to bundle them into the APK,
or leave the directory as it is and let the app download them from Hugging Face on first use
(Settings → AI models). Either way they are verified by sha256 before use.

The Russian query encoder (`ru_text_encoder_quantized.onnx`, 33 MB) is handled the same way, from
this project's own Hugging Face repository rather than the upstream CLAP one. Its vocabulary
(`ru_vocab.txt`) is a megabyte and ships in every APK, because the tokenizer is useless without it.

## Tuning

Most of what can be adjusted is in Settings: the grouping radius for smart albums, which CLAP
export the scanner analyses with, the equalizer, pause on disconnect and whether other apps are
given way. The wave has more to it, and some of it is only in the source.

### The wave, from the outside

Playlists → My wave:

| Control | What it does |
| --- | --- |
| To my taste | Starts from what has been listened to lately. |
| Like what is playing | Starts from the current track. |
| By description | Starts from a phrase, in English or Russian ("спокойное пианино"). |
| Mood chips | Shortcuts to the same thing: each is a fixed English prompt, see `ai/WaveMoods.kt`. |
| Familiar — new | How long a track is held back after being played: three days at one end, two months at the other. Remembered between sessions. |
| Don't like (in the player) | Pushes the direction away harder than a skip does, and the track never comes back in this wave. |

A skip steers too, which is the part worth knowing: play a track to the end and the stream moves
towards it, leave within the first third and it moves away. Anything in between is no verdict.

### The wave, from the inside

The steering lives in `app/src/main/java/music/ai/recommend/ai/Wave.kt`, as constants with no
settings behind them — changing them means building your own APK.

| Constant | Default | Effect |
| --- | --- | --- |
| `FINISHED_PULL` | 0.15 | How far a track heard to the end pulls the direction towards itself. |
| `SKIPPED_PUSH` | 0.10 | How far a skip pushes it away. Lower than the pull on purpose: "not now" is not "not ever". |
| `DISLIKE_PUSH` | 0.30 | The same for an explicit "don't like", which is unambiguous. |
| `AVOID_MAX`, `AVOID_HALFWAY` | 0.9, 2 | Ceiling on how much sitting near something rejected counts against a candidate, and how fast that ceiling is approached: one skip applies a third of it, two a half, six about three quarters. |
| `SKIPPED_BELOW`, `FINISHED_ABOVE` | 0.3, 0.8 | Fractions of a track below which it counts as skipped and above which as heard. |
| `CONTINUITY` | 0.25 | How much the next track is chosen to flow on from the current one rather than from the direction. Raise it for smoother transitions, lower it to follow the taste more strictly. |
| `SHORTLIST` | 40 | Candidates considered per pick. |
| `TEMPERATURE` | 0.12 | Spread of the draw among them. Higher is more surprising, lower more predictable; at zero the stream always takes the best track and starts repeating within the hour. |
| `GROUP_COOLDOWN` | 5 | Tracks before the same artist may come back. |
| `RECENT_DAYS_FAMILIAR`, `RECENT_DAYS_NEW` | 3, 60 | The two ends of the "familiar — new" slider, in days. |
| `UNPLAYED_BONUS` | 0.25 | How much a never-played track is favoured at the "new" end. |

What counts as "the same artist" is decided in `ai/TrackGroups.kt`: the tag if the file carries one,
otherwise the part before the dash in "Artist - Title", and only failing both the folder name. The
order matters more than it sounds. In a library where most files are untagged, grouping by folder
makes one big folder a single artist, and the cooldown then shuts out everything in it for five
tracks — which is how a wave ends up circling one act.

Four more sit in `MusicViewModel`, and they are about the queue rather than the taste:

| Constant | Default | Effect |
| --- | --- | --- |
| `QUEUE_AHEAD` | 4 | Tracks kept queued behind the one playing. |
| `SKIP_FRACTION` | 0.3 | Below this much of a track, the queued tail is rebuilt rather than kept. |
| `MIN_WAVE_LIBRARY` | 40 | Analysed tracks below which a wave refuses to start, because it would circle immediately. |
| `WAVE_MAX_AGE_MS` | 12 h | How long a saved wave is worth resuming after the app was closed. |

### Judging a change

Not by a single session. These knobs decide what gets played over an evening, and the difference
between two settings is a matter of how the stream feels after days of listening, not of any number
a test can print. What a test can do is show the stream: `RealLibraryWaveTest` runs a wave over an
exported library with a simulated listener who skips everything from one folder, and prints what
comes out — enough to catch a stream that circles, never leaves a neighbourhood it is being pushed
out of, or lets one artist take over. It needs `AURAAI_EMBEDDINGS` pointing at an export, which
holds personal data and is not in the repository.

## Tests

```bash
./gradlew build                        # all three JVM suites, about 400 tests
./gradlew build assembleDebugAndroidTest   # and the instrumented sources, which `build` leaves out
```

They are in three places, and the split follows what needs a platform:

| Where | What it covers |
| --- | --- |
| `shared/src/commonTest` | The sync format and its transport, the decoders, the sound print, the daily mix, the listen tracker — the bulk of it. One suite for both ports, run against the JVM target. |
| `app/src/test` | What is left of the Android app's own logic, plus experiments on a real exported library that skip themselves unless `AURAAI_EMBEDDINGS` points at an export. |
| `desktop/src/desktopTest` | This port's own storage and phrase vault, and the decoders checked against ffmpeg on whatever music the machine has. |

**`./gradlew build` does not compile `app/src/androidTest`.** It compiles and runs the unit tests
and lint, and leaves the instrumented source set alone — so a renamed field or a changed
constructor can sit there for weeks while every build reports success. Six such breakages
accumulated during the merge of the two code bases. `assembleDebugAndroidTest` is the cheap guard,
and the `android-tests` job in [the workflow](.github/workflows/desktop-build.yml) runs it on every
push, with no device needed.

Running the instrumented tests does need a device. Install the APKs and use `am instrument`:
`connectedAndroidTest` uninstalls the app, taking hours of analysis with it.

Three of the desktop tests are green when they have checked nothing: `SharedDecodersOnThisMachine`,
`FlacAgainstFfmpeg` and `M4aAgainstFfmpeg` return early when there is no music folder or no ffmpeg,
rather than declaring themselves skipped. On a clean machine they count as passes, and the numbers
cannot tell them apart from real ones. `assumeTrue` instead of `return` would put them in the
skipped column, where they belong.

## Licence

Copyright (C) 2026 Nikita Sharapov.

Aura AI is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. It is distributed in the hope that it will be
useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE) for the full text.

These terms cover the whole project, including the commits made before this file was added: the
copyright in all of it is held by the author, and this is the licence under which it is released.

The source for every released build is at <https://github.com/sharap/auraai>, which is how anyone
who receives a binary gets the corresponding source, as the licence requires.

[THIRD-PARTY.md](THIRD-PARTY.md) lists the libraries, model weights and data files this project
builds on, and the terms they come under.
