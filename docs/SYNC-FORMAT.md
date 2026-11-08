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
| The playlist of the day | yes | so both devices offer the same list on the same day, rather than two |
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
   re-encoded *and* retagged, and it can only be applied where a vector is actually to hand —
   which is in the vectors themselves, since a history record carries a key and not 512 floats.
   What history gets from it is second-hand: a hash tied to a local track by any means at all is
   remembered, and every later record carrying that hash resolves at once.

A record that resolves to nothing is **kept, not dropped**, and offered to the matcher again after
every scan: the file it refers to may simply not have arrived yet. Dropping those would quietly
lose the history of everything that arrives late, which is most of what arrives at all on a device
set up second.

Where several local tracks fit one description — duplicate files are ordinary — the closest
duration wins, and ties go to whichever the library listed first. Either answer is right; giving
the *same* answer twice is not optional, or one listen would land on a different track on every
pass.

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

These change, so a grow-only set is not enough. Each is an **operation log**. Six kinds, written as
`k`: `create`, `delete`, `rename`, `add`, `remove`, `order`. Each carries the writing device `d` and
a Lamport counter `s` that is one more than the highest that device has ever seen, its own
operations and the other's alike. Replaying a merged log in `(seq, deviceId)` order gives both
devices the same result, whatever order the files arrived in.

The wall-clock time `at` on an operation is for display and retention only. It must not decide
anything, because two devices' clocks drift, and a phone that was off for a week will insist its
clock is right.

A playlist is named by its name, which is what both apps already used. That makes `rename` an
operation rather than a delete and a create, and it makes two devices that independently made a
playlist of the same name end up with one playlist instead of two — which, for one person with two
devices, is what they meant. The cost is that two different renames of the same playlist keep the
later one and drop the other.

The favourites are the operations with no playlist named: `p` is left out rather than set to null.

### Two devices' words for one track

Replaying compares tracks by the identity rule above — hash when both have one, otherwise tags with
**a second of slack on the duration** — and never by equality of the written-down key. This is not
an optimisation; equality is the wrong question. The desktop reads a length from a tag and gets
whole seconds, the phone reads it from MediaStore and gets milliseconds, so one track is `308000`
in an operation written on one device and `308271` in an operation written on the other. Under
equality those are two recordings, and the log then quietly does nothing: a removal of a track the
other device had added, or a reordering of a playlist that came from it, applies to no track at
all. The playlists still *looked* right throughout, because resolving a key to a local track had
always allowed the slack — only the log's own arithmetic was strict.

A track already in a playlist keeps the description it arrived with. An operation that mentions it
in other words does not rewrite it, so what the playlist holds does not shift under the other
device's feet.

### Order

`order` carries the whole playlist — `ts`, a list of tracks described the same way a single `t` is
— and not the move that was made. A move cannot be said as "this track to the fourth place",
because a position is not something two devices can agree about: the fourth place means something
else on a device that has since added two tracks, and nothing at all on one that removed three.

Replay applies it to what the playlist holds at that moment and to nothing else:

- tracks it names **and the playlist has** take the order it gives;
- tracks the playlist has that it does **not** name keep their relative order, after those;
- tracks it names that the playlist does not have are not put back by being mentioned — an order
  says how to read a playlist, not what is in it;
- an order for a playlist this log does not have is an order for nothing, and does not create one.

So a track added on one device while the other was arranging the list is not lost; it is at the
end, and at the same end on both. Two devices arranging at once settle by `(seq, deviceId)` like
everything else: one of the two arrangements wins whole, which is not a kind answer to the owner
but is the same answer in both places.

The favourites have no order to set. Neither app stores one — the phone keeps a set of ids and
shows them in library order, the desktop a set of paths — so `order` always names a playlist, and a
line that does not is dropped.

### Operations the other device has not heard of

A line whose `k` this version does not know is dropped, and so is one missing what its kind cannot
do without. That is a real decision rather than an oversight: an older build replaying a log a
newer one wrote may arrive at a state the newer one would not. Refusing the whole log instead would
leave the owner with no playlists at all, which is worse and harder to explain. It is also why
`order` carries a list and not an index — a build that drops it still shows the right tracks, just
not in the right order.

### On the device

Each app keeps its own `playlist-log.jsonl` next to its sync state: every change made here is
written there first, numbered under a lock so that two changes a moment apart cannot take the same
counter. What the app *shows* is then the replay of that log and never the mutation itself. Two
things follow, both of them bugs that happened before the rule existed: a sync pass can no longer
take away a change made here, and a change the log refused does not appear to have worked and then
vanish at the next pass.

What a device already had before any of this existed is **seeded** into the log once, as the
operations that would have built it — a `create` and an `add` per track, in the order they were in
— and only after the library has been read, because a track that cannot be described cannot be
seeded. Seeding before the library is read would write an empty log and mark it done.

A change is pushed five seconds after the last of them, so hearting a dozen tracks is one write.
The folder is read on every pass, and a pass happens when playback stops, when the library has been
scanned, when the app comes to the front — and every fifteen seconds while it stays there, because
a playlist open on one screen and changed on the other must not wait for somebody to do something.

## The playlist of the day

`{m: day, a: attempt, d: device, ts: [tracks]}`, one record per build, in `mix/`.

This is the only thing in the folder that is not a fact to be merged but an opinion to be agreed
on. A listen happened; a playlist is the owner's; but today's mix is a list each device is
perfectly able to invent for itself — which is exactly the problem, because then one person has
two different lists both called today. So one of them wins and both show it.

Which one: the higher **attempt**, then the higher **device id**. A device raises the attempt when
it builds another mix for a day it already has one for, which is what the owner asking for a
different mix does — so a later deliberate act beats an earlier one, and the device id settles the
case where both built their first mix of the day before meeting. The wall clock decides nothing,
here as everywhere.

`day` is the **local** calendar date, not UTC: it is the day the owner is living in, and the one
the mix turns over on at their midnight. A record for a day that is not this device's today is
read and dropped rather than applied, so two devices in different time zones stop sharing a mix
near midnight instead of overwriting each other's.

Three rules that each exist to stop a particular kind of damage:

- **An empty mix never travels.** A device whose library is too small to choose from builds
  nothing, and a record of nothing would otherwise be a way to wipe a good mix on the other side.
- **A device never publishes a mix it adopted.** Claiming it would put two records of one list in
  the folder, and the two devices would then take turns winning the day on the strength of their
  names.
- **A mix is read in only after the library is.** Nothing keeps these records — unlike a playlist
  change, a mix has no history worth replaying — so a pass that cannot match tracks yet leaves the
  folder looking unread. Otherwise the first mix of the day would be lost on whichever device
  happened to start up second.

