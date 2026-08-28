# TapBack

A simple Android check-in app: send a ping to the people you care about. They tap the popup on their phone. You get notified that they tapped back. No texts, no location sharing — just ping / pong.

**Permanent download link (always latest):**  
https://github.com/beanbr173/tap-back/releases/latest/download/tap-back.apk

## How it works

1. Everyone installs TapBack.
2. One person creates a family code. Mom, a sister, anyone else — they all enter the **same** code. There is no limit on how many people can join.
3. Tap **Check in with everyone**, or check in with one person.
4. Their phones show a **full-screen** alert and ring even if the phone is on silent. Tapping **I'm here** means they got it.
5. You get a notification that they tapped back.
6. Everyone keeps a log: sent, received, and acknowledged.
7. Optional: a scheduled check-in for one person (or everyone), fired by the server even if the sender's phone is off.

## Hosting — this can stay free

There are **no SMS fees**. Alerts are push notifications, not text messages.

| Piece | What it does | Cost |
|---|---|---|
| **Cloudflare Workers + D1 + KV** | Pairs phones, stores the log, fires scheduled check-ins | **Free** for this kind of personal use ([Workers](https://developers.cloudflare.com/workers/platform/pricing/): 100,000 requests/day; [D1](https://developers.cloudflare.com/d1/platform/pricing/): 5M reads / 100k writes / day; 5 cron triggers on the free plan) |
| **Firebase Cloud Messaging** | The actual popup on Android | **Free**, unlimited |

That is enough for family check-ins. You will not get a bill unless you far exceed those limits.

You need two free accounts:

1. [Cloudflare](https://dash.cloudflare.com/sign-up) — deploy the API in [`worker/`](worker/)
2. [Firebase](https://console.firebase.google.com/) — enable Cloud Messaging so Android can show popups

**Live API:** https://tapback-api.beanbr-labs.workers.dev

Until Firebase is connected, the app still talks to the Worker. Popups will not appear in the background; the receiver would need the app open. Connect FCM before using it for real.

## Deploy the API (Cloudflare)

Cloudflare is already created for this project:

- Worker URL: `https://tapback-api.beanbr-labs.workers.dev`
- D1 database `tapback` (`210d71d3-b7dc-4a57-9763-897b0cfbb972`)
- KV namespace `tapback-cache` (`a6c9981877bc479694dadf4a8aa4f9d8`)

From [`worker/`](worker/) after changing the Worker:

```bash
cd worker
npm install
npx wrangler deploy
```

Copy the Worker URL (like `https://tapback-api.<your-subdomain>.workers.dev`) into the app's **Server URL** field.

### Firebase (required for lock-screen popups)

Do this in a browser. Do not paste keys into chat.

1. Open [Firebase Console](https://console.firebase.google.com/) and create a project named `tapback`. Google Analytics can be off.
2. Add an **Android** app with package name **`com.kreativesolutions.tapback`**. Nickname: TapBack. Skip SHA-1.
3. Download `google-services.json` and save it as:

   `D:\working\play_store_apps\tap_back\app\google-services.json`

4. Project settings (gear) → **Service accounts** → **Generate new private key**. Save that JSON as:

   `D:\working\play_store_apps\tap_back\worker\fcm-service-account.json`

Both files are gitignored. After they are on disk, say so in chat and they will be wired into the Worker + GitHub Actions, then a new APK will be built.

## Cloud build (no Android Studio required)

Pushes to `main` trigger [GitHub Actions](.github/workflows/build-apk.yml):

- JDK 17, Android SDK 36, `./gradlew assembleDebug`
- Debug APK artifact (30 days) + GitHub Release with stable filename `tap-back.apk`

Manual run: **Actions → Build APK → Run workflow**.

## Install (sideload)

1. Download [tap-back.apk](https://github.com/beanbr173/tap-back/releases/latest/download/tap-back.apk) on an Android 10+ phone.
2. Allow installs from your browser or file app if prompted.
3. Paste the Worker URL, enter your name, and join with a family code.
4. On each receiving phone, tap **Allow display over other apps** (and full-screen alerts / unrestricted battery if shown). That is what lets a check-in cover the whole screen and still ring on silent.

## Browser preview

Open [`preview/index.html`](preview/index.html) to click through the ping / tap-back / log flow without building the APK.

## Project layout

| Item | Value |
|------|--------|
| Display name | TapBack |
| Folder | `tap_back/` |
| Package | `com.kreativesolutions.tapback` |
| minSdk | 29 |
| targetSdk | 36 |
| Stack | Kotlin, Jetpack Compose, Firebase Cloud Messaging |
| API | Cloudflare Worker + D1 in [`worker/`](worker/) |

## License

MIT
