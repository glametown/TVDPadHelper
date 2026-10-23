# Netflix D-Pad Helper for RAY GOLD Projector (Android 12.0)

This lightweight Android app solves the mouse-cursor problem in the mobile Netflix app (`com.netflix.mediaclient`) pre-installed on the **RAY GOLD Android 12.0 Projector** (`CZJPCC50FNHZ2120260225`).

## What It Does
- **Smart Red Focus Frame:** Draws a high-visibility Netflix-red border around movie thumbnails, rows, profiles, and buttons so you can see exactly what is selected across the room.
- **True Directional Navigation (Up / Down / Left / Right):** Jumps between movie cards in 2D using your projector remote's directional keys.
- **Auto-Scrolling at Edges:** Automatically injects smooth swipe gestures when you press Down/Up/Left/Right at the edge of the screen to scroll through rows and categories without needing an air-mouse drag.
- **Video Player Shortcuts:** When a movie or episode is playing full-screen, pressing **Left / Right** skips `-10s` / `+10s`, and pressing **OK / Enter** wakes the player controls and toggles Play/Pause.
- **Mode Switcher (`Menu` key):** Press `Menu` on your remote at any time to switch between **Smart Card Focus** and **Virtual Grid Pointer** mode.

## How to Get the `.apk` File (2 Easy Options)

### Option A: Build in the Cloud Without Installing Anything (GitHub Actions - 2 Minutes)
1. Create a free repository on [GitHub](https://github.com/new) and upload the extracted files from this project folder.
2. Click the **Actions** tab in your GitHub repository and run **Build Netflix D-Pad Helper APK**.
3. After ~60 seconds, download `Netflix-DPad-Helper-APK.zip` from the workflow summary page, extract `app-debug.apk`, and copy it to a USB drive for your projector.

### Option B: Build Locally in Android Studio
1. Open **Android Studio** -> **Open** -> select the extracted `NetflixDpadHelper` folder.
2. Wait for Gradle Sync to finish, then click **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**.
3. Copy `app/build/outputs/apk/debug/app-debug.apk` onto a USB flash drive.

## How to Install & Enable on Your RAY GOLD Projector
1. Plug the USB flash drive into your RAY GOLD projector and install `app-debug.apk` using the projector's File Manager.
2. Open **Netflix D-Pad Helper** from your Apps list.
3. Click **1. Enable D-Pad Service in Accessibility Settings** -> select **Netflix D-Pad Helper** -> switch it **ON** (allow the Android 12 accessibility prompt).
4. Click **2. Launch Netflix Now** and enjoy navigating Netflix with your remote's directional keys!
