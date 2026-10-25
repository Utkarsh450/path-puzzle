# Path Puzzle (Kotlin + C++)

A from-scratch Android rebuild of the arrow-escape puzzle: tap an arrow and it slides out
along its own path; if another arrow is in the way it bumps back and you lose a heart.
Three mistakes and the game is over.

## Layout

| Path | What it is |
| --- | --- |
| `app/src/main/cpp/` | C++17 puzzle core: deterministic level generator, exit-ray probing, solver, JNI bridge |
| `app/src/main/java/com/arrowpuzzle/game/` | Kotlin: `GameView` (Canvas rendering, input, HUD, menus), `Game` (rules, animation), `Sfx`/`Haptics`, `Store` |
| `native-tests/core_test.cpp` | Host test: every level is well-formed, deterministic and solvable; dense boards are fully covered by long arrows with few free at the start |
| `app/src/test/`, `app/src/androidTest/` | JVM unit tests and on-device integration tests (`run-tests.ps1`) |
| `prototype-web/index.html` | First single-file web prototype (reference only, not maintained) |

Rules match the real game: an arrow leaves only if nothing is on its path to the edge,
including its own body; otherwise it turns red and you lose a heart (three hearts per level).

Levels come in Normal / Hard / Expert / Master tiers sized against the real game (level 9
has ~54 arrows, dailies ~180). From level 4 boards are fully tiled with long winding
snakes (3+ cells; stubs are merged into neighbours), then "peeled" into a guaranteed
solution: each arrow's head goes on whichever end can leave at that point, ends that would
be free from the start are used only as a last resort, and a deadlock is broken by cutting
an arrow in two instead of deleting it. A hardening pass then flips arrows inward to
lengthen the dependency chain. Several layouts are generated per level and the one with
the deepest chain and fewest free-at-start arrows wins. Special mechanics (portals, stop points, turn markers, lock zones,
two-way and magnetic arrows) are implemented but switched off (`kMechanicsEnabled` in
`puzzle_core.cpp`, `Mechanic.ENABLED` in `Board.kt`).

## Modes

- **Classic**: the level ladder, plus one daily challenge per day.
- **Time rush** (`Rush.kt`): 60 seconds to clear as many arrows as you can. Clears less than
  2 s apart build a combo (x2 after four in a row, up to x5); a blocked arrow costs 3 seconds
  and the combo; a clear board adds 10 seconds and the next, slightly larger board follows.
  The best score is kept and pays up to 50 coins.
- **Duel** (`Duel.kt`): two players on one phone take turns, one arrow each, a point per
  arrow. A blocked arrow or a turn over 8 seconds costs that player one of three hearts.
  Most points when the board is clear wins (then hearts, else a draw). No coins.

Mode boards use `kVariantFlag` salts (`Levels.modeSalt`) with a round number, so every duel
and every rush board is a fresh layout below the first mechanic level. Duels and rushes are
never saved and have no boosters; their clocks stop under popups.

## Build

```powershell
.\build.ps1            # assembleDebug
.\build.ps1 -Install   # also installs on the connected device and launches it
```

Release for Google Play: `.\build-release.ps1` makes the signed `.aab`; the checklist, the
privacy policy page and the store icon are in `store/` (`RELEASE.md`).

Core tests (any C++17 compiler):

```bash
g++ -std=c++17 -O2 native-tests/core_test.cpp app/src/main/cpp/puzzle_core.cpp -o core_test && ./core_test 1500
```

Android tests:

- `app/src/test` (JVM): level rules, Duel and Time rush rules and result text, easing, arrow motion maths, colour blending, particles, constant tables.
- `app/src/androidTest` (device): the native core through JNI and `Board`; `GameState`
  (hearts, boosters, second chance, animations, save/replay); Duel and Time rush on real boards
  (`DuelIntegrationTest`, `RushIntegrationTest`); `Store`; rendering of the board,
  previews, every menu tab and popup into bitmaps; and end-to-end UI flows on the real
  `MainActivity` with injected touches (menus, settings, winning, losing, restart, resume after
  relaunch, boosters, daily, duel and time rush). The UI tests run on their own prefs file and
  check that the player's save is unchanged; the game stays installed.

```powershell
.\run-tests.ps1            # unit + integration
.\run-tests.ps1 -UnitOnly  # JVM unit tests only
```

## Design

The UI follows ChaiUI (ui.chaicode.com; the `chaiui` and `chaiui-review` skills are in
`.claude/skills`): dark by default (`#0a0a0a`) with a light theme (Settings, or the half-circle
switcher on the main menu), Manrope / Montserrat / Onest (OFL, bundled in `assets/fonts`), one
solid button per screen with asymmetric corners, warm hairline cards with no shadows, the
cream highlight phrase, status as coloured text or thin outlines, sentence case and no em
dashes. Tokens live in `Theme.kt`; the toolkit (buttons, cards, chips, switch, icons and the
hexagon glow backdrop, drawn in code) in `Ui.kt`. `ChaiThemeTest` and `CopyRulesTest` check
the rules. All artwork is drawn in code; no assets from the original game or chaicode.com are
included.
