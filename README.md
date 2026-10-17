# Bifrost Launcher

A dual-screen, dock-aware home screen for the AYN Thor.

| Mode | Top screen | Bottom screen | External display |
|------|-----------|---------------|------------------|
| Handheld | App grid (controller or touch) | Clock, date, battery, network, storage, controllers, quick actions | n/a |
| Docked | Off | Off | Everything: clock, status, app grid, quick actions |

## Docked mode

Plugging in a USB-C/HDMI dock (or casting to a wireless display) switches modes automatically:

1. The dock screen opens on the external display and takes controller input.
2. Both built-in screens go black, stop taking input, and hide their system bars.
3. If root is available, both built-in panels are also powered off (see below).
4. Apps launched from the dock open on the external display.
5. Pressing Home brings the dock screen back to the front of the external display.

Unplugging closes the dock screen and brings both built-in screens back.

### Panel power-off

Black pixels on OLED are close to off, but the panels are still powered. With root, Bifrost turns
the panels off for real (`SurfaceControl.setDisplayPowerMode`, run through `app_process`). On a
stock Thor no setup is needed: it uses AYN's built-in root service (the one behind
Settings > "Run script as root"). It falls back to `su` on rooted devices.

Toggle it with the **Docked: built-in screens powered off / black** button on the bottom or dock
screen.

If a built-in screen stays dark after unplugging (for example, the launcher was killed while
docked), press the power button twice. Android powers the panels back on when it wakes.

## Handheld mode

- **A**: open the selected app (on the top screen)
- **X / Y** or long-press: app menu (open on bottom screen, app info)
- Games (apps marked as games) are listed first, then everything else A to Z.

## Install

1. Download `bifrost-launcher.apk` from the [latest release](../../releases/latest).
2. Install it, press Home, and choose **Bifrost** as the default home app
   (or use the **Home app** button).

Builds are signed with the committed `debug.keystore`, so new builds install over old ones.

## Build

```sh
./gradlew testDebugUnitTest assembleDebug
```

Requires JDK 17 and the Android SDK (platform 35).

## Credits

- The `PServerBinder` transaction format was documented by
  [Thor Wayfinder](https://github.com/Thor-Wayfinder/thor-wayfinder). No code was copied.
- The Android 14+ `DisplayControl` lookup follows [scrcpy](https://github.com/Genymobile/scrcpy).

## License

MIT
