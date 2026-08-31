# Watch Together v2

This version adds host-content synchronization on top of the existing playback sync.

## App changes
- WatchTogetherManager sends OPEN_CONTENT with URL/API/name/episode information.
- MainActivity listens globally so a viewer can follow the host even before the player is open.
- GeneratorPlayer sends OPEN_CONTENT whenever the host loads an episode/content.
- Existing PlayerEventSource.Sync playback handling remains unchanged.

## Cloudflare Worker change
The Worker must preserve contentUrl/apiName/name/episode/season in the Durable Object room state so a viewer joining an existing room can receive the current content.

Replace the Worker with `index-fixed.js` supplied alongside this project, then run:

    npm run deploy

## App build
From the project root:

    ./gradlew :app:assembleDebug

The build was not executed in this environment because Gradle 9.4.1 is not cached and this environment cannot download it from services.gradle.org.
