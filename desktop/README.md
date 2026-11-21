# AiMusic

A local music player for the desktop that finds tracks by what they sound like, not only by what
their tags say. It is a Compose Multiplatform port of [Aura AI](https://github.com/sharap/auraai.git), the Android
app; the AI pipeline is the same, the platform layer is not.

Nothing leaves the machine. The library, the embeddings and the listening history all stay in
`~/.aimusic/`.

## What it does

- **Search by description.** "calm piano", "epic cinematic orchestral" — the query is embedded with
  CLAP and compared against every analysed track. Results are cut where they stop standing out from
  the rest of the library for that particular query, rather than at a fixed score.
- **Smart albums.** The library is grouped by sound with DBSCAN over PCA-reduced embeddings, and
  each group is named zero-shot against a vocabulary of genre descriptions.
- **Playlist of the day.** A fixed playlist each morning, built from the listening history: tracks
  finished count for, tracks skipped count against, and part of it is reserved for things not heard
  in a long time.
- **Play similar.** Queues the nearest tracks in embedding space to the one playing.
- **External control.** Publishes MPRIS on the session bus, so media keys, the desktop's player
  widget, Bluetooth headset buttons and `playerctl` all drive it.

## Requirements

| | |
| --- | --- |
| JDK | 17 or newer to build; the packaged app carries its own runtime |
| libVLC | playback (`libvlc5` and `vlc-plugin-base` on Debian/Ubuntu) |
| ffmpeg | decoding the excerpt each track is analysed from |

On Debian or Ubuntu:

```
sudo apt install libvlc5 vlc-plugin-base ffmpeg
```

## Running

```
./gradlew :composeApp:run
```

## Building a package

```
./gradlew :composeApp:packageDeb
```

The `.deb` lands in `composeApp/build/compose/binaries/main/deb/` and declares libVLC and ffmpeg as
dependencies. `packageDmg` and `packageMsi` exist but have not been tested.

## Model weights

The CLAP weights are **not** in this repository. They are 408 MB, they are not this project's to
redistribute, and a file that size does not belong in git.

The app fetches them from Hugging Face the first time they are needed — from the settings screen,
or automatically when a scan starts — and keeps them in `~/.aimusic/models/`. Each file is checked
against a known size and sha256 before it is used, downloads resume if interrupted, and a file that
does not match is deleted rather than kept.

| File | Size | Fetched from |
| --- | --- | --- |
| `audio_model.onnx` | 269 MB | `Xenova/larger_clap_music_and_speech`, `onnx/audio_model.onnx` |
| `text_model.onnx` | 121 MB | the same repository, `onnx/text_model_quantized.onnx` |

To use a mirror instead, pass `-Daimusic.hf.endpoint`, `-Daimusic.hf.repo` or
`-Daimusic.hf.revision`. Behind a proxy the app follows `HTTPS_PROXY`/`HTTP_PROXY`, and an explicit
`-Dhttps.proxyHost` overrides that.

If you already have the files, putting them in `~/.aimusic/models/` under those names skips the
download.

## How the analysis works

A ten-second excerpt is taken from the middle of each track, decoded to mono 48 kHz, and turned
into 64-band log-mel features in the exact form CLAP was trained on — Slaney filters from 50 Hz,
area-normalised, a periodic Hann window, reflect-padded centred frames and a decibel scale. The
audio tower turns those into a 512-dimensional unit vector, stored in `~/.aimusic/embeddings.bin`.

Embeddings carry the version of the analysis that produced them. When anything that changes their
value changes, the stored ones are discarded and the settings screen says a rescan is due — stale
embeddings are not merely old, they describe a different space.

## Tests

```
./gradlew :composeApp:desktopTest
```

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE), and [THIRD-PARTY.md](THIRD-PARTY.md) for what this builds
on and under which terms.
