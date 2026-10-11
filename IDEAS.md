# Ideas not acted on yet

Things that look worth doing but have not earned their complexity yet, with what would settle the
question. Kept here so they are not rediscovered from scratch, and not built on a hunch.

## Taste that knows the hour and the day

**The idea.** Morning and evening listening differ, and so do weekdays and weekends. The wave's
"to my taste" mode and the daily mix both start from a profile that weights listens by how recent
they are and nothing else, so a Saturday night starts from the same place as a Tuesday morning.

**What it would take.** No new data: every listen in `play_events` already carries its timestamp,
so the hour and the weekday are there. Each listen's weight would gain two factors — how close its
hour is to now, falling off smoothly rather than by interval boundaries, measured around the clock
so 23:00 and 01:00 are neighbours; and whether the day type matches, weekday against weekend, which
has four times the evidence behind it that a specific weekday would. Where a slot holds little
history the time-aware profile would be blended with the overall one, in proportion to how much
evidence it has, so a first listen at three in the morning does not produce a profile out of two
random tracks.

The wave could use both factors; the daily mix only the day type, since it is built once and kept
for the day, and an "evening" playlist opened in the morning would read as a fault.

**Why it is waiting.** Measured on 2026-10-11, on 606 listens of 473 distinct tracks over 16 days
(62% heard to the end, 25% skipped, busiest hour 21:00, busiest day Saturday). The answer was no —
twice over, and the first answer was wrong in an instructive way.

Comparing the mean embedding of morning, day and evening listening against random splits of the
same listens made every pair look different, at p < 0.001. That is an artefact: music is listened
to in sessions, an album at a time, so listens in one evening resemble each other because they are
one album, not because it is evening. Shuffling individual tracks destroys that structure and makes
the null far too tight. The real sample is 34 sessions, not 606 tracks.

Permuting whole sessions instead, only day against evening survives (p = 0.009); morning against
day (p = 0.27), morning against evening (p = 0.55) and weekday against weekend (p = 0.055) are not
distinguishable from chance.

And the question that actually decides it — does knowing the hour help predict what will be played?
For each listen, the profile built from the same slot on *other* days scored it 0.627 on average,
against 0.630 for the profile built from all other days: a difference of -0.003 ± 0.004, winning
48% of the time. No gain, even where the music does differ.

So: not enough evidence, and no predictive value in what evidence there is. Worth re-measuring at
around a hundred sessions — roughly two months at the current rate, since sessions are what counts,
not listens.

**How to repeat the measurement.** The database holds everything needed: `play_events` has the
timestamps and `embeddings` the vectors. Build a debug APK with `-Pauraai.testWithReleaseKey=true`
so it is signed with the release key, install it over the release build (same signature, so nothing
is lost), copy `databases/music_database` out with `run-as`, and analyse offline. Split listens
into sessions at gaps over 30 minutes, permute sessions rather than listens, and finish with the
leave-one-day-out prediction check — the permutation test alone will mislead.

## Search in Russian

**The idea.** The AI search encodes the query with CLAP, whose text side is English-only, so a
Russian query produces noise. Plain-text search over titles and artists already works in any
language; this is only about the search by description.

**What it would take.** Of the ready-made options, the multilingual audio-text models (GLAP and
others) all replace both halves of the model: the text tower alone is ~770 MB quantised, no ONNX
export exists for it, and the embedding space is incompatible, so the whole library would need
re-analysing. The cheaper route is to train a small multilingual encoder to reproduce CLAP's text
embeddings on Russian-English sentence pairs — the Multilingual-CLIP recipe. No audio is involved,
the existing embeddings stay valid, and the result is tens of megabytes rather than hundreds.

**Why it is waiting.** It is a training job with an uncertain outcome on music vocabulary, and a
fallback exists: translating the query offline with a ~30 MB ML Kit model would give working
Russian search in a day, at the cost of a dependency on Google services.
