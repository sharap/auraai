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
- **…in Russian too.** CLAP only ever saw English, so a query with any Cyrillic in it goes through a
  separate encoder trained to answer the way CLAP's text tower answers for the English translation
  of the same phrase — the same 32 MB file the phone uses, and its own download, offered in
  settings. Measured on this library: a Russian query used to reproduce 12% of what its English
  twin returned, and 45% now; the query vectors themselves went from a cosine of 0.18 to 0.71.
  Russian still finds less than English does, and "мужчина поёт по-русски" remains out of reach —
  what language the singing is in is not something CLAP's space holds.
- **Smart albums.** The library is grouped by sound with DBSCAN over PCA-reduced embeddings, and
  each group is named zero-shot against a vocabulary of genre descriptions.
- **Playlist of the day.** A fixed playlist each morning, built from the listening history: tracks
  finished count for, tracks skipped count against, and part of it is reserved for things not heard
  in a long time.
- **The wave.** An endless stream steered by what you do with it: a track heard out pulls it
  closer, a skip pushes it away. Start it from your taste, from the track playing, from a
  description, or from one of eight moods. A slider says how far it may wander from the familiar.
  Two things differ from the phone deliberately: a wave left running is *offered* back when the
  window opens rather than resumed on its own — the phone resumes because Android reclaims the
  process while you are still listening, and a window that starts playing music when you open it
  was not asked to — and there is no "don't like" mark on this port for it to avoid, because this
  port has none.
- **Play similar.** Queues the nearest tracks in embedding space to the one playing: the same
  neighbourhood the wave starts from, as a fixed list rather than a stream.
- **External control.** Publishes MPRIS on the session bus, so media keys, the desktop's player
  widget, Bluetooth headset buttons and `playerctl` all drive it.

## Requirements

| | |
| --- | --- |
| JDK | 17 or newer to build; the packaged app carries its own runtime |
| libVLC | playback (`libvlc5` + `vlc-plugin-base` on Debian/Ubuntu, `vlc-libs` + `vlc-plugins-base` on Fedora) |
| ffmpeg | decoding the excerpt each track is analysed from |

On Debian or Ubuntu:

```
sudo apt install libvlc5 vlc-plugin-base ffmpeg
```

On Fedora, where both now come from the distribution's own repositories:

```
sudo dnf install vlc-libs vlc-plugins-base ffmpeg-free
```

## Running

From the source tree:

```
./gradlew :desktop:run
```

Once a package is installed, from the menu — *AiMusic*, under Audio — or by name:

```
aimusic
```

## Building a package

```
./gradlew :desktop:packageRpm      # Fedora and the other rpm distributions
./gradlew :desktop:packageDeb      # Debian and Ubuntu
```

Each format is assembled by its own distribution's tool — `rpmbuild` for the first, `dpkg-deb` and
`fakeroot` for the second — so a machine can only build the format it is. Name the format you want
rather than using `packageDistributionForCurrentOS`: that one asks for every format this operating
system could in principle carry, which on Linux is now both. The packages land in
`desktop/build/compose/binaries/main/rpm/` and `.../deb/`, and each is about 175 MB: a Java runtime
and skiko travel inside.

Both declare libVLC and ffmpeg, which no dependency scan can find, because libVLC is loaded through
JNA and ffmpeg is run as a process. The rpm asks for them as capabilities — `libvlc.so.5()(64bit)`
and `/usr/bin/ffmpeg` — rather than by package name, since the names are not the same thing twice:
that binary is `ffmpeg-free` in Fedora's own repositories and `ffmpeg` in RPM Fusion, and a
capability is satisfied by either. The deb gets the same two written in afterwards by
`tools/portable-deb.sh`, which additionally pairs every library name with its `t64` twin so that
one file installs on both Debian and Ubuntu.

The runtime inside the package is not this machine's JDK. It is a stock Temurin that Gradle
provisions as a toolchain, for two reasons: Fedora's openjdk cannot be jlinked at all — the jmods
are a package of their own that is not installed by default, and a `java.security` patched for the
system crypto policy closes the one other route — and a package should not come out different
depending on who built it. An ordinary `build` or `run` provisions nothing; only the packaging
tasks ask.

Installing the result, on Fedora:

```
sudo dnf install ./desktop/build/compose/binaries/main/rpm/aimusic-1.1.1-1.x86_64.rpm
```

It installs into `/usr/lib/aimusic`, puts itself in the menu as *AiMusic*, leaves the command
`aimusic` in `/usr/bin`, and reads the same `~/.aimusic/` as the version started from Gradle — the
library, the embeddings and the history are one, whichever of them is running.

The `/usr` tree rather than jpackage's default `/opt` is what the command costs: given a directory
inside `/usr`, jpackage puts launchers in `/usr/bin`, and under `/opt` nothing lands on the PATH.
There are two launchers for one application, `aimusic` and `AiMusic`, because jpackage names the
first after the application — and that name is also the label in the menu, where lowercase would
look like a typo. The second is the same application with the same options, not a second
configuration; see `packaging/aimusic-launcher.properties`.

`packageDmg` and `packageMsi` exist but have not been tested.

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
| `ru_text_encoder_quantized.onnx` | 32 MB | `sharapsoftware/auraai-ru-encoder` — this project's own, because the encoder was distilled against this app's CLAP export and exists nowhere else. Optional, and fetched on its own: without it everything works except Russian queries. |

To use a mirror instead, pass `-Daimusic.hf.endpoint`, `-Daimusic.hf.repo` or
`-Daimusic.hf.revision`; the Russian encoder comes from its own pair, `-Daimusic.ru.repo` and
`-Daimusic.ru.revision`. Behind a proxy the app follows `HTTPS_PROXY`/`HTTP_PROXY`, and an explicit
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
./gradlew :desktop:desktopTest
```

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE), and [THIRD-PARTY.md](THIRD-PARTY.md) for what this builds
on and under which terms.
