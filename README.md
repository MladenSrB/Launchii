# Launchii: One UI style launcher (One UI 8.5 / Galaxy S26 look) for Pixel and other Android phones

A from-scratch Android launcher (Kotlin, View-based, no third-party launcher code) that reproduces Samsung One UI 8.5's Home screen. Values come from One UI Home 17.5.02.15 and S26 Ultra firmware S948BXXU2AZDE; see `../ONEUI85_EXTRACTED_SPEC.md` (research notes kept outside this repo).

## Download

**[Launchii.apk](https://github.com/deciocrytek/Launchii/releases/latest/download/Launchii.apk)**: always the latest
release. Open it on your phone and allow installs from your browser. Updates install over the previous version.

When a newer release is out, Home shows an **Update** pill left of the Search pill (it checks GitHub once a day).
Tapping it downloads the APK, checks it against the release's size and sha256, and installs it. The first time,
Android asks you to allow installs from Launchii; after that, updates go through without asking.

Publishing a new build (same link): bump `versionName`/`versionCode` in `app/build.gradle.kts`, then run
`scripts/publish-apk.sh`. It has to be signed with the same key as the installed copies (`signing/`), so publish
from the machine that has it, not from CI.

## Build and install on the Pixel 10 Pro XL

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease bundleRelease      # R8-minified APK (sideload) + AAB (Play)
adb install -r app/build/outputs/apk/release/app-release.apk
```

Then:

1. **Set as Home app**: press Home and pick "Launchii", or go to Settings › Apps › Default apps › Home app.
2. **Wallpaper effects** (One UI's blur, tone curve and 1.3× app open/close zoom): set your wallpaper from edit mode › Wallpaper and style › *Choose a photo*. The launcher crops it to the display and keeps an exact copy, so no permission is needed and the effects line up perfectly. A wallpaper set elsewhere can't be read (that needs All files access, which Play doesn't allow for launchers), so the launcher falls back to window blur plus an overlay that approximates the curve.
3. **Badges**: open Home screen settings › App icon badges and allow notification access.
4. Use **gesture navigation**. Swipe-home uses GestureNavContract, so the app window flies into the icon.

Settings live in edit mode (pinch, or long-press empty space) › Settings, or in the apps screen › ⋮ › Settings.

## What's reproduced (and where the numbers come from)

| Area | Implementation |
|---|---|
| Layout | Samsung fractions: 5.7% top, 4.1% indicator, 12.4% dock, 3.8% sides; the dock sits above the navigation bar inset. 4×6 home and apps grids. Icon is 66% of a cell (15.25% of width at 4 columns) |
| Icons | `config_icon_mask` squircle, emboss highlight (SRC_OVER) and shadow (MULTIPLY) regenerated to within about 1.4/255 of Samsung's, `#19000000` night layer, legacy 8-point crop/contain on a `#FEFEFE` tray at 0.72, colour-palette themed icons |
| Typography | Inter variable font (`assets/fonts/Inter-VF.ttf`, OFL, licence in `assets/licenses/`) in place of One UI Sans, 13dp labels with a 2px shadow, letter-spacing shrink before ellipsis |
| Home ⇄ Apps | progress = drag/284dp; 0.94 scale, enter/exit interpolators, ±86dp slide; fling > 1 px/ms, commit > 0.4; duration `max(100, 1200/max(2,|v|·0.5)·rem)`; cubic or quintic ease-out |
| Paging | fling 500dp/s, 40% significant move, quintic scroller, Samsung snap formula (×5, 900 ms cap), 0.07 overscroll with 0.96 bounce |
| Background | blur(p×radius) + Samsung colour curve (15–255 → 53.5–232) + `#A6000000` × 25% (apps), 20% (folder), 25% (edit); half-strength blur while dragging |
| App open | home scales to 0.85 over 800 ms (0.4,0,0,1); wallpaper zooms 1.3× over 800 ms (0.05,0.3,0,1); window scales up from the icon |
| App close | GestureNavContract icon target (the app's icon on the current page or dock, else the folder holding it, or its large-folder slot; matched by package), content back over 350 ms (0.33,0,0.1,1), touch-ripple wave from that icon or the screen centre |
| 8.5 ripple | neighbours move d/50 toward the touch, 6 ms per unit stagger, 650 ms (0.807,0.586,0.266,1.628) |
| Folders | full folder (title 34sp/600, 79.2%-wide tray, 3×3 in preview order), 350 ms morph from the icon (0.17,0.34,0.3,1), flying previews, background fade 150 + 200 ms; 3×3 icon preview; five One UI colours; large 2×2 folders with the 8.5 stacked preview; tapping empty space closes; optional Good Lock Home Up pop-up folders |
| Quick options | 20dp glass popup on a blurred backdrop, real app shortcuts, global options, show 200/350 ms, hide 200 ms |
| Drag & drop | pickup 317 ms then 1→0.8→1 "breathe"; Remove target; edge paging; folder creation by hover (1.1×); hovering elsewhere on an occupied cell pushes items to the nearest free spots; single-app folders dissolve |
| Edit mode | pages at 0.69 scale, spring 225/0.67 in and 360/0.99 out, frosted page cards, "+" page, bin above each page to remove it, Wallpaper / Themes / Widgets / Settings panel |
| Widgets | AppWidgetHost (kept listening in the background), 26dp corners, picker that loads in the background, long-press drag to move (items in the way slide aside), resize frame (spring 200/0.7) |
| Apps screen | 8.5 floating 244×52dp search pill + ⋮ button, vertically scrolling A–Z grid; search above the keyboard with Google suggestions, results get the full long-press menu |
| Other | badges (`#F54D13`, 32.7% circle at 0.8/0.2), wallpaper-adaptive dark labels and status bar, dark mode, settings screen, hardware layers during transitions |

## Platform limits on Pixel (not fixable from a launcher)

- **Recents** is Pixel Launcher's Quickstep. A third-party launcher can't replace it without root.
- **App-open window animation** is drawn by the system. We supply the icon origin and animate everything behind it.
- **Unlock animation** is SystemUI's keyguard.
- Samsung-only surfaces (Now Brief, Edge panel, Discover/media page) aren't available.
- Live wallpapers can't be read, so they get window blur instead of the exact curve and zoom.

## Release and licensing

- Package `app.launchii`. Release builds are signed with the Play upload key in `signing/` (not versioned; back it up). Without it they fall back to the debug key. Store listing, screenshots, privacy policy and Play Console answers are in `../store/`.
- Nothing from Samsung ships in the app: the UI font is Inter (SIL OFL 1.1, shown under Settings › Open source licences). The original One UI Sans font is kept in `../reference/fonts/` for reference only. Don't put it back in `assets/`.
- The measured motion and layout values come from studying One UI. The emboss overlay PNGs in `res/drawable-nodpi` were generated by `../scripts/gen_emboss.py`; they aren't Samsung's files.

## License

Launchii is free software under the [GNU General Public License v3.0](LICENSE).
