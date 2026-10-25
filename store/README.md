# Store listing art

| File | Size | Use |
| --- | --- | --- |
| `icon-512.png` | 512 x 512 | Play app icon (`make_icon.py` draws it) |
| `feature-graphic-1024x500.png` | 1024 x 500, opaque | Play feature graphic |
| `screenshots/phone-01.png` ... `phone-06.png` | 1080 x 2120, opaque | Phone screenshots: menu, heart board, bow board, win screen (butterfly board), Time rush, Duel |

The screenshots and the feature graphic are drawn by `StoreShotsTest` (in `app/src/androidTest`)
from the real game code, real boards and the real HUD, on test-only preferences, so a player's
save is never touched. Play asks for sides of 320 to 3840 px with the long side at most twice
the short one, which is why the shots are 1080 x 2120 and not the phone's own 1080 x 2408.

To remake them, with the phone connected, unlocked and awake:

```powershell
.\run-tests.ps1                     # builds and installs both apps, runs every suite
adb shell am instrument -w -e class com.arrowpuzzle.game.StoreShotsTest com.utkarsh.pathpuzzle.test/android.test.InstrumentationTestRunner
```

(`run-tests.ps1` removes the test app at the end, so run the second command right after an
install, or install `app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk` first.)
Then copy the files out of the app's private storage (the debug build allows `run-as`):

```powershell
cmd /c "adb exec-out run-as com.utkarsh.pathpuzzle cat files/store-shots/phone-01.png > store\screenshots\phone-01.png"
```

The release checklist is in `RELEASE.md`.