A mix that names tracks this library has none of is not adopted: a device shows what it has rather
than an empty day. One that names some of them is adopted without them, because the alternative is
two lists called today again.

## Embeddings

A fixed-size record, keyed by the identity above: 512 floats plus the excerpt it was made from.
Because the key is the content and the payload is deterministic, the whole set of them is a
content-addressed cache, and a new device can be handed one instead of scanning for hours.

A vector is accepted only when **all** of these match the receiving device's own:

- the **model** it was produced by, including which variant (quantised or full);
- the **analysis version**, which is bumped whenever anything that changes an embedding changes;
- the **excerpt** — the start, in milliseconds, of the ten seconds that were listened to;
- the **decoder** that turned the file into samples.

### Which decoders are shared, and why that is the hard part

mp3, flac, wav and m4a are decoded by code both apps carry — `ai/Mp3Excerpt.kt`,
`ai/FlacExcerpt.kt`, `ai/WavExcerpt.kt`, `ai/M4aExcerpt.kt`, dispatched by `ai/SharedDecoder.kt`.
Everything else goes to the platform, MediaCodec on the phone and ffmpeg on the desktop, and stays
at home.

For mp3 the reason is the codec itself: measured against ffmpeg, Android's decoder begins its
output 576 samples later, and its seek over a variable bitrate lands hundreds of samples further
off again.

For flac the samples were never the problem — it is lossless, so two correct decoders agree
exactly — and it would have been easy to conclude that the platform's would do. Everything *around*
the samples disagreed: the desktop handed ffmpeg a start in whole seconds and asked it for 48 kHz,
so ffmpeg's polyphase resampler ran where the phone runs the shared linear one, and the window
began wherever the seek landed. Owning the decode settles both, and the stream header's exact
sample count settles the third disagreement — how long the track is — which is what the excerpt is
chosen from.

Being lossless also makes flac the one format whose decoder can be *proved* rather than trusted:
ffmpeg is an oracle, not a second opinion. The shared decoder was run against it over the whole
library it was written for — 425 files — and agreed sample for sample on every one. Then the same
eight files were decoded on the phone and on the desktop, and the sha256 of the samples matched on
all eight, which is the property the vectors actually rest on.

For m4a none of that reasoning was available, and that is the point worth keeping. AAC is lossy,
and its specification defines how *close* a decoder has to be rather than what it has to output, so
two correct decoders of one file produce two different signals on purpose. There is nothing to
align and no amount of care that helps: the only answer is for both apps to run the same decoder.
They run the pure-Java one that jcodec carries, `net.sourceforge.jaad.aac`, named
`jaad-jcodec-0.2.5` after the artifact rather than the library, because what has to agree is the
bytes both devices run. The container — which frame the window starts in, and how long the track
is — is this app's own work, as it is for flac.

The m4a trap was the encoder's **priming**: the samples an encoder puts in front of the music to
fill its own filters. There is a box for declaring it, the edit list, and the files this was
written for do not carry one — iTunes writes the number in a tag instead, `iTunSMPB`, which also
says exactly how many samples of music there are. Reading neither put the excerpt 2112 samples —
48 milliseconds — into the music on one side and at the music on the other. It was found by
correlating the excerpt against ffmpeg's decode of the same file and asking at what lag they lined
up: the answer came back as exactly the priming, which is how a misalignment names its own cause.

Because AAC cannot be checked for equality, it is checked for agreement: the cosine against
ffmpeg's decode of the same window, over all 133 m4a files in the library it was written for. The
mean was 0.9999 and the worst 0.9982, where a window in the wrong place lands at nothing in
particular — the first run, before the priming was read, averaged about 0.05. And the same files
decoded on the phone and on the desktop gave identical digests, in all four formats: flac 6 of 6,
m4a 6 of 6, mp3 5 of 5, wav 6 of 6. That is the property the vectors rest on, and it is the one
that cannot be established by reasoning about floating point.

A format that gains a shared decoder does **not** make the vectors already held for it travel. A
stored vector does not record what decoded it, so those are left as they are — marked as already
offered, which they were — and they travel only once the owner has them analysed again.

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

With that done, the chain was checked link by link on a phone against this machine, on the same
files:

| link | mp3 |
| --- | --- |
| the samples the model is fed | identical, byte for byte |
| the log-mel features computed from them | identical, byte for byte |
| the embedding | cosine 1.0000000, individual numbers within 3e-07 |

The last row is the one link that cannot be made the same code on both sides — ONNX Runtime for
Android against ONNX Runtime for the JVM, the same graph in fp32 but not the same build — and it
turns out to cost nothing beyond floating-point rounding. So an mp3 embedding made on one device is
an mp3 embedding on the other, and the floor on carrying vectors is set by the decoder, which is
now shared, rather than by anything underneath.

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

### How a vector travels

`aura/v1/<device>/vectors/<day>.jsonl`, in the same batch envelope as history and named for the day
of the writing for the same reason: a file that is appended to and then finished is one Syncthing
stops carrying. The day says nothing about the vectors inside — a vector has no date — it only
bounds the file.

One record carries the track key, the excerpt start, the decoder, the model's sha256, the analysis
version, and the 512 floats as base64 of little-endian fp32. That is about 2.8 KB a record against
roughly 7 KB as a JSON array of numbers, and a library is four thousand of them: eleven megabytes
either way is the thing being optimised. Fifty records to a batch, which is a line of about 137 KB —
a little over Syncthing's block size, so an append moves a block or two instead of rewriting the
file.

Both sides bound the work. A sender offers at most five hundred vectors a pass and a reader takes at
most five hundred, so a first exchange — a whole library — spreads over a handful of passes instead
of stalling a phone with eleven megabytes of parsing and four thousand matches at once. Nothing is
lost in between: progress is recorded only for lines actually handed over, and only after the
vectors are stored.

Every device offers every vector it holds, including ones it was handed by somebody else. The folder
therefore carries each vector once per device that has it — twice over, with two devices — and that
is deliberate: it makes the folder a cache a device can be *restored* from rather than one it can
only contribute to. A device that loses its vectors is handed them back by whoever it gave them to,
instead of analysing a library again. Against hours of analysis, a few megabytes is nothing. A
device that holds no vectors at all also forgets how far it had read, since otherwise "read to the
end" would outlive the store it was read into.

