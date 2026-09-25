# Aura AI

An offline music player for Android that understands how its library *sounds*.

Everything runs on the device. There is no account, no server and no telemetry: the library is
analysed locally with a CLAP neural network, and the embeddings, the listening history and every
playlist built from them stay in the app's own storage.

## What it does

- **Search by description.** "calm piano", "energetic workout" — the query is encoded into the same
  space as the tracks, so it finds music that sounds like what was asked for, not files whose names
  happen to match. Plain text search over titles and artists runs alongside it, inside the open
  album or across the whole library.
- **Smart albums.** The library is grouped by sound with DBSCAN over the track embeddings, and each
  group is named zero-shot ("Транс", "Классика · Secret Garden") by comparing its centre with a
  vocabulary of genre and mood descriptions. The grouping radius is adjustable in settings.
- **Playlist of the day.** 28 tracks, fixed for the day and rebuilt at midnight, balancing what the
  listening history says against variety and a share of tracks not heard in a long time.
- **A player.** Media3 playback with a background service, equalizer, adaptive text colours
  computed from the wallpaper, pause on headphone disconnect and configurable audio focus.

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
| `-Pauraai.hf.repo=…` | Where the weights are fetched from. |

### Model weights

The CLAP weights are **not** in this repository — they are ~390 MB, which is no place for git. Put
`audio_model.onnx` and `text_model.onnx` into `app/src/main/assets/` to bundle them into the APK,
or leave the directory as it is and let the app download them from Hugging Face on first use
(Settings → AI models). Either way they are verified by sha256 before use.

## Tests

```bash
./gradlew testDebugUnitTest
```

The JVM tests cover the parts that can be reasoned about without a device: the tokenizer, the audio
feature extractor, contrast calculations, clustering, album naming and the daily playlist. Some
tests are experiments on a real exported library and skip themselves unless `AURAAI_EMBEDDINGS`
points at an export.

Instrumented tests live in `app/src/androidTest` and are opt-in where they are slow or destructive.
Note that `connectedAndroidTest` uninstalls the app, taking hours of analysis with it; install the
APKs manually and run `am instrument` instead.

## Licence

Copyright (C) 2026 Nikita Sharapov.

Aura AI is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. It is distributed in the hope that it will be
useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE) for the full text.

These terms cover the whole project, including the commits made before this file was added: the
copyright in all of it is held by the author, and this is the licence under which it is released.

[THIRD-PARTY.md](THIRD-PARTY.md) lists the libraries, model weights and data files this project
builds on, and the terms they come under.
