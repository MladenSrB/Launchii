# Launchii: One UI style launcher (One UI 8.5 / Galaxy S26 look) for Pixel and other Android phones

A from-scratch Android launcher (Kotlin, View-based, no third-party launcher code) that reproduces Samsung One UI 8.5's Home screen. Values come from One UI Home 17.5.02.15 and S26 Ultra firmware S948BXXU2AZDE; see `../ONEUI85_EXTRACTED_SPEC.md` (research notes kept outside this repo).

## Download

**[Launchii.apk](https://github.com/MladenSrB/Launchii/releases/latest/download/Launchii.apk)**
is the latest signed build from this repository. Every push to `main` builds and
publishes a new APK automatically through GitHub Actions.

Install this APK once to switch to this repository's update channel. When Home
comes to the foreground, it checks GitHub at most every six hours and shows an
**Update** pill for a newer version. Tap it to download and install the update;
Android may ask you to allow installs from Launchii or confirm installation.
The download is checked against the release's size and sha256 when available.

See [automatic update setup](docs/automatic-updates.md) for signing secrets,
version numbering, and local build instructions. Updates must use the same
signing certificate as the installed APK.

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

## Foldables and resizable windows

Launchii adapts to the current window bounds when moving between the cover and inner display.
Home keeps the same saved grid coordinates, with a centred workspace (up to 840dp), a centred dock
(up to 520dp), and capped icon sizes. Apps keeps the configured column count on both displays:
four per row by default, without automatic expansion. **Rotate Home screen** is off by default
and follows the user's rotation setting when enabled.

Resizing retains the Home page, the first visible app row, and an open search's query. Active drags,
folder panels, edit mode, and resize handles close before rebuilding their geometry. System bars
and display cutouts contribute to safe padding. On wide windows, widgets fit inside their allocated cells
with their span aspect ratio preserved and their content centred; the size reported to providers matches
these actual content bounds. Cancelling or dismissing a resize gesture does not save new spans.
This is window-size adaptation; hinge avoidance,
tabletop posture layouts, and separate cover/inner-display workspace arrangements are not implemented.

Manual checks on a foldable or resizable emulator:

- Fold and unfold on a non-first Home page; check the page and saved item positions stay the same.
- Scroll the Apps list, then unfold; check the previously visible app remains in the visible row.
- Type a search, then fold/unfold; check the text and results return and the keyboard works.
- Resize during an icon/widget drag, folder animation, or edit mode; check no stale overlay remains.
- Check four Apps columns on both cover/inner displays, then test rotation, landscape, and cutout padding.
- Check widgets update their content for their new physical size without losing their widget IDs.

## App transitions and click handling

App launches progressively blur the launcher scene (up to 28dp); returning Home clears the blur over
420ms, with the Home grid, widgets and dock shrinking together from 1.14× to 1× over 520ms.
Zoom In enlarges the desktop to 1.22×, Smooth shrinks it to 0.80×, and wallpaper zoom reaches 1.12×.
App motion uses iOS-inspired curves with a prompt response and soft settling tail; Zoom In shares a
680ms, lightly overshooting spring across the card, desktop and wallpaper. These are tuned approximations,
not measured iOS timing values. The effect is separate from drawer/search blur. The wallpaper follows
the same blur radius: static copies stay visible during the transition, Launchii's live wallpaper renders
the blur in its own surface (excluding the lock screen and previews), and wallpapers without a local copy
use cross-window blur when the device allows it. Low-RAM devices
and devices with system animations disabled skip this effect.

Non-card launches retain the original system-close completion callback and 400ms fallback.
Zoom In waits for the full opening-mask expansion animation to finish normally and its final frame
to be drawn before dispatching the native app launch. It no longer dispatches at the screen centre or
before predicted coverage. Native app starts request zero system enter/exit animations. Current-profile
app icons, widget activity senders, contacts and settings pages also use FLAG_ACTIVITY_NO_ANIMATION;
profile and shortcut launches use their public APIs with zero-animation options and no icon-origin bounds.
The launcher mask, desktop motion, blur and mask fade remain enabled. Target apps' own content animations
and system/profile overrides cannot be guaranteed to follow these requests.
The mask is an extension of the displayed icon: samples come only from the outer opaque contour,
are smoothed around that perimeter, and form a directional gradient field rather than a dominant-color fill.
In system dark mode, the extended mask is darkened by 85% (15% RGB brightness); its alpha and
foreground icon remain intact. The original icon remains in the centre. Empty transparent directions borrow adjacent perimeter colors.
Palettes are cached per bitmap; unreadable icons use a fallback color.
The expanding/retracting mask has speed-responsive blur up to 14dp, clearing at both endpoints;
the fully expanded handoff frame remains sharp. Once the native launch is dispatched, the mask itself
fades out and blurs up to 18dp over 200ms.
Retracting or detaching cancels and restores this mask effect. Low-RAM devices omit the extra blur,
and disabled system animations skip this exit effect. It does not apply blur to the target app's content.
Home, screen-off, cancelled cards, new requests and destruction invalidate a pending mask launch.
The experimental frame-dispatch queue, frozen intermediate state and late-intent
suppression have been rolled back. Blur, the return shrink, foldable layouts and unlock reveal remain.
Return targets use final screen coordinates, and the floating icon hands back to the real icon as soon as
the system close finishes, even if desktop scaling is still running, so there is no invisible gap.
The landing icon is excluded from the return ripple; surrounding icons use 1.6× displacement
pulling toward the landing icon at return start with a distance-based stagger. Their rebound starts
320ms into the return, overlapping the landing tail instead of
waiting for the icon and desktop to stop. A 480ms damped spring softens the reversal and settling.
Starting a new app cancels this return displacement.
Once a new app is selected, opening gestures cannot replace it with
another app. Tapping the next app during a return remains supported, including the system-close wait.
A new launch immediately releases the previous return surface and restores its real icon.
Interrupted opening cards remain opaque while retracting; already fading masks are removed immediately.
Detached icon/widget origins fall back to the normal zoom. Deep shortcuts share this launch path and report
failures instead of silently swallowing them. Widget IntentSender launches preserve their original options
and include the Android 14+ sender opt-in (visible-only on Android 16+).

Home screen settings also includes **Unlock animation** (on by default). After the system keyguard is
dismisses into Home, icons, widgets and Dock items fade in and expand outwards with a short stagger.
The reveal starts at authentication/foreground entry without waiting for input focus, so it can overlap
the system window transition. Late user-present/focus/entry-complete notifications skip the reveal instead
of starting a second animation after the system transition. Secure and non-secure swipe locks use their
respective authentication/dismissal state. This animates only the launcher, not the lock screen itself. Touch, screen-off and window resizing cancel
and restore the reveal; system-disabled animations are respected. Unlocking into another app must not
cause a delayed reveal when returning Home later.

Additional device checks:

- Lock/unlock directly into Home using PIN and biometrics; check a single reveal and try tapping mid-reveal.
- Unlock into another app, then return Home after a few seconds; check no stale reveal plays.
- Fold/unfold a 2×2 widget repeatedly, including during a resize gesture; check its saved spans stay 2×2.

- Open/return from a cold and warm app using Home, Apps, folders, and search; check blur clears completely.
- With Zoom In, launch icons from different rows and the dock on both foldable displays; check native
  dispatch waits for the full expansion and the final drawn frame. Cancel midway and check no app starts.
- Check directional perimeter extensions with colored, white-tray, themed, transparent and monochrome icons;
  centre artwork must not change the sampled outer-ring palette.
- Check the app appears after the mask fully expands without a second system zoom, including warm/cold
  starts, shortcuts, widget links and another user profile.
- During app opening, tap other icons repeatedly: only the first app should launch.
- At early/middle/late stages of a return, tap a different app, widget or shortcut: compare behavior
  with the original launch handling, including its system-close wait and fallback.
- Repeat with Zoom In, Smooth and each installed live-wallpaper effect; check their original effects play.
- Press Home before a launched app covers the desktop, then tap another icon while the card retracts.
- Return from an app that closes immediately; check the desktop and launch card recover normally.
- Test widget activity links and non-activity actions such as play/pause, including after a RemoteViews update.
- Cancel a widget drag or resize/fold during a long press; check the next normal tap still works.
- Try disabled/deleted shortcuts and invalid widget actions; check a failure is visible and Home recovers.

## Predictive Back

Launchii explicitly opts in to Android's system Back APIs. On Android 14+, the Apps drawer follows
`OnBackAnimationCallback` progress: it shrinks/fades as Home appears, commits only on invocation,
and animates back to the same drawer and scroll position on cancellation. A new gesture can catch a
cancelled gesture while it is settling. Screen locking, app launching and window resizing clear an active
preview. Android 13 keeps the existing invoked-only Back path, including button navigation.

Pop-up and full-screen folders also follow Back gesture progress using their existing close morph:
card, preview icons, Home dim/scale and wallpaper blur move together. Cancellation restores the same
folder and page; invocation completes the close. New gestures can catch a cancellation settling back.
Search exits when a previously shown keyboard finishes hiding; initial hidden insets and cancelled
keyboard gestures do not dismiss it. Other launcher sheets retain their existing Back actions. Settings activities retain platform Back handling, allowing system
back-to-home/cross-activity/cross-task previews when supported by the OS. The terminal Home screen
keeps its no-op Back behavior. Other apps must support predictive Back themselves; Launchii cannot
opt another package in or replace the system's task-window animation.
External-app back-to-home previews also depend on system launcher/Quickstep integration; enabling
callbacks in Launchii alone does not provide the stock launcher's remote window animation.

Device checks:

- On Android 14+, swipe Back partway in Apps and cancel: check the drawer, scroll position and blur recover.
- Commit the same gesture: Home should appear smoothly; repeat cancellation and re-grabbing quickly.
- Repeat Back preview, cancellation, commit and re-grabbing in pop-up and full-screen folders, including
  a non-first folder page. The preview must not navigate until invocation.
- Hide the keyboard using Back or its dismiss button in Home search and Apps search: search should exit.
  Cancel a keyboard Back gesture and check search stays open; opening search must not close it before IME appears.
- Rotate/fold, lock, or launch an app during a preview; check no partial transform is left behind.
- Check Back with an open search, folder, widget picker or popup still performs that surface's normal action.
- Check system predictive Back from a settings page and a compatible external app; on Android 13/14,
  system back-to-home previews may require the Predictive Back developer option.
- Check three-button navigation remains functional and Back on Home does not close the launcher.

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
