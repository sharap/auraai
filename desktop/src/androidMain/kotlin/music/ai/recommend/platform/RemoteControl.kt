package music.ai.recommend.platform

/** Android publishes playback through MediaSession in PlaybackService, not from here. */
actual class RemoteControlService actual constructor(remote: PlaybackRemote) {
    actual fun start() {}
    actual fun stop() {}
}
