# Bifrost Launcher

A dual-screen, dock-aware game frontend and home screen for the AYN Thor.

| Mode | Top screen | Bottom screen | External display |
|------|-----------|---------------|------------------|
| Handheld | The selected console's games, spine-out on a shelf | Consoles (GameCube, Wii, 3DS, ...), Android apps, clock and status | n/a |
| Docked | Off | Off | Everything: consoles, games, clock, status |

## Game library

1. Open **Bifrost settings** and choose your ROMs folder.
2. Put each console's games in a subfolder named like ES-DE (`gc`, `wii`, `n3ds`, `switch`, `ps2`,
   `psx`, `psp`, `nds`, `n64`, `dreamcast`, `gba`, `snes`, ...) or by full name
   (`Nintendo GameCube`). An existing ES-DE ROMs folder works as is.
3. Consoles with games show up on the bottom screen. Pick one and its games fill the top screen.

Every system and emulator ES-DE supports on Android works (about 190 systems), including Dolphin,
Azahar, Eden, NetherSX2, DuckStation, PPSSPP, melonDS, Vita3K, Flycast and RetroArch cores.
Bifrost lends the emulator read access to the game when it starts it, so most standalone
emulators need no folder setup of their own.

### The shelf

Games stand spine-out on a shelf, like a real collection: Wii cases are white with the colored
corner, PS2 black, Switch red, N64 and SNES boxes cardboard, and so on. The selected game slides
out and turns to show its cover.

Starting a game plays the insert: the case opens, the disc, cartridge or card slides out and drops
into the console. On the Thor the case is on the top screen and the console on the bottom one, so
the disc falls from one screen into the other. Docked, it all plays on the TV.

Cases, discs, cartridges and consoles are drawn in code from the box art; no console makers'
images or logos ship with Bifrost.

### Launchers

- **Long-press a console** (or press X/Y on it): choose its default emulator.
- **Long-press a game**: "Launch with..." picks an emulator for that game only.
- Default: the first installed standalone emulator, else RetroArch.
- **Custom launchers**: put `bifrost_systems.xml` and `bifrost_find_rules.xml` in the ROMs folder,
  in [ES-DE's format](https://gitlab.com/es-de/emulationstation-de/-/tree/master/resources/systems/android).
  Entries there add to or replace the built-in ones, then Rescan. `%EMULATOR_com.app/.Activity%`
  works too, without a find rule.

### Artwork

- **Download missing artwork** in settings fetches box art for every game from the
  [libretro thumbnail server](https://thumbnails.libretro.com) (no account needed).
- Long-press a game for **Download artwork**, **Set artwork...** (any image) or **Remove artwork**.
- Long-press a console to give it its own artwork.
- Switch and other systems without libretro thumbnails need custom artwork.

### Controller

- **Left / right**: along the shelf, **L2 / R2**: jump 10 games
- **A**: play, **X / Y**: menu, **L1 / R1**: previous / next console, **Start**: settings
- Touch: swipe the shelf, tap a spine to pull it out, tap the cover to play.

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

Toggle it in **Bifrost settings > Docking**.

If a built-in screen stays dark after unplugging (for example, the launcher was killed while
docked), press the power button twice. Android powers the panels back on when it wakes.

## Android apps

The **Android apps** console lists installed apps, games first. Long-press one to open it on the
bottom screen.

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

- Systems and emulator launch commands are ES-DE's
  ([ES-DE Frontend](https://es-de.org), MIT, see `app/src/main/assets/ES-DE-LICENSE.txt`).
- Box art comes from [libretro-thumbnails](https://github.com/libretro-thumbnails/libretro-thumbnails).

- The `PServerBinder` transaction format was documented by
  [Thor Wayfinder](https://github.com/Thor-Wayfinder/thor-wayfinder). No code was copied.
- The Android 14+ `DisplayControl` lookup follows [scrcpy](https://github.com/Genymobile/scrcpy).

## License

MIT
