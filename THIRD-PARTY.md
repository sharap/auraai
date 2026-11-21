# Third-party components

Aura AI itself is GPL-3.0-or-later, and so is the desktop port in `desktop/` — one project in one
repository since the two code bases were merged, and this file covers both. It records what they
build on and under which terms. Apache-2.0, MIT, BSD and CC0 components can all be combined into a
GPL-3.0 work; the obligation that comes with them is attribution, which is what this file provides.

Two components need more than attribution.

**JLayer** is LGPL-2.1-or-later, which its own section 3 allows to be taken under the GNU GPL
version 2 or any later version — so it travels into this GPL-3.0 work on those terms, and its
source is available from its author and from Maven Central alongside the artifact.

**vlcj**, which drives playback in the desktop port, is GPL-3.0 itself. That is not a preference
but a constraint: anything linking it has to be GPL-compatible, which is one of the reasons this
project is GPL-3.0-or-later and cannot quietly become anything else.

Licences were read from each component's published POM or its own licence file, not from memory.

## Model weights

The app uses CLAP (Contrastive Language-Audio Pretraining) to turn audio and text into comparable
vectors.

| Component | Origin | Licence |
| --- | --- | --- |
| CLAP weights (`audio_model.onnx`, `text_model.onnx`) | [laion/larger_clap_music_and_speech](https://huggingface.co/laion/larger_clap_music_and_speech) | Apache-2.0 |
| ONNX conversion the app downloads by default | [Xenova/larger_clap_music_and_speech](https://huggingface.co/Xenova/larger_clap_music_and_speech) | Not stated on the model card |
| Reference implementation the audio pipeline follows | [LAION-AI/CLAP](https://github.com/LAION-AI/CLAP) | CC0-1.0 |
| Russian query encoder (`ru_text_encoder_quantized.onnx`) | Trained for this project; see below | GPL-3.0-or-later, as part of this work |

The weights are not distributed with the source. On Android they are either downloaded at runtime
or placed into `app/src/main/assets/` by whoever builds the APK; on the desktop they are fetched on
first use into `~/.aimusic/models/` and verified against a known size and sha256 before use.
Anyone distributing a build that bundles them is distributing Apache-2.0 material and should carry
that licence and its attribution along.

The conversion repository states no licence of its own. The weights it was converted from are
Apache-2.0; a build that would rather not rely on that can point `-Pauraai.hf.repo` (Android) or
`-Daimusic.hf.repo` (desktop) at another mirror, or convert the upstream model itself.

### The Russian query encoder

CLAP's text tower only understands English. `ru_text_encoder_quantized.onnx`, published in this
project's own Hugging Face repository, is a small encoder trained here to answer the way CLAP's
text tower answers for the English translation of the same phrase — the Multilingual-CLIP recipe.
It was made from, and so carries obligations towards:

| Ingredient | Origin | Licence |
| --- | --- | --- |
| Starting weights | [sergeyzh/rubert-mini-sts](https://huggingface.co/sergeyzh/rubert-mini-sts) | MIT |
| Training sentences (general language) | [Tatoeba](https://tatoeba.org) ru-en, via [OPUS](https://opus.nlpl.eu/Tatoeba.php) | CC-BY 2.0 FR |
| Training targets | CLAP's own text embeddings of the English side, from the Apache-2.0 weights above | Apache-2.0 |
| Music vocabulary pairs | Written for this project | GPL-3.0-or-later |

This encoder is used by the Android app; the desktop port answers Russian queries through the
English tower for now.

`app/src/main/assets/ru_vocab.txt` is the WordPiece vocabulary rubert-mini-sts inherits unchanged
from rubert-tiny2, MIT either way, and ships in every APK because it is a megabyte and the
tokenizer is useless without it.

## Data files in this repository

| File | Origin | Licence |
| --- | --- | --- |
| `app/src/main/assets/vocab.json`, `merges.txt` | The RoBERTa tokenizer shipped with the CLAP model above | Apache-2.0 |
| `desktop/src/commonMain/composeResources/files/vocab.json`, `merges.txt` | The same tokenizer, as the desktop port ships it | Apache-2.0 |
| `app/src/main/assets/ru_vocab.txt` | The WordPiece vocabulary of [cointegrated/rubert-tiny2](https://huggingface.co/cointegrated/rubert-tiny2) | MIT |

## Libraries

| Library | Where | Licence |
| --- | --- | --- |
| Kotlin standard library, kotlinx.coroutines | both | Apache-2.0 |
| Gson | both | Apache-2.0 |
| [JLayer](http://www.javazoom.net/javalayer/javalayer.html) — mp3 decoding for analysis | both | LGPL-2.1-or-later |
| [jcodec](https://github.com/jcodec/jcodec) — m4a decoding for analysis | both | FreeBSD, as its POM names it (the two-clause BSD licence) |
| AndroidX (Core, Lifecycle, Activity, Navigation, Room) | Android | Apache-2.0 |
| Jetpack Compose, Material 3, Material icons | Android | Apache-2.0 |
| AndroidX Media3 (ExoPlayer, Session, UI) | Android | Apache-2.0 |
| ONNX Runtime for Android | Android | MIT |
| Coil | Android | Apache-2.0 |
| Compose Multiplatform, Material 3, Material icons | desktop | Apache-2.0 |
| AndroidX Lifecycle (multiplatform), Collection, Annotation | desktop | Apache-2.0 |
| [vlcj](https://github.com/caprica/vlcj) — playback | desktop | **GPL-3.0** |
| [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) — tag reading | desktop | LGPL-2.1 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) (JVM) — inference | desktop | MIT |
| [dbus-java](https://github.com/hypfvieh/dbus-java) — MPRIS and the Secret Service on the session bus | desktop | MIT |
| [SLF4J](https://www.slf4j.org/) (pulled in by dbus-java) | desktop | MIT |
| JUnit 4 (tests only) | both | EPL-1.0 |
| Espresso, AndroidX Test (tests only) | Android | Apache-2.0 |
| kotlin-test (tests only) | desktop | Apache-2.0 |

## External programs

Not linked, and not distributed with either app; called at runtime by the desktop port and declared
as package dependencies of its `.deb`.

| Program | Used for | Licence |
| --- | --- | --- |
| libVLC (`libvlc5`, `vlc-plugin-base`) | audio playback, through vlcj | LGPL-2.1 / GPL-2.0 |
| ffmpeg | decoding the excerpt a track is analysed from, where no shared decoder handles the format | LGPL-2.1-or-later / GPL-2.0-or-later, depending on the build |

## Artwork

`app/src/main/res/drawable/app_icon.png` and the launcher icons derived from it, and
`desktop/packaging/aimusic.png`, were generated with Google Gemini and are used under the terms
that apply to its output. They are distributed as part of this project.
