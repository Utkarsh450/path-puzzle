# Releasing Path Puzzle on Google Play

## Build the bundle

Two kinds of bundle, both signed with the upload key and copied to `dist\`:

```powershell
.\build-release.ps1 -TestAds   # TEST: Google's sample ads only. For internal and closed testing.
.\build-release.ps1            # LIVE: your real AdMob ids. For production only.
```

**Rule: testers only ever get the TEST bundle.** A real ad unit must not be clickable by testers
(clicks on your own ads count as invalid traffic and can limit the AdMob account). The file name says
which is which: `dist\path-puzzle-v<versionCode>-TEST-ads-only.aab` or `...-LIVE-real-ads.aab`.
Before the first production release, build the LIVE bundle with a higher versionCode than any test one.

For every upload raise `versionCode` (a whole number, +1 each time) in `app\build.gradle.kts` first;
Play refuses a versionCode it has already seen. Version 1 (real ads) went to internal testing by
mistake; version 2 is the TEST build that replaces it. The next live one must be 3 or higher.

## The upload key

`signing\pathpuzzle-upload.jks` and `signing\keystore.properties` (it holds the password) are
the upload key. **Copy both to a safe place now** (a password manager or private cloud
storage). They are not in the project's version control on purpose. Turn on Play App Signing
when you create the app (it is the default): Google keeps the real signing key, so if you ever
lose the upload key Google can reset it. Without a backup and without Play App Signing, a lost
key means you can never update the app.

## Ads (AdMob)

Publisher id `pub-1339411307978525`. The game shows a rewarded ad (optional: a heart after
running out of lives, or a free booster) and, every third completed level from level 6 on and
never within two minutes of another ad, a full-screen ad between levels. Never in a duel, a
Time rush or on the first levels. With no internet there are no ads and nothing waits for one.

1. In AdMob add the app: Apps, Add app, Android, "Path Puzzle", package `com.utkarsh.pathpuzzle`
   (before it is on Play, say it is not published yet; link it after launch).
2. Create two ad units (a Rewarded and an Interstitial) and copy the App ID and both unit ids
   into `signing\ads.properties` (template: `signing\ads.properties.example`). Without that
   file every build shows Google's TEST ads, which pay nothing; debug builds always do.
3. AdMob, Privacy & messaging: create a consent message (GDPR) and publish it, otherwise EU
   players see no consent form. Also turn on the US states regulations message if offered.
4. After the app is live on Play, link it in AdMob and publish `store\app-ads.txt` at
   `https://<your website>/app-ads.txt` (the website domain is set in the Play listing).
5. **Never tap your own live ads** or let friends do it; that gets the AdMob account banned.
   Test with the debug build only (it shows labelled test ads).

Play Console: tick "Contains ads"; data safety = collects data: device or other IDs
(advertising ID), approximate location (IP), app interactions and diagnostics, shared with
Google for advertising, collection optional/not optional as the form asks; target audience
13 and older (not 'designed for children', or AdMob ads are not allowed).

## Before the first upload

1. Create a Google Play Console developer account ($25 once, ID check).
2. The privacy policy is live at https://utkarsh450.github.io/pathpuzzle-privacy/ (GitHub Pages,
   repository Utkarsh450/pathpuzzle-privacy, source `store\privacy-site\index.html`). Paste that
   URL into Play Console, App content, and into AdMob's consent message. To change the text,
   edit the file in that repository.
3. App content forms: data safety as in the Ads section above (it collects advertising data; not a news/health/finance app),
   target audience 13+ is simplest, content rating questionnaire (puzzle, no violence).
4. Store listing: name "Path Puzzle", the 512 x 512 icon `store\icon-512.png`, a 1024 x 500
   feature graphic, 2 to 8 phone screenshots, short (80 characters) and full descriptions.
5. A new personal developer account must run a closed test with at least 12 testers for 14
   days before it can publish to production. Create a closed testing track, upload the
   `.aab`, add testers by email, wait the 14 days, then apply for production access.

## Checks run on every release candidate

```powershell
.\run-tests.ps1      # JVM + on-device suites
```

and the native core test (see the README). Then `.\build-release.ps1`.
