# 🚀 GitHub Actions Setup for Automatic APK Releases

This guide explains how to compile your Android Kiosk application (APK) and publish it to a **Public GitHub Release** automatically whenever changes are pushed to the Android app directory or on-demand via GitHub Actions.

## 1. How It Works

We have configured a GitHub Actions workflow (`.github/workflows/build-apk.yml`) that triggers automatically when commits touching the `app/` directory are pushed to the `main` branch (or manually triggered via `workflow_dispatch`). It will:
1. Check out your code.
2. Set up the Java JDK and compile the **Release APK**.
3. Overwrite the `latest` tag in your GitHub repository's **Releases** page.
4. Upload the fresh `.apk` file to this public release.

Because GitHub Releases are 100% public (unlike GitHub Actions Artifacts), your Android device will be able to flawlessly download the APK from the URL without hitting a 401 Unauthorized wall.

## 2. Setting Your APK URL in the Dashboard

Since your web dashboard is hosted on Cloudflare Pages, it needs to point the QR code to this public release URL on GitHub.

1. Open `website/install.html`.
2. Locate the JavaScript section at the bottom.
3. Change the placeholder `apkUrl` to match your GitHub username and repository name:
```javascript
const apkUrl = "https://github.com/YOUR_USERNAME/YOUR_REPO/releases/latest/download/app-release.apk";
```
*(Example: `https://github.com/google/aistudio-kiosk/releases/latest/download/app-release.apk`)*

Now, every time you push code to GitHub:
* Cloudflare Pages will automatically update your dashboard UI.
* GitHub Actions will automatically compile the newest APK and place it at that permanent URL.
* Your QR code will seamlessly download the newest version every time!

## 3. Permissions Requirement

Because this workflow automatically deletes and creates Releases, you must ensure your GitHub repository gives Actions permission to do so.
This is defined in the workflow file (`permissions: contents: write`), but make sure you haven't restricted Actions globally in your repository settings:
1. Go to your repository on GitHub.
2. Go to **Settings** > **Actions** > **General**.
3. Under **Workflow permissions**, ensure it allows writing, or that the defaults aren't overriding the workflow's permissions.
