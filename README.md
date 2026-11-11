# Focus Launcher

A black-and-white Android launcher with three 10-minute slots a day.

[Download the signed APK](https://github.com/joey-david/Olauncher/releases/latest) · Android 10+

- Choose the apps and alerts you allow once per launcher version. Checked apps are allowed outside slots and become your home apps. A separate morning allowlist applies during the first 30 minutes. Saved choices take effect immediately. Other apps open only during a slot.
- Slots run continuously, including while the phone is locked. No pause, extension, refund, or rollover. The count resets at local midnight.
- The first morning unlock starts a 30-minute wait. Home suggests going outside, drinking water, or having breakfast. This uses the first unlock after 04:00, or an unlock after four hours with the screen off, as an estimate of waking. A reboot starts a fresh wait.
- Every unlock shows ten seconds of daily unlocked time, per-app use, and a countdown to the next deadline. Usage tracking starts with this update. Calls and recent incoming texts skip that wait; texts do not change the app or alert rules. A call or text arriving during the wait ends it.
- Quiet notifications appear in a local inbox when you start a slot. Chosen alerts keep their open/reply actions. Calls, alarms, the keyboard, and Android Settings stay available.
- Home shows the next deadline. A deadline has only a date, time, and title. Tap to search, sort by date/name, edit, or remove it.

Outside slots, a reminder appears every two minutes of continuous unlocked use.

Installed apps get a one-time choice for slots-only, outside-slot access, morning access, and alerts.

## Setup

Open **Rules** and choose **Apps** and **Alerts**. Finish your choices independently of Android access. Repair controls stay separate from slot use. System access-repair screens get a five-minute grace period; no slot is spent. On Xiaomi, allow Autostart and set battery use to No restrictions in App info. You can repair access later; changing the choices requires a launcher version update.

Quiet time uses its own Do Not Disturb rule. Turn off other apps' existing DND overrides. Other DND modes can also silence chosen alerts. While a blocked app has a live call notification, its call UI can open. Actual call behavior depends on the calling app and phone; the emulator tests use a separate app's synthetic call notifications.

This is a personal launcher, not device management: Android still lets its owner revoke access, clear app data, or change the launcher. Notification copies stay on the device and do not enter Android backups.

Settings → Check for updates checks this repository for a newer APK. Download it, then use the same control to open Android’s installer. Android may first ask you to allow installs from Focus Launcher. The launcher stays black; wallpaper and theme menus are removed.

## Build and check

Use JDK 21 and Android SDK 37. Set the SDK path in `local.properties`.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
./gradlew :app:assembleRelease
```

Release signing reads the local, ignored `keystore.properties`. The debug app has a separate package. Instrumentation checks run against a local Android emulator and include real notification delivery from the test APK, a synthetic call, unlock timing, app blocking, slots, and deadlines.

Based on [Olauncher](https://github.com/tanujnotes/Olauncher). [GPLv3](LICENSE).
