# Play Console answers for Path Puzzle

Copy these into Play Console. Your privacy policy link is
https://utkarsh450.github.io/pathpuzzle-privacy/

## Store listing

**App name** (30 characters max): `Path Puzzle`

**Short description** (80 characters max):
`Slide arrows off the board. Shapes, daily puzzles, Time rush and Duel.`

**Full description** (4000 characters max):

```
Path Puzzle is a calm, clever puzzle game. Every board is full of arrows, and each one can only slide away if nothing blocks its path. Tap the right arrow, clear the board, and watch the picture empty out. A wrong tap costs a heart, so look before you tap.

HOW IT WORKS
Tap an arrow and it slides along its line until it leaves the board. If another arrow is in the way, it bumps back and you lose one of your three hearts. Clear every arrow to finish the level.

BOARDS IN ALL SHAPES
Levels are drawn in shapes: hearts, bows, butterflies, houses, moons, flowers, mushrooms and more. Each board is a new puzzle, and they get harder as you go, from Normal to Hard, Expert and Master.

DAILY CHALLENGE
A new board every day. Finish a whole month to win its trophy.

TIME RUSH
Sixty seconds on the clock. Clear arrows quickly to build a combo and multiply your points. A blocked arrow costs time, and every cleared board adds more. Beat your best score.

DUEL
Two players, one phone. Take turns clearing one arrow each. A blocked arrow or a slow turn costs a heart. Most points wins.

HELP WHEN YOU NEED IT
Hints show you a safe arrow. The eraser removes one arrow, the magic wand makes a move for you, and the grid guide helps you line things up.

DARK AND LIGHT
A clean dark look by default, with a light theme in one tap.

PLAY ANYWHERE
The whole game works offline, and your progress is saved on your phone. The game shows ads, including optional ads you can watch for a free heart or booster.
```

**Category:** Game, Puzzle. **Tags:** Puzzle, Casual, Single player.
**Contact email:** your own address (it is shown publicly on the listing).

**Graphics:** `store\icon-512.png` (app icon), `store\feature-graphic-1024x500.png` (feature graphic),
`store\screenshots\phone-01.png` to `phone-06.png` (phone screenshots).

## App content forms

| Form | Answer |
| --- | --- |
| Privacy policy | the link above |
| Ads | Yes, the app contains ads |
| App access | All functionality is available without special access |
| Content rating | Puzzle game. No violence, no sexual content, no profanity, no drugs or gambling, no user-to-user chat, does not share location. Expect a rating for everyone |
| Target audience | 13 and older (13 to 15, 16 to 17, 18 and over). Do NOT choose under 13, or AdMob ads would not be allowed |
| News app | No |
| Government app | No |
| Financial features | None |
| Health features | None |
| Advertising ID | Yes, the app uses it (for advertising, via AdMob) |
| Data deletion | The app stores data only on the device; there is no account to delete |

## Data safety

Does the app collect or share user data? **Yes** (through the ads SDK only).

| Data type | Collected | Shared | Purpose | Optional |
| --- | --- | --- | --- | --- |
| Device or other IDs (advertising ID) | Yes | Yes (Google AdMob) | Advertising or marketing | Required |
| Approximate location (from IP address) | Yes | Yes (Google AdMob) | Advertising or marketing | Required |
| App activity: ad interactions | Yes | Yes (Google AdMob) | Advertising or marketing, analytics | Required |
| Diagnostics: crash logs and performance | Yes | Yes (Google AdMob) | Analytics | Required |

Everything else (name, email, contacts, photos, files, audio and so on): **not collected**.
Data is encrypted in transit: **Yes**. Users can request data deletion: **No** (nothing is kept on a server).
Game progress stays on the phone and is not collected.

## Testing and release

1. Testing, Closed testing, create a track, upload `app\build\outputs\bundle\release\app-release.aab`.
2. Add at least 12 testers by email (a list or a Google Group). Each must open the opt-in link and accept.
3. Keep at least 12 testers opted in for 14 days in a row.
4. Then use "Apply for production" in the Dashboard and answer the questions about your test.
5. Each update needs a higher versionCode in `app\build.gradle.kts` before running `.\build-release.ps1`.
