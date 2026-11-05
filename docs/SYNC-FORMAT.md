# Sync format, version 1 (draft)

Aura AI on a phone and AiMusic on a desktop analyse the same music collection twice and keep the
results to themselves. Both halves of what the apps know are expensive: hours of analysis for the
embeddings, and months of listening for the history the wave and the daily mix are built on. This
describes how the two carry that to each other.

The format is documented here, in one place, and the desktop port points at this file. Both
implementations have to follow the same words or neither can trust what the other wrote.

## What travels

| | travels | why |
| --- | --- | --- |
| Listening history | yes | the wave and the daily mix are nothing without it |
| Favourites and playlists | yes | hand-made, and losing them is losing work |
| The daily mix | yes | so both devices offer the same list on the same day, rather than two |
| Embeddings | yes | hours of analysis per device, and the same answer on both |
| Music files | **no** | Syncthing already does that, and does it better |
| Downloaded model weights | **no** | 390 MB that each device fetches once from Hugging Face |
| Settings | **no** | screen size and storage differ; these are local by nature |

## Identifying a track

Everything else rests on this: two devices have to agree that this file here and that file there
are the same recording. Nothing about the location can be used for it. The collections drift apart
in structure and in content, so a relative path is not an identity; absolute paths obviously are
not; and `MediaStore` ids are local to one phone.

A track is resolved in this order, and the first answer wins:

1. **The file's sha256.** Exact, and free of any judgement. Covers everything that was copied
   rather than re-encoded, which in this collection is nearly all of it.
2. **Duration within a second, plus artist and title.** Both are taken from the tags *and* from the
   file name — most files here are named `Artist - Title (version)` and carry no artist tag at all,
   and the same parsing the wave uses for grouping is reused (see `ai/TrackGroups.kt`). This is what
   survives a tag being edited or a flac being turned into an mp3.
3. **Nearest neighbour in embedding space**, accepted only above a similarity that leaves no room
   for doubt, and only when the duration agrees. This is a last resort for a file that was
   re-encoded *and* retagged.

A record that resolves to nothing is **kept, not dropped**, and offered to the matcher again after
every scan: the file it refers to may simply not have arrived yet.

Devices identify themselves by a UUID generated once, not by a name or a path. This also fixes two
things that are wrong locally today, independently of sync: on Android, history is keyed by
`MediaStore` id, so renaming a file loses its past; on the desktop, embeddings are keyed by
`absolutePath.hashCode()`, which is 32 bits — at 4300 files the chance of a collision is around
0.2%, and it grows with the square of the collection.

## History

An append-only set. Each play is a record of `(deviceId, track, atMs, playedFraction)`, and merging
two devices' histories is their union — no record is ever rewritten, so there is nothing to resolve.
Keyed by `(deviceId, track, atMs)`: the same device cannot start the same track twice in the same
millisecond, and two devices that did are two real plays.

Deletion only happens by retention, and retention is applied locally: a device that drops records
older than its own cutoff must not propagate that as a deletion, or the two would ratchet each other
down to nothing.

## Favourites and playlists

These change, so a grow-only set is not enough. Each is an **operation log**: `add`, `remove`,
`rename`, `reorder`, each stamped with the writing device and a Lamport counter `seq` that only ever
increases. Replaying a merged log in `(seq, deviceId)` order gives both devices the same result.

The wall-clock time on an operation is for display and retention only. It must not decide anything,
because two devices' clocks drift, and a phone that was off for a week will insist its clock is
right.

## The daily mix

`{day, attempt, tracks}`. A device that builds the mix for a day it already has a mix for raises
`attempt`; the highest `attempt` for a day wins, and ties break by device UUID. So both devices
show the same list rather than two lists both called "today".

## Embeddings

A fixed-size record, keyed by the identity above: 512 floats plus the excerpt it was made from.
Because the key is the content and the payload is deterministic, the whole set of them is a
content-addressed cache, and a new device can be handed one instead of scanning for hours.

A vector is accepted only when **all** of these match the receiving device's own:

- the **model** it was produced by, including which variant (quantised or full);
- the **analysis version**, which is bumped whenever anything that changes an embedding changes;
- the **excerpt** — the start, in milliseconds, of the ten seconds that were listened to;
- the **decoder** that turned the file into samples.

That last one is why, for the moment, **only mp3 travels**. An mp3 goes through `ai/Mp3Excerpt.kt`,
which is the same code in both apps, so both get the same samples by construction. Everything else
goes through the platform — MediaCodec on the phone, ffmpeg on the desktop — and those are two
different decoders whose agreement is a matter of luck rather than design. Measured on this pair of
devices they happen to agree well (m4a 0.9979, wav 0.9977, and the same ten nearest neighbours out
of ten), but nothing guarantees that the next phone's vendor has not improved its decoder, and a
vector that is wrong in a way nobody notices is worse than a vector that was never sent. So those
formats are analysed locally on each device until there is a shared decoder for them too.

