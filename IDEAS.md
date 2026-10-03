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

**Why it is waiting.** The history only starts at 2026-09-24. Before building this, look at a month
of it and check whether the pattern is there at all: do the tracks played between 7:00 and 10:00
actually differ from those played after 20:00, and by more than chance? A local library of a few
thousand tracks is small, and an hour slot may hold too few listens to say anything. If the data
shows nothing, not building this is the better answer.

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
