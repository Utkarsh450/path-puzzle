# Path Puzzle: break-it QA report

Run on a Samsung Galaxy A13 (SM-A135F, Android 14, 32-bit armeabi-v7a: a budget phone, on purpose) and on the
build host. Date: 26 October 2026. Every finding below was fixed and has a test that keeps it fixed.

## What was attacked

| Attack | Result |
| --- | --- |
| Core stress on the host: levels 1 to 1200, odd level numbers (0, negative, INT_MAX, a million), 4,000 daily boards over ten years, 1,500 Duel boards, 9,600 Time rush boards. Every board must be well formed and solvable; a sample is finished by following hints | 16,326 boards, 0 failures. Slowest board 698 ms on the host (level 775) |
| Core misuse: 130,501 random taps and erases with invalid ids, copies, hints | 0 failures, no crash |
| Rules chaos on the phone (`ChaosTest`): random actions incl. invalid ones in the main game, dailies (also an impossible date), Duel and Time rush; finished games hammered; damaged saved games; save/replay round trips | Pass (8 tests) |
| Random touch monkey inside the app (`MonkeyTest`): menus and popups, main game, Time rush, Duel; taps, swipes, double taps, real buttons, back key. About 4,000 actions | Pass, no crash, no freeze |
| Restart abuse: 20 rounds of start, Home, memory-pressure signal, background kill, relaunch, Back | 0 problems |
| Ads offline then online (real ad SDK, test ads) | Ads load by themselves about 15 s after the connection returns (was a bug, fixed) |
| Idle: frames drawn in 12 s on the menu | 0 (no battery drain); memory 140 MB PSS after all the abuse |
| Screen sizes in dp: 320x568, 360x740, 412x915, 600x960, 800x1280, 1280x800 | Layout bugs found and fixed (below) |
| Android lint | 0 errors, 7 warnings (see below) |
| Release manifest audit | No debuggable flag, one launcher activity exported, library services protected by system permissions |

## Bugs found and fixed

1. **A damaged or foreign saved game crashed "Continue game" every time** (one unreadable token made the replay throw, and the
   bad save stayed). Replay now skips what it cannot read. Test: `ChaosTest.testDamagedSavedGamesAreSurvived`.
2. **Ads never loaded in a session that started offline** (the loader gave up and never retried), which costs revenue. It
   now checks quietly every 10 to 60 seconds. Verified on the phone.
3. **The "Path Puzzle" title ran into the coins badge on a 320 dp phone.** The title now fits the room it has.
4. **The Duel card caption ran past its card on 360 dp phones**, the most common width. Text now shrinks to fit
   (`Ui.fitSize`, test `RenderingTest.testFitSizeNeverSpillsOver`).
5. **On short screens the mode cards could be cut off with no way to reach them.** The Main tab now scrolls. Test:
   `RenderingTest.testMainMenuIsReachableOnSmallScreens`.
6. **Privacy policy said the app asks for no other permission and that data never leaves the device.** The ads library adds
   technical permissions and Android backup may copy the save to the player's Google account. Both are now stated.

## Not bugs, but know them

- Lint warnings: portrait-only (Android 16 ignores fixed orientation on big screens; the game still lays out correctly there),
  a Gradle patch update, a redundant resource folder, no accessibility labels (the canvas UI is not readable by TalkBack),
  the unused back-callback attribute. None blocks Play.
- Boards get bigger and slower to build at very high levels: level 775 takes 0.7 s on a fast PC and about 3 s on a budget
  phone, with the loading screen shown. Nobody will reach it soon.
- Level layouts for the same level number can change when the generator changes (shapes were added), so an in-progress
  saved level may load as a different board after an update. It plays normally; the save is not lost.
- Time rush and Duel clocks run only while the game is drawn, and pause under popups.
- A real ad click opens another app (Play, a shop). A monkey that taps ads wanders there: never tap ads in a live build.

## How to rerun

```powershell
.\run-tests.ps1 -Full        # unit + all device tests including the monkey and the screen sizes (about 25 minutes)
.\run-tests.ps1              # everything except those two (about 6 minutes)
.\run-test-class.ps1 MonkeyTest
```