Each row records when it was offered, so it is offered once. That mark is also set for a vector that
could not travel at all — a format with no shared decoder — because that answer will not change
while the analysis version does not. It is **not** set for a vector whose track this library cannot
describe: a library can be short for ordinary reasons, not read yet or mid-rescan, and a vector
retired on that basis is lost for good. This was learnt by losing four thousand of them to a test
whose library had three tracks in it. Such vectors are stepped over instead, and the batch asked for
grows by as many as have been met, so they cannot sit at the head of the queue and block the rest.
When the analysis version changes every vector is thrown away and the marks go with them.

### A refusal is local, and does not travel

Every library has a few files that are not music: a download that stopped at its ID3 tag, a
playlist saved under the name `.mp3`. This collection has five, and both devices refuse all five.

A refusal used to be stored as a vector of 512 zeros. That kept the scan from decoding the file
again on every pass, and it was wrong in three ways at once. It counted as an analysed track, so the
library's own count of what it knows was five too high. It was **offered to the other device like
any other vector**, which is the part that matters: "my decoders could not read this file" is a
verdict about one device's decoders, and a device that *can* read the format would have taken those
zeros for an analysis and never looked at the track again. And it could never be reconsidered — the
day the owner finishes that download, the file would stay unanalysed with nothing to say why.

So a refusal is now a record of its own, in the row where the vector would have been, carrying what
the file looked like when it was refused: its length and the time it was last written
(`ai/AnalysisBacklog.kt`, shared by both sides). Three rules follow from that one change.

- A refusal is **never written to the folder**. It is not news another device can use.
- A line carrying a vector of nothing but zeros is **turned away on reading**, because the folder
  already holds such lines from before this was understood, and will for as long as the day files
  they are in are kept.
- A refusal **expires with the file**. The same length and the same write time mean the same file
  and the same answer; anything else means this is no longer the file that was refused, and it goes
  back into the backlog. A row recovered from the old zero-vector form carries no stamp at all,
  which no present file can equal, so each gets exactly one more attempt and then a stamp of its
  own.

A refused track is not an analysed one: the badge does not claim it, the count does not include it,
and a vector arriving for it from the other device is news rather than a duplicate. What it is, is
*dealt with*, which is a different question and the only one the scan asks. The owner is told how
many there are — *Could not be read: N* — because the answer is nearly always a file worth
replacing, and nothing else in either app would ever mention it.

### Vectors whose track is gone are the owner's to keep

A vector outlives its file. The library loses a track, the listening history does not: it still
holds the listens, they still shape the taste profile and the playlist of the day, and the vector is
the only record of what that track sounded like. Delete it and the history keeps rows that nothing
can place in the similarity space.

So neither device throws those away by itself — the desktop's scan used to, on every run, which was
quietly deciding this for the owner. They are counted and shown instead, and a button removes the
ones **the history does not mention**, which on this phone is 392 vectors against `MediaStore` ids
that will never be issued again. The ones history still mentions stay, however many there are.

### The excerpt start comes from the decoder, not from the platform

The window has to be the one the vector was actually made from, and for a shared decoder that is
the decoder's own: `Mp3Excerpt.decode` picks it from the frame walk's count of the samples, not
from whatever the platform says the track's length is. So `Mp3Excerpt.startMs` is what names it,
and the platform is only asked for formats whose vectors do not travel anyway.

Naming it from the platform's duration was tried first and is wrong, which a live exchange said
plainly: a phone refused every vector a desktop sent, sixty and sixty-six in two passes, as
describing a different ten seconds — when each described exactly the right one. For an mp3 the
container's duration comes from a Xing header or an estimate while the frame walk counts the frames,
and the two are not a millisecond apart but a second or more. On real files from this library the
window named from MediaStore disagrees with the decoder's for two tracks in three.

The vectors were never wrong — the decoder made them from its own window either way. The *check*
was wrong, and it refused what it should have taken. Which is the shape of error worth noticing: a
guard comparing the wrong quantity looks like caution and costs the whole feature.

Done this way the check also means something better than it used to. Two devices holding the same
file get the same window by construction, so a disagreement no longer means "the two read the
duration differently" — it means the files are different, another encode of the same recording,
whose samples differ too. The question went from one worth arguing about to one worth asking.

It costs a walk over the file's frame headers, which is sequential reading and no decoding, once per
track on each side.

## What this protects, and from whom

The listening history is the most sensitive thing either app holds. It is not a record of taste but
a minute-by-minute record of behaviour: when the phone's owner wakes up, when they commute, when
they are at home, when they are awake at three in the morning. Four thousand plays describe a
routine better than a calendar does. Playlists and favourites are personal but tame beside it. The
vectors themselves say nothing, but what they are keyed by — a file hash, an artist and a title —
is a complete inventory of someone's library.

So the question is not whether this needs protecting but where the protection belongs.

| | on the wire | at rest |
| --- | --- | --- |
| Syncthing, which carries it today | TLS, mutual, pinned to each device's own key — already covered | the sync folder is plain text, and on Android it sits in shared storage |
| direct transfer over the local network, which is next | nothing, unless built | — |

Two things follow. First, there is no gap on the wire today, and building a second layer of
transport security under Syncthing would be effort spent twice. Second, there is a gap **at rest**
that is easy to overlook: the sync folder is not app-private. On Android any app holding the
storage permission can read it. Encrypting a channel and then writing plain text into a shared
folder locks the door and leaves the window open.

And the local network is the case that makes the decision. It is often not the owner's own network —
a café, a hotel, an office — where anyone attached to it can read what passes, and, worse, write.
A forged history is the expensive case: history is a grow-only set, so anything that merges into it
stays, and a poisoned wave is noticed a week later and cannot be undone.

### The payload is encrypted, not the channel

Each batch of records is sealed with AEAD — AES-256-GCM, which both platforms have in hardware —
under one symmetric key shared by the owner's devices and established once when they are paired.
Every transport then becomes a dumb pipe, and it stops mattering who carries the bytes: Syncthing
and its relays, a socket on a hostile network, a USB stick.

This is a stronger invariant than "TLS on the local network, and Syncthing is trusted", and it is
also less work, because it removes a question rather than answering it. Three consequences are worth
spelling out:

- **Integrity comes free, and signatures are not needed.** A batch that authenticates under the
  shared key was written by something holding that key, which is to say by one of the owner's own
  devices. Public-key signatures would additionally prove *which* device wrote a batch, and in a
  system whose devices all belong to one person that is a distinction without a difference — the
  device id travels inside the sealed plaintext, which is enough to merge by. This also sidesteps
  Ed25519, which Android only exposes from API 33 while this app supports 23.
