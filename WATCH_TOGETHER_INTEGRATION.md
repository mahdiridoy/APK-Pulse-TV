# Watch Together integration

This version adds a first-pass Watch Together client using the deployed Cloudflare Worker:

- Settings -> Watch Together
- Create Room
- Join Room
- Disconnect
- Host authentication using the generated host token
- Viewer connections using room code only
- Host PLAY/PAUSE/SEEK synchronization
- Viewer applies incoming events using PlayerEventSource.Sync

Cloudflare endpoint:
https://watch-together-server.pulsestream.workers.dev

Important: this first pass synchronizes playback for the content already open in the viewer. Automatic host browsing/content/episode loading is intentionally left for the next integration step, because the app's result/episode loading path needs to be wired without replacing the existing player.

Build note: the environment used to prepare this archive could not download Gradle 9.4.1 from services.gradle.org, so a full Gradle compile could not be completed here. Run the normal project build locally and fix any dependency/version-specific compile issue before installing.
