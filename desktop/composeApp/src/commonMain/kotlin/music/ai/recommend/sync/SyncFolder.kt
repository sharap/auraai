package music.ai.recommend.sync

/**
 * A sync folder, which is both somewhere to write and somewhere to read. Named so that the parts
 * above it can ask for one thing instead of two that have to be the same thing.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
interface SyncFolder : SyncSink, SyncSource
