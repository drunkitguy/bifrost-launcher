# Bifrost Launcher

A dual-screen, dock-aware game frontend and home screen for the AYN Thor, laid out like Cocoon.

| Mode | Top screen | Bottom screen |
|------|-----------|---------------|
| Handheld | Full-bleed art: the console's scene on its color, or the game's fan art / title screen with its logo. Status pills, play time, info | Black grid: consoles (console, logo, year), then a console's games as their discs or cartridges. Header with back, sort and list toggle |
| Docked | Off | Off |

**Docked, one screen made for the TV** instead of the two screens pasted together: the art fills
the whole display, the game's logo, play time and year sit top-left, the status pills top-right,
and the consoles or games run in a single row along the bottom.

## Game library

1. Open **Bifrost settings** and choose your ROMs folder.
2. Put each console's games in a subfolder named like ES-DE (`gc`, `wii`, `n3ds`, `switch`, `ps2`,
   `psx`, `psp`, `nds`, `n64`, `dreamcast`, `gba`, `snes`, ...) or by full name
   (`Nintendo GameCube`). An existing ES-DE ROMs folder works as is.
3. Consoles with games show up on the bottom screen. Open one to see its games as discs or
   cartridges; the top screen shows the art of whatever is selected.

Every system and emulator ES-DE supports on Android works (about 190 systems), including Dolphin
(GameCube, Wii), Azahar (3DS), Eden and Eden Nightly (Switch), NetherSX2, DuckStation, PPSSPP, melonDS, Vita3K, Flycast and RetroArch cores.
Bifrost lends the emulator read access to the game when it starts it, so most standalone
emulators need no folder setup of their own.

### Game info

Highlighting a game looks up its release year and description on Wikipedia (once, then kept on
the device). **Y** (or the ⓘ button) shows them over the art with the emulator, last played and
play time. Play time is tracked from launch until you're back in Bifrost; while a game runs, the
bottom screen shows it with a Stop button (closing a game needs root, which a stock Thor has).

### Themes

Settings > Look > Theme: Midnight, Snow, Neon, Crimson, Ocean, or your own packs. A pack is a
folder in `ROMs/bifrost_themes/` with a `theme.json`:

```json
{ "background": "#101418", "surface": "#1A1F27", "accent": "#7AA2FF",
  "text": "#FFFFFF", "dim": "#9AA0A6", "light": false, "wallpaper": "wallpaper.png" }
```

and optionally that wallpaper image.

### Launchers

- **Long-press a console** (or press X/Y on it): choose its default emulator.
- **Long-press a game**: "Launch with..." picks an emulator for that game only.
- Default: the first installed standalone emulator, else RetroArch.
- **Custom launchers**: put `bifrost_systems.xml` and `bifrost_find_rules.xml` in the ROMs folder,
  in [ES-DE's format](https://gitlab.com/es-de/emulationstation-de/-/tree/master/resources/systems/android).
  Entries there add to or replace the built-in ones, then Rescan. `%EMULATOR_com.app/.Activity%`
  works too, without a find rule.

### Artwork

- **Download missing artwork** in settings fetches box art, game logos and title screens from the
  [libretro thumbnail server](https://thumbnails.libretro.com) (no account needed).
- With a free [SteamGridDB](https://www.steamgriddb.com) API key (settings) it also fetches wide
  fan art for the top screen, like Cocoon's. Switch games get theirs from there too.
- Long-press a game for **Download artwork**, **Set artwork...** (any image) or **Remove artwork**.
- Long-press a console to give it its own artwork.
- Switch and other systems without libretro thumbnails need custom artwork.

### Controller

- **D-pad**: move, **A**: open / play, **B**: back, **X**: options, **Y**: game info
- **L1 / R1**: previous / next console, **Select**: grid / list, **Start**: settings
- Touch: tap to select, tap again to open, long-press for options; the sort pill cycles
  Recent / A–Z / Most played.

## Docked mode

Plugging in a USB-C/HDMI dock (or casting to a wireless display) switches modes automatically:

1. The single-screen layout opens on the external display and takes controller input.
2. Both built-in screens go black, stop taking input, and hide their system bars.
3. If root is available, both built-in panels are also powered off (see below).
4. Games and apps launched while docked open on the external display.
5. Pressing Home brings Bifrost back to the front of the external display.

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
- System logos and console art: [Slate for ES-DE](https://gitlab.com/es-de/themes/slate-es-de)
  (CC BY-NC-SA), downloaded at runtime. Logos are trademarks of their owners.
- SVG rendering: [AndroidSVG](https://github.com/BigBadaboom/androidsvg) (Apache 2.0).
- Game descriptions and years: [Wikipedia](https://en.wikipedia.org) (CC BY-SA), credited on screen.
- Box art and game logos come from [libretro-thumbnails](https://github.com/libretro-thumbnails/libretro-thumbnails).

- The `PServerBinder` transaction format was documented by
  [Thor Wayfinder](https://github.com/Thor-Wayfinder/thor-wayfinder). No code was copied.
- The Android 14+ `DisplayControl` lookup follows [scrcpy](https://github.com/Genymobile/scrcpy).

## License

MIT