- **The local-network transport barely needs crypto of its own.** It moves opaque sealed blobs, so
  it cannot leak content however hostile the network. A pinned TLS channel over the top is still
  worth having, to keep device identifiers and traffic patterns off the air, but nothing load-
  bearing rests on it. In particular there must be **no fallback to an unprotected connection**:
  the classic way to reduce a secure protocol to an insecure one is to break the secure attempt and
  let the client retry in the clear.
- **Losing the key costs nothing but re-pairing.** The sync files are a transport, not an archive:
  every record in them also lives in the device's own database in the clear. A device that forgets
  the key has lost its outbox, not its history.

### What the envelope has to carry

Each line of a `.jsonl` file is one sealed batch rather than one record, so that the cost of a nonce
and an authentication tag is paid per batch instead of per play:

| field | why |
| --- | --- |
| `d` | the writing device's id, also the directory the file sits in |
| `n` | that device's batch counter, so a missing batch is visible as a gap |
| `e` | what sealed it: `none` for a folder with no phrase set, otherwise `a256gcm` |
| `iv` | the nonce: the device id's first four bytes, then `n` as eight — so it cannot repeat |
| `ct` | the sealed batch as base64, or the records themselves when `e` is `none` |

`d`, `n` and `e` are the associated data, so a batch cannot be moved to another device's file or
renumbered without the seal failing. A truncated line loses that batch and nothing else, which is
the same property the plain-text form had.

What this does not do is prove that nothing was **removed from the end**. A reader cannot tell a
deleted tail from a tail that has not synced yet, and no scheme fixes that without an online
channel to ask the other device what it believes it wrote. Gaps in the middle are caught by `n`;
the tail is accepted as a known limit.

### The key, as it is today

**A phrase the owner types on both devices.** The key is derived from it per *writing* device:
PBKDF2-HMAC-SHA256, 200 000 rounds, salted with that device's id — which is a random 128-bit value,
so it is a proper salt and nothing can be precomputed against this app in general. A reader derives
the key for the device whose file it is opening and keeps it for the run; the derivation is
deliberately expensive and happens once.

Deriving per device buys more than the salt: a key is used by exactly one writer, so its nonces
cannot collide with another device's, and the nonce is that device's own counter, which never
repeats. Nonce reuse is the one thing AES-GCM does not forgive, and here it is impossible by
construction rather than by care.

PBKDF2 is written out by hand in `sync/Sealing.kt` rather than taken from `SecretKeyFactory`, which
only knows `PBKDF2WithHmacSHA256` from API 26 against this app's 23 — and a key derived one way on
one device and another way on the other is two keys. The test checks it against the JVM's own
implementation, which the desktop has.

The phrase is kept in each app's own private storage: the Android preferences, a settings file on
the desktop. The Android keystore would be better — hardware-backed, non-exportable — but what has
to be kept is the phrase and not a key made from it, since meeting a new device means deriving a
new key from it again. That trade belongs with pairing.

Changing the phrase, or setting one for the first time, makes a device **read the folder again from
the beginning**: a batch that could not be opened is counted as unreadable and marked read, so
without that, the day the owner fixes a typo on one device, everything the other had sealed would
stay unread for ever. The same mechanism as a change in what a vector is believed on, and the state
carries a short name for the phrase in use — `sealingKey` — to notice it.

### Pairing, which is still to come

A phrase typed by a person is weaker than a key exchanged between devices and held in hardware, and
it is here because it works today over a folder both devices already share. When the local-network
transport arrives, pairing arrives with it, and it is the one window an attacker could climb
through: before it there is nothing to authenticate against. So it will be explicit and
short-range — one device shows a code bound to the key, the other has it typed in or read by
camera. Anything that pairs silently over the network pairs with whoever answers first.

Discovery deserves the same care for a different reason. A device announcing itself over mDNS tells
the whole café that its owner runs this app, and usually what their phone is called. So: no
announcing by default, announcing only while the sync screen is open, and under a name that means
nothing.

### What is done and what is not

Done: the envelope, the sealing, the key from a phrase, and the reading-again that a changed phrase
needs. A folder with no phrase set writes `e: "none"` and stays readable for ever, so nothing that
is already in a folder is lost by turning this on — and a device that is handed the phrase goes
back for what it had stepped over.

Not done, and deliberately with the network transport rather than before it: pairing, the key in
hardware, discovery, and the socket itself. What sealing buys is that none of those is load-bearing
any more. The records are opaque before they leave, so a transport is a dumb pipe — Syncthing and
its relays, a socket on a café's network, a USB stick — and the question of who may carry them stops
needing an answer.

## Transport

One file per device, so that there is exactly one writer per file and Syncthing never has to merge
anything:

```
<sync folder>/aura/v1/<device id>/history/2026-11-05.jsonl
                                 /vectors/2026-11-05.jsonl
                                 /playlists/2026-11-05.jsonl
                                 /mix/2026-11-05.jsonl
```

Four kinds of thing, four directories, one file per day in each — named in UTC, so that two devices
agree on which file a batch belongs in whatever their clocks say. The day in a *record* may be the
owner's local date (the playlist of the day is), but the day in a *file name* never is.

A provider that will not append to an existing document turns each of those files into a directory
of its own instead, one whole file per batch:
`playlists/2026-11-05/0001.jsonl`. The reader takes either shape, and the only cost of the second
is more files. Which one a device uses is settled once, by writing a probe file and reading it
back, before any real data is written.

JSON Lines throughout: a file that was copied while being written loses its last line, and a reader
that skips a line it cannot parse loses one record instead of the file. Each line is one sealed
batch, as described above, which keeps that property — vectors included, where a line is a block of
records and the floats are base64 inside it.

A device reads every directory but its own and writes only its own. There is no locking, no leader
and no server. Android reaches the folder through the storage access framework, so the user picks
it once and the grant persists.

Two transports carry that, and the owner picks one. A **folder** something else keeps in step —
Syncthing, a network share, a stick — or this app's own **socket**. The second is the reason the
layout above is worth reading twice: it changes nothing about it.

### Carrying it over a socket

Syncthing was the transport first, because it was already running, and on Android it is the part
that goes wrong. A phone's background limits fight a sync daemon, the folder has to live in shared
storage where any app with the permission can read it, and reaching it goes through the storage
access framework, which is slow and refuses to append on some providers. None of that is Syncthing
being bad at its job; it is a phone being a phone. So the app carries the files itself.

What makes that a small piece of work is that the records are already sealed, so the transport is a
dumb pipe, and that the folder was always behind an interface. A device keeps the same tree in its
**own private storage** — its own directory, and a copy of each other device's — and the socket's
whole job is to make those copies match. Everything above it is untouched: the same batches, the
same readers, the same merge, the same note of how far each file was read.

The conversation has five questions about files, because the layout only permits that many, and four
more about vectors, which do not travel as files at all — see the next section:

