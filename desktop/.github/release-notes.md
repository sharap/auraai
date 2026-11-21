The first packaged build of the desktop port. A local music player that finds tracks by what they
sound like, not only by what their tags say. Nothing leaves the machine.

## Install

```
sudo apt install ./aimusic_*_amd64.deb
```

The package declares what it needs, so apt pulls in libVLC and ffmpeg if they are not already
there. Verify the download against `SHA256SUMS` if you like:

```
sha256sum -c SHA256SUMS
```

## First run

The CLAP model weights are not in the package — 408 MB of them, and they are not this project's to
redistribute. Open **Settings → AI Models** and press Download; they come from Hugging Face once,
land in `~/.aimusic/models/`, and are checked against a known size and sha256 before use. An
interrupted download resumes.

Then point the app at your music folder and run the AI scan. It takes roughly a second per track,
and it is what everything below is built on.

## What it does

- **Search by description** — "calm piano", "epic cinematic orchestral". The query and the audio
  are embedded into the same space, so the match is on sound rather than on tags. Results stop
  where they stop standing out from the rest of your library for that particular query.
- **Smart albums** — the library grouped by sound and named automatically.
- **Playlist of the day** — a fixed playlist each morning, built from what you finished and what
  you skipped, with part of it reserved for tracks you have not heard in a while.
- **Play similar** — queues the nearest tracks to the one playing.
- **Media keys and panel widget** — the player publishes MPRIS on the session bus, so the
  keyboard's media keys, the desktop's player widget, Bluetooth headset buttons and `playerctl`
  all drive it.

## Known limitations

- Linux only for now. The build produces `.dmg` and `.msi` targets too, but neither has been
  tested; only the `.deb` is published here.
- x86-64 only.
- The AI scan is single-threaded by design and takes about an hour for a four-thousand-track
  library. It can be stopped and resumed — analysed tracks are not re-analysed.
- Tracks the decoder cannot read are stored as a zero vector and simply never surface as
  recommendations.

## Where things are kept

Everything lives under `~/.aimusic/`: `embeddings.bin` (the analysis), `play_history.bin` (what
you listened to), `models/` (the weights), plus settings, playlists and the cached smart albums.
Deleting that directory resets the app without touching your music.