The excerpt is the part that took measuring, and the reason it is in the record rather than assumed.
`ai/Excerpt.kt` decides it, and that file exists twice, once in each repository, and must stay
identical. The numbers behind it, measured on one mp3 with everything else held fixed:

| difference between the two sides | cosine |
| --- | --- |
| window five milliseconds apart | 0.9878 |
| window twenty-one milliseconds apart | 0.9585 |
| window anywhere from 20 ms to 2 s apart | 0.94–0.96 |
| excerpt one thousand samples short of ten seconds | 0.8988 |
| mono mix 3 dB apart, which is what `-ac 1` does against a plain average | 0.975 |
| linear interpolation against ffmpeg's polyphase resampler | 0.9973–0.9988 |
| two different high-quality resamplers | 1.0000 |

A waveform difference comes out larger in the embedding than it went in: on one wav, signals that
differed by 0.0008 gave vectors 0.025 apart. That is the reason the two sides are made to produce
the same samples rather than merely similar ones.

The app treats 0.9985 as "the same embedding, no re-analysis needed", and two different tracks sit
at about 0.27. So there is no tolerance to spend on the window: a near miss costs as much as a
gross one. Hence the rule in `Excerpt.startMs`, which truncates the duration to whole seconds before
halving it, so that the answer depends only on which two-second bucket the track's length falls
into — the one thing two platforms that measure duration differently can still agree on. Android
reads microseconds from the container; the desktop's tag reader reports whole seconds.

Where even that does not agree, the mismatch is **detected rather than papered over**: the start
travels with the vector, and a device that computes a different one analyses the track itself.

With both sides pinned this way, the same track embedded by the two pipelines agrees to 0.9971–0.9986
across four windows, against 0.8231 before; used as a query against a 4300-track library, the two
answers share 9 or 10 of their top ten, against 3 of 10 before. The residual is the resampler and
the fact that ffmpeg mixes to mono with a clip at ±1 while Android averages channels in float; it is
left alone deliberately, because closing it would mean putting the worse resampler on both sides to
buy nothing that can be observed.

## Transport

One file per device, so that there is exactly one writer per file and Syncthing never has to merge
anything:

```
<sync folder>/aura/v1/<device uuid>/history.jsonl
                                   /ops.jsonl
                                   /dailymix.jsonl
                                   /vectors.bin
                                   /manifest.json
```

JSON Lines for everything but the vectors: a file that was copied while being written loses its last
line, and a reader that skips a line it cannot parse loses one record instead of the file. The
vectors are fixed-size records in a binary file for the same reason — a truncated tail is a whole
number of records plus a remainder to ignore.

A device reads every directory but its own and writes only its own. There is no locking, no leader
and no server. Android reaches the folder through the storage access framework, so the user picks
it once and the grant persists.

Syncthing is the transport today, because that is what is already running. Direct transfer over the
local network is the obvious next step and changes none of the above: the same files, handed over a
socket instead of a folder.

## Keeping the two implementations honest

Three things must not drift, and each has a guard:

- `ai/Excerpt.kt` and `ai/Mp3Excerpt.kt` exist in both repositories and must be identical apart from
  the line naming which side it is on. Diff them.
- The mp3 decoder has a golden test, `Mp3ExcerptTest`, which decodes a committed 25-second fixture
  and checks the sha256 of the result. It runs on the Android side, where the tests run, and it
  pins the behaviour of the canonical copy: if it fails after a change, every embedding made from
  an mp3 has stopped being comparable and the analysis version has to be raised.
- `ANALYSIS_VERSION` must be the same number on both sides. A vector carries it and is refused if it
  differs, so a mistake here costs a re-analysis rather than a wrong answer.
- The merge itself gets **one fixture, two implementations**: the same input logs and the same
  expected result, committed in both repositories, so neither side can quietly decide something
  different about ordering or ties.

## Status

| | |
| --- | --- |
| The excerpt contract | done on both sides, measured |
| A shared mp3 decoder | done on both sides, byte-for-byte identical on this machine |
| The same, confirmed on a phone | not yet |
| A shared decoder for m4a, flac and the rest | not started; those vectors stay at home |
| Everything else here | designed, not written |

The excerpt had to come first. Until the two sides agreed on which samples to listen to, a synced
vector described a slightly different ten seconds of the track than the device receiving it would
have, and a quarter of the collection would have been quietly answering the wrong question. History,
playlists and the daily mix do not depend on any of this and can be built next.