| | |
| --- | --- |
| `hello` | which device is calling, so the right directory is offered |
| `list` | what the answering device has of **its own**, with the byte count of each file |
| `fetch` | bytes of one of those files, from an offset, at most 256 KB at a time |
| `index` | what the answering device has of the **caller's** files |
| `put` | bytes of the caller's own file, to be appended to its copy there |
| `vectors` | how many vectors the answering device holds, and how many of its tracks have none |
| `want` | the keys of tracks the caller has no vector for; the answer is the records, if any |
| `wanted` | the keys the answering device has no vector for, so the caller can hand them over |
| `give` | records for tracks the answering device said it lacks |

Offsets are bytes rather than lines, because the files are append-only and a byte count is the one
cursor two devices can agree on without trusting each other's line counting. A write is **strictly**
at the end: a gap would leave a hole of zeros inside somebody's history and an overlap would rewrite
what has already been read, so both are refused and the caller asks for the index again next pass.

A caller may read the answering device's own directory and append to the one named after the id it
said hello with. It may not touch any other, which is a safety property rather than a convenience:
a device that could write *our* directory could forge our batches to a third device. Every path is
checked against the shapes this program's own writers produce, which is also what keeps `..`,
absolute paths and a peer's imagination out of the file system.

One connection does both directions — take first, then give — because a device cannot count on
being called back. A phone with its screen off is not listening, so whichever side is awake does
the whole exchange. For the common pair that is the phone dialling a desktop that listens all day,
and **the phone needs no background service at all**: it listens only while the app is on the
screen, and dials on every pass. Two phones sync while both apps are open. That is a limit, stated
rather than hidden, and it is the limit that removes the thing Syncthing suffers from.

### Changing where the records go

What the state remembers is not a *kind* of transport but a **destination**: `direct` for the
socket, `folder:<uri>` for a particular folder. Naming the folder is what makes choosing a different
one count as a change, which it is — an empty destination whose files carry the old one's names, the
same situation as switching transports and invisible while the state said only "folder".

