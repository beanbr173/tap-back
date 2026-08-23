# TapBack

A simple Android check-in app: send a ping to someone you care about. They tap the popup on their phone. You get notified that they tapped back. No texts, no location sharing — just ping / pong.

**Permanent download link (always latest):**  
https://github.com/beanbr173/tap-back/releases/latest/download/tap-back.apk

## How it works

1. Both people install TapBack.
2. One person creates a short code; the other enters it.
3. Either of you taps **Send check-in**.
4. The other phone shows a popup. Tapping it means "I'm here."
5. The sender gets a notification that they tapped back.
6. Both phones keep a log: sent, received, and acknowledged.
7. Optional: a daily scheduled check-in, fired by the server even if the sender's phone is off.

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

### Firebase secrets (for popups)

In Firebase: Project settings → Service accounts → Generate new private key. Then:

```bash
npx wrangler secret put FCM_PROJECT_ID
npx wrangler secret put FCM_CLIENT_EMAIL
npx wrangler secret put FCM_PRIVATE_KEY
```

On the Android side, download `google-services.json` for package `com.kreativesolutions.tapback` and place it at `app/google-services.json` (gitignored). See [`app/google-services.json.example`](app/google-services.json.example). Rebuild the APK after adding it.

## Cloud build (no Android Studio required)

Pushes to `main` trigger [GitHub Actions](.github/workflows/build-apk.yml):

- JDK 17, Android SDK 36, `./gradlew assembleDebug`
- Debug APK artifact (30 days) + GitHub Release with stable filename `tap-back.apk`

Manual run: **Actions → Build APK → Run workflow**.

## Install (sideload)

1. Download [tap-back.apk](https://github.com/beanbr173/tap-back/releases/latest/download/tap-back.apk) on an Android 10+ phone.
2. Allow installs from your browser or file app if prompted.
3. Paste the Worker URL, enter your name, and pair with a code.

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
