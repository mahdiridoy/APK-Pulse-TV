# PulseStream TV navigation fixes

This update keeps the phone UI unchanged and makes the Live TV screen remote-friendly on
Android TV and Google TV.

* Press Left from any Live TV channel to focus the persistent left navigation rail.
* The rail remains fully labelled and the Live TV content is laid out beside it, so Movies &
  Series, Search, Library, Downloads, and Settings can be selected directly.
* Press Up from the first row of channel cards to focus the Live TV Search button; press Select
  to open channel search. Search fields and results now have explicit D-pad focus paths.
* Returning from channel search restores the previously focused channel card.
* First-launch Live TV loading coalesces the view-creation and resume requests, preventing
  competing initial network loads; cached channels retain their initial focus and a failed
  cold-start request remains retryable when the screen resumes.

## Build note

The project requires JDK 17 or later. Build with `gradlew.bat :app:assembleDebug` after setting
`JAVA_HOME` to a JDK 17+ installation.