On a change, progress through every file goes (none of the new destination's files has been read)
and every outbox starts again, so that the new destination ends up holding what this device knows
rather than only what happened after the switch. Batch counters are untouched: a number going
backwards would collide with batches already out there.

The vectors are the one thing not re-sent on *every* change — eleven megabytes for a library this
size — but a destination that has **never** had them gets them once, and the state remembers which
destinations those were. The reason is what a folder is for: a device that loses its analysis is
handed it back by whoever holds a copy, and a destination that never received the vectors cannot
hand back anything. That holds for the socket too, where a device's own files are fetched by its
peer and kept there: the peer's copy is the backup, and it exists only because the vectors were
published. Switching back and forth therefore costs one fill per destination, ever, and nothing on
the flicks after that.

That fill is a pass that gives without reading: the destination has only just been named, so there is
nothing of its to take in yet. A pass asked for every vector therefore counts as a full pass on its
own — it was once gated on having read something first, which made the one pass whose whole purpose
was the vectors publish none of them, and a phone whose analysis had been cleared could not be handed
it back.

### Vectors over a socket are asked for, not published

A folder has to be written to speculatively. It is a dead drop: nobody is on the other end to ask,
and the copy lying in it is the only reason a device that lost its analysis can be given it back.
That is why every device offers everything it holds — the folder ends up carrying each vector once
per device that has it, and that redundancy *is* the restorability.

A socket is the opposite. There is somebody there, and the vector they hold in their own store is
already the second copy. Publishing a library's worth into a day file for them to fetch buys
nothing, and the bill arrives in megabytes. Measured on this pair: a phone whose analysis had been
cleared three times held 4334 vectors and had written **59 197 708 bytes** of them into its own
directory, every byte of which the desktop dutifully fetched and then took in **none** of, having
had all of them already.

So over a socket it goes the other way round. A device says which of its tracks have no vector and
is sent only those. Nothing is published that nobody asked for; a device missing nothing asks for
nothing and is sent nothing; and the first exchange after a wipe is a library's worth in one
conversation, fifty records to a message.

Both halves of it are needed because reachability is usually one-sided. **Asking** covers the
caller: it says what it is missing and takes what comes back. **Giving** covers a peer that may be
unable to dial anybody and would otherwise never receive what was analysed on the other side: the
caller asks what the peer lacks and hands over what it holds of it.

The two limits are different on purpose, because a key is a name and a record is a vector. A message
whose answer is records carries at most **50** of either; a message that answers with keys carries up
to **1000**. The giving half needs the larger one: the head of a peer's gap is often a dead end —
tracks neither side has analysed sit there for ever — so asking fifty at a time would let those fifty
decide everything and the thousands behind them would never be reached. Measured exactly that way,
against a phone re-analysing its own library while the desktop had the rest of it in hand: the round
ended on the first fifty and handed over nothing. The round now ends when the peer's list stops
changing, or when the caller holds nothing in it.

The cheap question comes first. `vectors` is two numbers, and if neither side has gained or lost
anything since the last exchange with this peer, the last answer still stands and no keys are
carried to hear it again. Without it, two devices simply missing the same few tracks would trade a
few thousand keys every half minute for as long as both were running. It compares counts rather
than the keys themselves, so a library that swapped one track for another on both counts is noticed
only when something else moves — a delay, never a wrong vector.

The judging does not change. A record that arrives this way is matched and judged by exactly the
code that judges a batch out of a folder: same terms, same matcher, same verdicts. The question "may
I believe this vector" has one answer and it cannot depend on which pipe the record came down.

What a socket destination therefore has in its tree is history, playlists and the mix — and no
`vectors/` directory at all. The one a device wrote before this was true is deleted, and the note of
how far its lines had been read goes with it, since a line count against a file that is gone is a
claim about nothing. A folder destination is untouched by all of this and still gets everything.

### Where the phrase is kept

The phrase is the whole of the trust: the key that seals a batch is PBKDF2 of it salted with the
writing device's id, the key that opens a conversation is PBKDF2 of it under a salt of its own, and
a device that holds it is one of the owner's devices. So where the phrase sits is where the trust
sits — and it sat in plain preferences on the phone and in `settings.json` on the desktop, written
with the permissions the system gives by default, which on that machine is **644**: readable by
every user on it.

It is now kept where each platform keeps secrets.

| | |
| --- | --- |
| phone | wrapped with an AES-256-GCM key in the Android keystore, generated once, non-exportable, in the secure element on hardware that has one. What sits beside it is a nonce and a ciphertext |
| desktop | the Secret Service over the session bus — gnome-keyring or KWallet, the same place the browser keeps its passwords. The app already speaks D-Bus for the media keys, so this needs nothing new |
| desktop without one | a file of its own that only the owner can read. Weaker, and **said out loud** in the settings screen rather than quietly assumed |

The phrase itself is kept, and not a key derived from it, because reading what another device wrote
needs PBKDF2 of the phrase salted with *that* device's id: a device met for the first time has to
be readable, so what is stored has to be what all the derivations start from.

**Nothing may lose a phrase.** A new place is trusted only after it has been read back and found to
say the same thing; the plain copy goes only then. A keyring that accepts a secret and will not
return it, and a keystore that encrypts and then cannot decrypt, both happen on real machines — and
finding out later would mean a library that has quietly stopped being readable by the other device.
A device that keeps the one secret badly is better than a device that has lost it.

A wrapped phrase that will not open — a restore onto another phone, a secure element that was
reset — is not guessed at. It is let go of and asked for again, once, rather than on every pass.

### What a recording is, by its own sound

A track has to be named to another device, and the two descriptions it can be named by both fail in
their own way. Tags and a duration are a judgement call about spelling, and measured on one real
pair of libraries the judgement went wrong 57 times out of 231: 24 artists the two tag readers
disagreed about (`flёur` against `fl¸ur`, `демо` against `äåìî` — each side mangling what the other
read correctly), 15 titles one reader found and the other did not, 18 durations more than a second
apart because one side reads whole seconds from a header. Worse, tags are not unique: 213 keys on
that desktop named **more than one file** — 452 files in all — and the right copy was then chosen by
whichever duration was closer, which is a coin toss. Those 157 tracks could never arrive: the vector
served was the other copy's, its excerpt did not match, and it was refused every time, correctly.

So the bytes — but not the file's bytes. That was the first idea and it is wrong: a file is mostly
tags and artwork at the front, and editing a tag would change the one thing that was supposed to be
exact. What is hashed is the **sound**:

| | |
| --- | --- |
| mp3 and anything else | past a leading ID3v2 tag (its synchsafe size, plus its footer when it says it has one), and before a trailing ID3v1 or APE tag |
| flac | past `fLaC` and the metadata blocks, each of which states its own length |
| wav | the contents of the `data` chunk |
| mp4 and m4a | the contents of the `mdat` box |

Then: **sha256 of the sound's length as eight big-endian bytes, followed by up to 256 KiB from the
middle of the sound.** The middle, because that is also where the excerpt the analysis listens to
comes from; the length, so that a copy cut short is never mistaken for the whole; a quarter of a
megabyte, because that is about fifteen seconds of music and over 300 real mp3s it gave 300 distinct
prints.

**No decoding, deliberately.** A device only carries vectors for formats it shares a decoder with,
but it carries history, playlists and the playlist of the day for everything it can play, and those
name tracks too. An identity that needed a decoder would leave exactly the formats that already lose
the most with nothing but their spelling. Finding where the sound sits needs no decoder, so an ogg
is named as well as an mp3 — and a format this does not know is still named consistently by both
devices, which is all that is asked of it.

It is also the cheapest of the three candidates, by a wide margin:

| | per file |
| --- | --- |
| a slice of the sound | **0.19 ms** |
| the whole file | 3 ms for an mp3, 27 ms for a flac |
| the decoded excerpt | 39 ms |

So a library of four and a half thousand is about a second on a desktop and seconds on a phone, and
nothing has to be scheduled, rationed or explained to the owner.

A print is kept with the **stamp** of the file it was taken from — its length and modification time,
exactly as a refusal records one — and that is the only reason it is ever taken again. One notion of
"this file has changed", not two.

The print travels in the key's `h` field, which the format has carried from the beginning and which
nothing until now computed. `TrackKey.matches` compares hashes alone when both sides have one, and
`TrackMatcher` looks in its hash index before it looks at tags — so two copies of one recording
agree however their tags were written, and two different recordings under one name stop being the
same track.

### A VPN, which takes the room next door with it

A VPN takes the whole routing table, the address of the machine in the next room included. A phone
with one running dials the desktop, the tunnel accepts the connection locally — so `connect`
succeeds and nothing looks wrong from the inside — and then has nowhere to deliver it, because the
other end of the tunnel is in another country and knows nothing about this living room. Measured
exactly that way: every dial from the phone came back as "the conversation would not open", the
desktop never saw a connection at all, and a probe from the desktop to its own port answered
instantly. The route said it plainly: `10.220.68.119 dev tun0`.

This is not an exotic case to leave for later. Where an offline music player is wanted most, a VPN
is ordinary, so "sync works unless you have a VPN" is not a limit this feature can have.

The way round it is per-socket rather than global, so that nothing else in the app leaves the tunnel
the owner put it in: the platform says which network the local devices are on, and each dialled
socket and each beacon is put on that one. On Android that is `Network.bindSocket`, which has to
happen **before** the socket is connected — the one ordering mistake that would silently do nothing
at all — and which the system refuses outright for a VPN set to block connections without it. Then
there is nothing to be done and the owner gets the answer they already get: nobody could be reached.

A desktop needs none of this: a VPN there leaves the routes of its own subnet alone, so a socket to
the phone next door goes out of the right interface by itself.

### Being reachable, which is often one-way

A desktop's firewall is the ordinary case, not the exception. Measured on this machine — Fedora,
firewalld, zone `public`: a phone on the same network could reach **neither** the TCP port nor the
beacon's UDP port, while the desktop reached the phone's TCP port on the first try. Outgoing is not
blocked; incoming is, and the default zone allows ssh and mDNS and nothing else.

Two things follow, and both are built in. First, **whichever side can reach the other dials on its
own**, every half minute while the transport is on, rather than waiting to be called: that is what
makes a one-way path enough, and it is also what keeps the beacon fresh. Second, a listener that has
never been reached says so — *nothing has reached this machine yet* — because that is the one
failure that looks from the inside exactly like everything working.

The owner then has a choice, and the smaller of the two is worth knowing: opening the **beacon's UDP
port alone** is enough, because once this machine can hear the other announce itself it can dial out
to it and the TCP port need never be open. Typing an address works with nothing opened at all, at
the price of a number that changes when the lease does.

### The frames, and finding each other

Every frame is sealed with AES-256-GCM under a key derived from the owner's phrase — the same
phrase, salted with this transport's own name, so that sealing a conversation never uses the key
that seals a record. Each connection then gets a key of its own: both sides send sixteen random
bytes in the clear, and the session key is `HMAC(phraseKey, "aura-session-v1" || clientNonce ||
serverNonce)`. That is what makes counters safe as nonces — a fresh key per connection means a
counter starting at zero cannot repeat one — and each direction counts separately and says which it
is, so the two sides cannot collide either. The counter is authenticated rather than sent, so a
frame replayed, dropped or swapped does not open.

The payload is sealed already, so this layer is not where trust is decided. What it protects is the
*shape* of the conversation: device ids, how many files each side holds, which days they are named
for, how many bytes move and when. On a café's network that is a presence log, and it costs about
thirty lines not to hand it over. There is deliberately **no fallback**: a frame that will not open
ends the conversation, because breaking the protected attempt and letting the client retry in the
clear is the classic way to turn a secure protocol into an insecure one.

Finding each other is a short broadcast every pass — *here is a device, at this port* — on a fixed
UDP port beside the TCP one, with the announcement sealed under the same key. Sealed because a
beacon goes to everyone on the network, repeatedly, and in the clear it would announce a device's id
to a café every few seconds; its nonce is random rather than a counter, since a beacon has no
conversation to count within. A beacon proves only that somebody holding the phrase was recently at
that address, which is all it has to prove: the connection proves the rest, a replayed beacon points
at an address where nothing answers, and a timestamp keeps even that within a minute. Written by
hand rather than taken from mDNS so that both platforms run the same thirty lines — and where a
network drops broadcasts between its clients, which drops mDNS too, there is a field to type an
address into.

A beacon also carries **what the sender is short of** — one number, *how many tracks I have no
vector for* — and that is how a device which cannot dial gets to ask. Under a VPN that forbids
bypassing the tunnel a phone answers conversations and never starts one, so everything it needs
arrives on somebody else's initiative; the broadcast is the one thing of ours that still leaves the
tunnel. A peer that hears the number dials at once instead of on its own timer, which turns a wait
of up to thirty seconds into a round trip. The field is left out entirely when nothing is wanted, so
the ordinary beacon is byte for byte the length it always was and a build that never heard of
errands reads it unchanged; it is sealed with the rest, because how much music somebody is missing
is a fact about their library.

What the number is *for* is the one decision a scan makes before it spends an hour: analysing a
track the other device has already analysed is the only purely wasted work here, and on a phone it
is also battery. So the scan waits — but it waits on events, never on a measured interval. Three
things end the wait: vectors stopped arriving after arriving, the other device has been and gone
without bringing any, or nobody announced themselves at all. A number of seconds would be wrong on
at least one device, because the one doing the waiting may be a cheap phone while the thing it waits
for runs elsewhere, and the two have no reason to be in proportion.

What is still the phrase's job and not pairing's: a code shown on one device and read on the other,
with a random key kept in hardware, is the next step and is what will let the phrase go. The
transport is built so that this changes one function — where the key comes from — and nothing else.

## How often it is written

The thing to notice first is that the file is an outbox, not a journal. Every record in it also sits
in the device's own database, so a phone that dies before a flush loses nothing permanently — the
records simply arrive later. Write frequency therefore buys **freshness on the other device and
nothing else**, which turns an anxious question into an easy one: how stale may the other device be?

Minutes to tens of minutes. History feeds the wave's sense of novelty — how many days a track is
held back — and the daily mix. Half an hour behind means nothing. A day behind means the desktop
offers something that was played on the phone that morning.

What frequent writing does cost is power, not space. History grows slowly: a few dozen plays a day
at some eighty bytes sealed is about three megabytes a year. But Syncthing watches the folder and
notices a change within about ten seconds, and then starts an exchange. On a phone that is a radio
wake-up, which costs disproportionately: the modem stays in a high-power state for seconds after
each transmission. Ten of those a day is nothing; a thousand is the same information at a hundred
times the price.

So: batches, not a stream.

| what | when it is written |
| --- | --- |
| history | twenty records or half an hour, whichever comes first, plus when playback stops and when the app goes to the background |
| playlists and favourites | at once — they are rare, and someone who adds a track and walks to their computer expects to find it there |
| the daily mix | once, when it is built |
| vectors | at the end of a scan, in blocks of about five hundred |

With tracks of a few minutes it is always the half hour that gets there first, so that is the real
knob: a couple of writes per hour of listening, a dozen on a heavy day, each carrying eight or ten
events. The record count is there for the bursts, where a run of skips fills a batch in a minute.
Neither is waited out often, because stopping playback and leaving the app both flush at once — the
end of a listening session is reported when it ends, not half an hour later.

### Append, never rewrite

Syncthing transfers the blocks of a file that changed. Appending changes the last block and adds
new ones; rewriting changes the file. So anything that tidies, compacts or re-sorts a log turns a
cheap sync into an expensive one, every time, for as long as the log exists. The rule is therefore
flat: a file that has been synced once is never written again except at its end.

Which is why history is kept **one file per day**:

```
history/2026-11-05.jsonl
```

Yesterday's file is finished forever. Syncthing stops touching it, a reader that has consumed it can
tell so from its name alone without opening it, the number of files stays in the hundreds rather
than the thousands, and retention — dropping what is older than some cutoff — falls on a day
boundary naturally.

It also sidesteps a platform detail. On Android the folder is reached through the storage access
framework, and appending to an existing document is not something every provider there supports. If
append turns out to be unavailable, a day's records can be written as several whole files inside the
day's directory — `2026-11-05/0003.jsonl` — and nothing else in the design changes, because a day
only ever holds three to five of them anyway.

### Reading

The mirror image, with a belt and braces. A device re-reads the others on start, on a change to the
folder — debounced by about ten seconds, so that a burst of writes is one read — and on a sweep
every half hour regardless. The sweep is there because a file observer over a storage-access-
framework tree is not reliable on every Android version, and a periodic look costs less than
finding out which versions.

Four kinds of line a reader cannot use, and they are not treated alike:

- **A half-written last line.** Left unconsumed, met again next time: the rest of it is on its way.
- **A line that will not parse in the middle of a file.** Stepped over. In a file that is only ever
  appended to, the only line that can be half-written is the last one, so this is damage and
  waiting will not improve it.
- **A sealed line that will not open.** Left unconsumed wherever in the file it is, and the walk
  stops there to keep the rest of the file in order. The phrase on this device is not the one the
  other sealed with, and that does improve by waiting, because somebody fixes it. The count is put
  in front of the owner — *N batches would not open* — because it is the one way sealing fails, and
  nothing else would ever mention it.
- **A vector of nothing but zeros.** Dropped, like a line that will not parse, and for a reason
  worth stating: it is not a vector but one device's refusal to read a file, written before a
  refusal had a record of its own. Taking it in would mark the track analysed on a device that can
  read the file perfectly well. See *A refusal is local, and does not travel*.

## Keeping the two implementations honest

Three things must not drift, and each has a guard:

- `ai/Excerpt.kt` and `ai/Mp3Excerpt.kt` exist in both repositories and must be identical apart from
  the line naming which side it is on. Diff them. So do the other decoders beside them —
  `FlacExcerpt`, `WavExcerpt`, `M4aExcerpt`, `SharedDecoder` — and `ai/AnalysisBacklog.kt`, which
  decides what a scan looks at and when a refusal expires. Forty-one files in all claim a copy, and
  every one of them says so in its own first lines, so the rule a diff checks is "same name, must
  be identical".
- The mp3 decoder has a golden test, `Mp3ExcerptTest`, which decodes a committed 25-second fixture
  and checks the sha256 of the result. It runs on the Android side, where the tests run, and it
  pins the behaviour of the canonical copy: if it fails after a change, every embedding made from
  an mp3 has stopped being comparable and the analysis version has to be raised.
- `ANALYSIS_VERSION` must be the same number on both sides. A vector carries it and is refused if it
  differs, so a mistake here costs a re-analysis rather than a wrong answer.
- The merge itself gets **one fixture, two implementations**:
  `app/src/test/resources/sync/history-fixture.jsonl` is a day's file with every case a reader has
  to survive put in on purpose — a listen told of twice, a device whose clock is two days fast, a
  listen older than the local store keeps, a record naming no track, a missing batch, a batch sealed
  in a way this version cannot read, a line left half-written, and the reader's own batch. The
  expected answer is asserted in `HistoryMergeTest`. If the desktop port folds the same file in and
  gets a different answer, one of the two is wrong, and the history is the one thing in either app
  that cannot be recomputed.
- Everything under `sync/` exists in both repositories and must be identical apart from the line
  naming which side the copy is on, exactly like the two excerpt files. Diff them. Forty-two
  files, the socket transport among them: the records and the track key, the batch envelope, the merge, the device id, the writers
  and the readers for history, vectors, playlists and the mix, the playlist log and its replay, the
  matcher, the terms a vector is believed on, the sealing and the base64 it rides in, the state kept between
  runs, the coordinator, and the interfaces that keep the folder and the stores out of all of it.
- A track is written down in one place and one shape — `TrackKey.written` and `TrackKey.read` —
  because four kinds of record carry one: a listen, a vector, a playlist change, the mix. Four
  copies of those four lines is four chances for a device to describe a track in a way the other
  one reads as a different track.
- Four pairs under `sync/` are deliberately **not** copies, and say so in their own first lines:
  the history store (`PlayHistoryStore` against `PlayLogStore`), the vector store
  (`EmbeddingVectorStore` against `DaoVectorStore`), the playlists (`PlaylistShelf` against
  `M3uShelf`) and the mix (`MixShelf` against `PathMixShelf`). `SafSyncFolder` has no opposite
  number at all: reaching a document tree is a question only Android has, and `FileSyncFolder` —
  an ordinary directory, which both platforms now use — is a copy like the rest.
  Each is how one platform answers a question only it can: how to reach a folder, where listens
  are kept, where vectors are kept and what decoder and excerpt a file of its own would have, and
  whether a playlist is a blob of MediaStore ids or a file of paths. None of them shares a name
  with its opposite number, which is what makes "same name, must be a copy" a rule a diff can
  check. Everything above them sees only the interfaces.
- `SyncCoordinator` is where the order of operations *is* the correctness, and it is the one file
  that could not have been a copy: history is a Room table keyed by MediaStore id on one side and an
  appended log keyed by path on the other. So it is generic over what a platform calls a track, and
  reaches history through `HistoryStore`, which each side implements over its own storage —
  `PlayHistoryStore` and `PlayLogStore`, neither of them shared. A difference between two copies of
  this file would have been a difference in behaviour rather than in spelling, which is why there is
  only one.

## Status

| | |
| --- | --- |
| The excerpt contract | done on both sides, measured |
| A shared mp3 decoder | done on both sides, and confirmed on a phone: same samples, same features, same vector |
| A shared decoder for flac and wav | done on both sides, and checked against ffmpeg on 425 real files: identical sample for sample |
| A shared decoder for m4a | done on both sides, with the pure-Java decoder jcodec carries; checked against ffmpeg on 133 real files |
| A shared decoder for ogg, opus and the rest | not started; those vectors stay at home |
| The record format, the track key and the merge | written and tested, shared code on both sides |
| The envelope, and sealing the payload | done on both sides: AES-256-GCM under a phrase the owner types, plain text until they do |
| The phrase in the device's own keystore | done on both sides: the Android keystore on the phone, the Secret Service on the desktop, a file only the owner can read where there is neither |
| Pairing by code, and discovery beyond one network | not started |
| The device id, the writer and the reader | written and tested, the folder behind an interface |
| Matching a key to a local track | written and tested |
| Naming a recording by its sound | done on both sides: a slice of the sound, no decoding, 0.19 ms a file — see above |
| Attaching all of it to a real folder, and to the two apps | done: the phone writes through a tree the owner picks, the desktop through a folder it picks, both verified end to end |
| The listening history knowing which device heard it | done on both sides, with the older store converted in place |
| Carrying vectors | done on both sides for mp3, with the terms checked rather than assumed |
| Vectors over a socket | asked for by name rather than published: nothing travels that nobody is missing, and a quiet pair is quiet |
| A file that will not decode | remembered as a refusal, not as a vector of zeros: local to the device, expiring with the file, and turned away on reading |
| Vectors whose track is gone | kept, counted and shown; removed only when the owner asks, and never the ones the history still mentions |
| Carrying favourites and playlists | done on both sides, including the order tracks are in |
| Carrying the playlist of the day | done on both sides: one list, agreed on by attempt then device |
| The socket itself | done on both sides: sealed frames, five questions about files, byte offsets, tested end to end over a loopback socket |
| Working with a VPN running | done on the phone: each dialled socket and each beacon is put on the local network, so a tunnel does not take the room next door with it; and a phone that can only answer still asks, by saying in its beacon how many vectors it is missing |
| Finding each other on a network | done: a sealed broadcast every pass, with an address to type where broadcasts do not arrive |
| Sync without a shared folder on Android | done: the files live in the app's own storage, and nothing runs in the background |
| Everything else here | designed, not written |

The excerpt had to come first. Until the two sides agreed on which samples to listen to, a synced
vector described a slightly different ten seconds of the track than the device receiving it would
have, and a quarter of the collection would have been quietly answering the wrong question. History,
playlists and the playlist of the day did not depend on any of it, and were built after.
