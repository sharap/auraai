package music.ai.recommend.sync

/**
 * A sync folder, which is both somewhere to write and somewhere to read. Named so that the parts
 * above it can ask for one thing instead of two that have to be the same thing.
 */
interface SyncFolder : SyncSink, SyncSource
