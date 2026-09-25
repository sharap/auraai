# Third-party components

Aura AI itself is GPL-3.0-or-later. This file records what it builds on and under which terms.
Apache-2.0, MIT, BSD and CC0 components can all be combined into a GPL-3.0 work; the obligation
that comes with them is attribution, which is what this file provides.

## Model weights

The app uses CLAP (Contrastive Language-Audio Pretraining) to turn audio and text into comparable
vectors.

| Component | Origin | Licence |
| --- | --- | --- |
| CLAP weights (`audio_model.onnx`, `text_model.onnx`) | [laion/larger_clap_music_and_speech](https://huggingface.co/laion/larger_clap_music_and_speech) | Apache-2.0 |
| ONNX conversion the app downloads by default | [Xenova/larger_clap_music_and_speech](https://huggingface.co/Xenova/larger_clap_music_and_speech) | Not stated on the model card |
| Reference implementation the audio pipeline follows | [LAION-AI/CLAP](https://github.com/LAION-AI/CLAP) | CC0-1.0 |

The weights are not distributed with the source. They are either downloaded at runtime or placed
into `app/src/main/assets/` by whoever builds the APK, and anyone distributing an APK that bundles
them is distributing Apache-2.0 material and should carry that licence and its attribution along.

The conversion repository states no licence of its own. The weights it was converted from are
Apache-2.0; a build that would rather not rely on that can point `-Pauraai.hf.repo` at another
mirror or convert the upstream model itself.

## Data files in this repository

| File | Origin | Licence |
| --- | --- | --- |
| `app/src/main/assets/vocab.json`, `merges.txt` | The RoBERTa tokenizer shipped with the CLAP model above | Apache-2.0 |

## Libraries

| Library | Licence |
| --- | --- |
| AndroidX (Core, Lifecycle, Activity, Navigation, Room) | Apache-2.0 |
| Jetpack Compose, Material 3, Material icons | Apache-2.0 |
| AndroidX Media3 (ExoPlayer, Session, UI) | Apache-2.0 |
| ONNX Runtime for Android | MIT |
| Coil | Apache-2.0 |
| Gson | Apache-2.0 |
| JUnit 4 (tests only) | EPL-1.0 |
| Espresso, AndroidX Test (tests only) | Apache-2.0 |

## Artwork

`app/src/main/res/drawable/app_icon.png` and the launcher icons derived from it were generated with
Google Gemini and are used under the terms that apply to its output. They are distributed as part of
this project.
