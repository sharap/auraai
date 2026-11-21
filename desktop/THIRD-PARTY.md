# Third-party components

AiMusic is GPL-3.0-or-later. That is not only a preference: vlcj, which drives playback, is
GPL v3, so anything linking it has to be GPL-compatible. This file records what the project builds
on and under which terms. Apache-2.0, MIT, BSD and LGPL components can all be combined into a
GPL-3.0 work; the obligation that comes with them is attribution, which is what this file provides.

Licences below were read from each component's published POM or its own licence file, not from
memory.

## Model weights

The app uses CLAP (Contrastive Language-Audio Pretraining) to turn audio and text into comparable
vectors.

| Component | Origin | Licence |
| --- | --- | --- |
| CLAP weights (`audio_model.onnx`, `text_model.onnx`) | [laion/larger_clap_music_and_speech](https://huggingface.co/laion/larger_clap_music_and_speech) | Apache-2.0 |
| ONNX conversion the app downloads | [Xenova/larger_clap_music_and_speech](https://huggingface.co/Xenova/larger_clap_music_and_speech) | Not stated on the model card |
| Reference implementation the audio pipeline follows | [LAION-AI/CLAP](https://github.com/LAION-AI/CLAP) | CC0-1.0 |

The weights are **not** distributed with this source. They are fetched from Hugging Face on first
use and kept in `~/.aimusic/models/`, verified against a known size and sha256 before use. Anyone
distributing a build that bundles them is distributing Apache-2.0 material and should carry that
licence and its attribution along.

The conversion repository states no licence of its own. The weights it was converted from are
Apache-2.0; a build that would rather not rely on that can point `-Daimusic.hf.repo` at another
mirror, or convert the upstream model itself.

## Data files in this repository

| File | Origin | Licence |
| --- | --- | --- |
| `composeApp/src/commonMain/composeResources/files/vocab.json`, `merges.txt` | The RoBERTa tokenizer shipped with the CLAP model above | Apache-2.0 |

## Libraries

| Library | Licence |
| --- | --- |
| Compose Multiplatform, Material 3, Material icons | Apache-2.0 |
| AndroidX Lifecycle (multiplatform), Collection, Annotation | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 |
| [vlcj](https://github.com/caprica/vlcj) — playback | **GPL-3.0** |
| [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) — tag reading | LGPL-2.1 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) (JVM) — inference | MIT |
| [dbus-java](https://github.com/hypfvieh/dbus-java) — MPRIS on the session bus | MIT |
| [Gson](https://github.com/google/gson) | Apache-2.0 |
| [SLF4J](https://www.slf4j.org/) (pulled in by dbus-java) | MIT |
| kotlin-test (tests only) | Apache-2.0 |

## External programs

Not linked, and not distributed with the app; called at runtime and declared as package
dependencies.

| Program | Used for | Licence |
| --- | --- | --- |
| libVLC (`libvlc5`, `vlc-plugin-base`) | audio playback, through vlcj | LGPL-2.1 / GPL-2.0 |
| ffmpeg | decoding the excerpt each track is analysed from | LGPL-2.1-or-later / GPL-2.0-or-later, depending on the build |

## Artwork

`composeApp/packaging/aimusic.png` was generated with Google Gemini and is used under the terms
that apply to its output. It is distributed as part of this project.
