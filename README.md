# 📱 tidtam — App Usage & Call Monitor

> A native Android app that reports a phone's app-usage and call activity to **Telegram** in real time, with an automatic daily summary at midnight.
> Built with Android's official `UsageStatsManager` (the same API Digital Wellbeing uses) — **no screen scraping, no spyware**.

![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Language](https://img.shields.io/badge/language-Kotlin-blue)
![minSdk](https://img.shields.io/badge/minSdk-26-orange)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

---

## ✨ Features

- 📱 **App open/close alerts** — Telegram message when an app is opened, and when it's closed (with time spent).
- 📞 **Accurate call tracking** — incoming / outgoing / missed calls with real talk-time, measured from the system call state so it stays correct **even when the screen is off**.
- 🌙 **Daily summary at midnight** — per-app open counts and total time, delivered automatically via WorkManager.
- ⏹️ **Real screen-time only** — counts only the truly-foreground app, so YouTube Picture-in-Picture popups and background Google Maps navigation **don't inflate the numbers**.
- 💾 **Local-first storage** — sessions kept in a Room database on-device; auto-prunes data older than 30 days.

---

## 🏗️ Architecture

```
┌──────────────────────────────┐        ┌────────────────────┐
│        Monitored phone        │        │     Your phone     │
│                               │        │                    │
│  UsageMonitorService (FGS)    │        │                    │
│   ├─ UsageStatsManager poll ──┼─ HTTP ─┼──►  Telegram Bot   │
│   ├─ CallTracker (call state) │        │    (you read here) │
│   └─ Room DB (history)        │        │                    │
│                               │        │                    │
│  DailySummaryWorker ──────────┼─ 00:05 ┼──►  daily summary  │
└──────────────────────────────┘        └────────────────────┘
```

| Layer | Tech |
|-------|------|
| Language | **Kotlin**, Coroutines |
| Usage detection | `UsageStatsManager` (foreground `RESUMED`/`PAUSED` events, 1.5 s poll) |
| Call detection | `TelephonyCallback` (API 31+) / `PhoneStateListener` (legacy) |
| Background exec | Foreground **Service** + **WorkManager** (daily job) |
| Storage | **Room** (SQLite) |
| Networking | **OkHttp** → Telegram Bot API |
| UI | View Binding, Material 3 |

---

## 🔍 Engineering highlights

A few design decisions worth calling out:

- **Chose the right API over the obvious one.** The naive approach — reading the screen — is fragile and invasive. `UsageStatsManager` yields exactly the "which app / how long" data through a permission the user explicitly grants.
- **Picture-in-Picture is handled for free.** Because time is counted only for the full-screen foreground activity, a YouTube PiP bubble or backgrounded Maps navigation automatically stops the timer the moment the user leaves the app.
- **Calls are tracked out-of-band.** Screen-based timing would under-count calls (the screen turns off against the ear), so call duration is measured from `CALL_STATE` transitions instead, and the dialer package is excluded from the usage poll to avoid double counting.
- **Survives reboots and OEM battery killers** via a `BOOT_COMPLETED` receiver, a sticky foreground service, and a battery-optimization opt-out prompt.

---

## 🚀 Build & Run

1. Open the project in **Android Studio** (latest) and let Gradle sync.
2. **Build → Build APK(s)** → the APK lands in `app/build/outputs/apk/debug/`.
3. Install the APK on the target device (enable "install from unknown sources").

### Telegram setup (one-time)
1. Message **@BotFather** on Telegram → `/newbot` → get the **Bot Token**.
2. Send your bot any message, then open
   `https://api.telegram.org/bot<TOKEN>/getUpdates` and read the `"chat":{"id": ...}` value — that's your **Chat ID**.
3. In the app: grant permissions → paste Token + Chat ID → **Send test message** → **Start**.

---

## ⚠️ Limitations

- `UsageStatsManager` has a small delay (not per-second real time) — fine for this use case.
- **Messenger chat-head / floating bubble overlays are not reported by Android to any app**, so overlay chatting can't be tracked — only full-screen Messenger counts.
- Some OEM ROMs (Xiaomi/Oppo/Vivo) aggressively kill background services; enable Autostart + set battery to "unrestricted".

---

## 🔐 Privacy & intended use

This is a **family-care / personal project**: it requires the "Usage Access" permission to be granted **on the monitored device itself** — it cannot be installed covertly, and the person being monitored should be aware. The Telegram token is entered at runtime and stored on-device; **no credentials or personal data are committed to this repository**.

---

## 📄 License

[MIT](LICENSE) © Tanapat Chaithong

---

<a name="thai"></a>

# 🇹🇭 ภาษาไทย

แอป Android ที่แจ้งเตือนการใช้แอปและการโทรของโทรศัพท์เครื่องหนึ่งไปยัง **Telegram** แบบเรียลไทม์ พร้อมสรุปการใช้งานอัตโนมัติทุกเที่ยงคืน — ใช้ `UsageStatsManager` (API ทางการ ตัวเดียวกับ Digital Wellbeing) **ไม่ได้อ่าน/แคปหน้าจอ**

### ฟีเจอร์
- 📱 แจ้งเตือนตอนเปิด/ออกจากแอป (พร้อมจำนวนนาที)
- 📞 จับเวลาโทรแยก (โทรออก/รับสาย/สายไม่ได้รับ) แม่นยำแม้จอดับ
- 🌙 สรุปการใช้งานรายวันตอนเที่ยงคืน
- ⏹️ นับเฉพาะแอปเต็มหน้าจอ → ป๊อปอัพเล็ก (PiP) และนำทางพื้นหลังไม่ถูกนับ
- 💾 เก็บประวัติในเครื่องด้วย Room (ลบข้อมูลเก่ากว่า 30 วันอัตโนมัติ)

### วิธีใช้ (ย่อ)
1. เปิดใน Android Studio → **Build APK** → ติดตั้งบนเครื่องเป้าหมาย
2. สร้าง Telegram Bot กับ **@BotFather** เอา Token + Chat ID
3. ในแอป: เปิดสิทธิ์ → ใส่ Token/Chat ID → ส่งข้อความทดสอบ → เริ่มติดตาม

### หมายเหตุความเป็นส่วนตัว
เป็นโปรเจคส่วนตัว/ดูแลครอบครัว — ต้องกดอนุญาต "Usage Access" บนเครื่องที่ถูกติดตามเอง ติดตั้งลับไม่ได้ และควรให้เจ้าของเครื่องรับรู้ / ไม่มีการเก็บ token หรือข้อมูลส่วนตัวไว้ใน repo นี้
