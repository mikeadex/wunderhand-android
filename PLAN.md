# Wunderhand for Android: build plan

Status: **A0 to A6 built; A7 built and waiting on three things from you** · 19 September 2026 · see [Progress](#progress) at the end

A native Android app for the **pro side** of Wunderhand, written in Kotlin with Jetpack Compose. It
is the same product as the iOS app in `../wunderhand`: the diary, clients, menu, money and shop that
braiders, barbers, stylists and tattooists run their day from. It targets phones, tablets and
foldables, and uses the real `chairtime` backend from day one.

This plan is shorter than the iOS one for a reason: **the hard half is already done.** The iOS
plan spent most of its length on the backend — `/api/v1`, its auth, its money scope, its contract
tests. All of that exists, is in production, and is platform-neutral. What is left is an Android
client for an API that already works, plus one backend piece: push.

---

## 1. What we are building from

### Already there, and reused as it is
| | |
|---|---|
| The API | `/api/v1` in chairtime: bearer token, `X-Tenant-Id`, errors as `{error:{code,message,field?}}`. Nothing in it is Apple's. |
| The rules | Ownership (`lib/auth/owner.ts`), money scope, eligibility, refunds, prices — all decided on the server. The app never recomputes them. |
| The contract | 29 JSON fixtures written from real responses (`tests/fixtures/api-v1`). The iOS tests decode them; the Android tests will decode the same files. |
| The words | Every sentence the iOS app says was taken from the web or written once in `WHCore` (`SignalWords`, `LeavingWords`, `UnlockWords`…). They are ported, not reinvented. |
| The design | The `.desk` palette, Archivo, the radii and the component rules in the iOS plan §4.4. Lucide icons, which ship for Android too. |
| The two ways out | Delete my login and Close this shop exist on the web **and** in the API, which is what Google Play asks for (§9). |
| The demo shop | `kit@fold.example` on production, Fold Barbers. Same account for Play review. |

### What the iOS build taught, so Android starts with it
These were found by building, and cost time. Here they are requirements from the first commit.

1. **Decode tolerantly.** A field added after the app shipped is optional in the app; a word the
   app has not heard of (`mine: "something-new"`) becomes the safe case, never a crash. A test
   decodes an *older* reply for every screen that matters.
2. **A non-owner's screens make no owner-only requests.** `/menu/options`, `/shop/*` answer 403
   `not_owner`. The non-owner path builds from what it already has.
3. **Tests never hard-code the seed.** "6 people" became 24. Match shapes, not counts.
4. **The ways out, the ownership rules and the empty/offline states are part of each screen's
   milestone**, not a hardening pass at the end.
5. **No price, plan or upgrade prompt anywhere.** The Plan row says seats and outlets and leads
   nowhere.
6. **Health records are never cached, never in a screenshot, and open behind biometrics.**
7. **chairtime's own tests run against a real database** — never run its full suite from here, and
   never push, open a PR or merge there without asking.

---

## 2. Decisions

| Decision | Choice |
|---|---|
| Language and UI | **Kotlin, Jetpack Compose, Material 3** themed to the `.desk` tokens. No XML layouts, no Fragments. |
| Where it lives | `~/Documents/mobileNative/wunderhand-android`, its own git repository beside `wunderhand` and `chairtime`. |
| Application ID | **`com.wunderhand.app`** — the same as the iOS bundle ID, which is the convention and keeps deep links and the Play listing tidy. |
| Minimum Android | **API 26 (Android 8.0)** — `java.time` without desugaring, adaptive icons, notification channels. About 97% of devices. Target and compile **API 36**. |
| Devices | Phones, tablets and foldables from day one, by window size class — the Android answer to "iPhone and iPad". |
| Data | Real chairtime backend from day one. No mock layer. Local development against `http://10.0.2.2:3100` (the emulator's name for the Mac's localhost). |
| Business logic | Stays on the server. |
| Offline | Read-only cache of `Me` and one diary day per shop, as iOS. Changes need a connection. |
| Appearance | Light only in v1, as iOS and the web. **No dynamic colour** (Material You): the brand palette is the palette. |
| Architecture | Single activity. One state holder per feature (a `ViewModel` exposing `StateFlow`), an `AppModel` for the session phase. **No DI framework** — a hand-written `AppContainer`; the app is small enough that Hilt would be the largest thing in it. |
| Dependencies | As few as Android allows (§4.3). iOS managed none; Android cannot, because Compose, coroutines and push are libraries there. Every one is Google's or JetBrains', plus OkHttp. |
| Push | Firebase Cloud Messaging — the only route to an Android phone. chairtime sends through FCM's HTTP API with a service account, as it does APNs with a key: no Firebase SDK on the server. |
| Distribution | Google Play: internal testing, then closed, then production (§9 has a date that matters). |

---

## 3. Backend: what chairtime still needs

One piece, for milestone A7. Everything else is done.

### 3.1 Push for Android
Today push is Apple's in three places. `lib/push/provider.ts` was written as the only door
("nothing else in the app knows APNs exists"), so the change stays behind it.

| Where | Today | Change |
|---|---|---|
| `POST /api/v1/devices` (`DeviceRegistration`) | `token` must be 64–200 hex characters; `environment` is `sandbox` or `production` | Add `platform: 'ios' \| 'android'`, defaulting to `'ios'` so the shipped iOS app keeps working untouched. For `android` the token is FCM's (not hex, up to ~4 KB) and `environment` is not asked for. |
| `device_tokens` table | No platform | Migration: `platform text not null default 'ios'`. Run on production by hand, as always. |
| `lib/push/apns.ts` | The only sender | Add `lib/push/fcm.ts`: an OAuth token from the service account (a signed JWT, same shape of work as APNs), then `POST https://fcm.googleapis.com/v1/projects/{id}/messages:send`. `provider.ts` splits messages by platform and calls each. An `UNREGISTERED` reply deletes the token, as APNs' 410 does. |
| Console stand-in | Prints when `APNS_*` is missing | Same when `FCM_*` is missing. |
| Non-owner pushes carry no price | Enforced where the message is written (`lib/push/words.ts`) | Nothing to do — it is above the platform split. A test says so for Android too. |

**Data messages, not notification messages.** FCM can draw a notification itself, but then the app
cannot attach "Offer the gap" or choose the channel. chairtime sends `data` only (title, body,
appointment id, shop id, kind); the app draws the notification. High priority for a booking or a
cancellation, so Doze does not hold it.

### 3.2 App Links
`app/.well-known/assetlinks.json` as a **route**, not a static file, and in `PUBLIC_FILES` by exact
path — the same two traps the Apple file hit (no content type under `nosniff`; the sign-in
middleware redirecting Google's fetch, which switches verification off silently). It names the
package and the SHA-256 of the **Play app-signing** certificate, which is only known once the app
exists in Play Console — plus the debug key's, for development.

### 3.3 Small
- The support page says "The iPhone and iPad app". It becomes "the app" with both stores named.
- Contract tests: one for `POST /devices` with `platform: 'android'`, one for a refused iOS-shaped
  token on Android and the reverse.

Branch from `origin/main`, ask before pushing, PR, ask before merging.

---

## 4. Android app architecture

### 4.1 Project (A0)
- Android Studio project, Gradle Kotlin DSL, a **version catalog** (`gradle/libs.versions.toml`) so
  every version is in one file.
- `minSdk 26`, `targetSdk 36`, `compileSdk 36`. Kotlin 2.x with the Compose compiler plugin. JDK 21
  (already on this Mac, as are the SDK, platforms 35–36.1 and the emulator).
- Build types: `debug` (`API_BASE_URL = http://10.0.2.2:3100`, application ID suffix `.debug` so it
  sits beside the store build) and `release` (`https://wunderhand.com`, R8 on, resources shrunk).
- **Cleartext traffic** allowed only in the debug network-security config, only for `10.0.2.2` and
  `localhost`. Release has none.
- Orientation: portrait on phones, free on tablets and foldables (Android 16 ignores orientation
  locks on large screens anyway, so the layouts must be right in both).
- `enableEdgeToEdge()`; every screen handles insets. Predictive back supported.
- `en-GB` strings in `strings.xml`. 24-hour times whatever the phone says, as iOS.
- `git init` with an Android `.gitignore`. `keystore.properties`, `google-services.json` and any
  `.jks` are ignored from the first commit. A private GitHub repo only when you say.

### 4.2 Layout
The iOS package had three libraries so its logic could be tested without a simulator. Same split,
same reason: `:core` is **pure Kotlin on the JVM**, so its tests run in seconds with no emulator.

```
mobileNative/wunderhand-android/
├─ settings.gradle.kts · build.gradle.kts · gradle/libs.versions.toml
├─ app/                          the application module
│  └─ src/main/kotlin/com/wunderhand/app/
│     ├─ app/                    MainActivity · AppContainer · AppModel (session) · RootScreen ·
│     │                          DeepLinks · Reachability · PushService · NotificationActions
│     └─ features/
│        ├─ auth/                SignIn · ShopPicker  (forgot password opens the web reset)
│        ├─ diary/               DiaryScreen · Agenda · DayGrid · TeamGrid (wide) · WeekView · WeekStrip ·
│        │                       StaffChips · AppointmentSheet · Reschedule · BlockTimeSheet
│        ├─ booking/             Service → Person → Extras → Time → Confirm
│        ├─ clients/             ClientsScreen · ClientProfile · ClientForm · HealthRecord · NotesGate
│        ├─ waitlist/ checkout/ money/ menu/
│        └─ shop/                ShopScreen · the editors · AccountScreen · CloseShopScreen
├─ core/                         pure Kotlin/JVM — no Android imports
│  ├─ src/main/kotlin/           API types · Pence · Durations · ShopClock · DiaryGeometry · Snapping ·
│  │                             Loadable · the words (Signal, Leaving, Unlock…) · OfflineCache format
│  └─ src/test/                  decoding the shared fixtures · formats · clock changes · geometry
├─ network/                      ApiClient · RetryPlan · ApiError · TokenStore (Keystore) 
├─ design/                       Tokens · Type · WHIcon · components (Card, PrimaryButton, Eyebrow,
│                                Chip, Segmented, NoteCard, Tag, Skeleton, Hatch, SignalBar, EmptyNote)
├─ scripts/                      sync-api-fixtures.sh · sync-brand.sh (+ .mts)
├─ PLAN.md · PLAYSTORE.md
```

### 4.3 Dependencies, all of them
| Library | For | iOS equivalent |
|---|---|---|
| Compose BOM: `ui`, `foundation`, `material3` | The interface | SwiftUI |
| `material3-adaptive`, `-adaptive-navigation-suite` | Bar ↔ rail ↔ drawer, list–detail panes | `.sidebarAdaptable`, `NavigationSplitView` |
| `navigation-compose` (typed routes) | One back stack per tab; deep links are values | `NavigationStack` |
| `lifecycle-viewmodel-compose`, `-runtime-compose` | State that survives rotation | `@Observable` |
| `kotlinx-coroutines` | async | Swift concurrency |
| `kotlinx-serialization-json` | Decoding | `Codable` |
| **OkHttp** | HTTP. No Retrofit: `ApiClient` is one class with typed functions, as on iOS | `URLSession` |
| `datastore-preferences` | The selected shop | `UserDefaults` |
| `androidx.biometric` | Medical notes behind a fingerprint or face | LocalAuthentication |
| `firebase-messaging` | Push (the one unavoidable Google service) | APNs |
| Test: JUnit, `kotlinx-coroutines-test`, Compose UI test, `mockwebserver` | | Swift Testing, XCUITest |

No image loader (the pro app shows no remote images), no database (the cache is two JSON files),
no analytics, no crash SDK — Play Console's own vitals cover crashes, and the privacy form stays
as short as Apple's.

### 4.4 Patterns
- **State:** each feature has a `ViewModel` exposing one immutable `UiState` as `StateFlow`,
  collected with `collectAsStateWithLifecycle()`. `AppModel` holds the session phase — launching,
  signed out, choosing a shop, signed in — and what we can fall back on when the network cannot say.
- **Loading:** `Loadable<T>` (idle, loading, loaded, failed). **Skeletons that mirror the layout,
  never spinners.** Errors are plain sentences, never a red fill.
- **Networking:** `ApiClient` over OkHttp with `suspend` functions. `kotlinx.serialization` with
  `ignoreUnknownKeys = true`, `explicitNulls = false`, and `coerceInputValues` so an unknown enum
  word falls to its default. `Instant` parsed with fractional seconds. `RetryPlan.standard`
  (3 attempts, 400 ms doubling) for reads and 502/503/504 only; `Retry-After` honoured up to 5 s.
  Typed `ApiError`: `unauthorized` signs out, `slotTaken` says "Someone got there first", `notOwner`
  shows the owner-only note, plus `validation(field)`, `offline`, `unavailable`.
- **Auth:** the bearer token is encrypted with an AES key held in the **Android Keystore** and the
  ciphertext kept in app-private storage. (`EncryptedSharedPreferences` is deprecated; this is what
  it did, written out.) Excluded from cloud backup and device transfer in `data_extraction_rules.xml`
  — a token should not arrive on a new phone by itself.
- **Time:** everything in the **shop's timezone** (`ZoneId` from `Me`), never the phone's. `ShopClock`
  is ported with its tests for the 23- and 25-hour days.
- **Money:** `Pence` as a value class, formatted with the session's currency. The app never
  computes a price.
- **Offline:** `Me` and one diary day per shop as JSON in `filesDir` (Android encrypts app storage
  at rest; it is also out of backups). `Reachability` wraps `ConnectivityManager`'s callback;
  `SignalBar` sits above the content; writes are disabled while offline; a return to signal
  retries. **Health records never touch the disk.**
- **Privacy:** `FLAG_SECURE` on the window while a health screen is up — no screenshots, blank in
  recents. `BiometricPrompt` (class 3, device credential allowed as the fallback) before medical
  notes, with the same `UnlockWords`.
- **Haptics:** `LocalHapticFeedback` — long-press on picking a booking up, confirm on success,
  reject on a refusal.
- **Accessibility:** TalkBack descriptions on diary blocks ("14:30 to 16:00, Root tint, Dez, deposit
  not taken") with `mergeDescendants`; headings marked; **48 dp** touch targets; type in `sp` and
  every screen checked at 200% font scale; the contrast fixes iOS already made to the tokens
  (`neutral500 #736E6B`, `eyebrow #6B6562`) carried over rather than rediscovered.

### 4.5 Design system
Same tokens as the iOS plan §4.4, expressed as a Material 3 theme that **overrides Material's
look rather than adopting it**: `.desk` colours mapped onto `ColorScheme` (primary = accent
`#b03a22`, background `#f6f5f4`, surface white, onSurface ink `#302d2c`), Archivo as the whole
`Typography`, the radii as `Shapes` (card 12, button 12, tag 6, sheet top 18). Ripples stay — they
are how Android says "that was a tap" — tinted to ink at low alpha.

One filled red action per screen, always meaning "forward". Destructive actions are red text.
The hatch for develop gaps is drawn in a `Canvas`/`drawBehind`.

**Icons:** `scripts/sync-brand` grows an Android output — the same 23 Lucide icons at 1.6 stroke as
**VectorDrawable XML**, and the app icon as an **adaptive icon**: foreground (the scissors mark from
chairtime's `lib/brand/mark.ts`), background (the accent gradient), and the **monochrome** layer
Android 13's themed icons need. Plus the 512 px Play listing icon.

### 4.6 Large screens
By **window size class**, not by device, so a folded phone, an unfolded one, a tablet and a
split-screen window each get the right one.

| Width | Navigation | Diary | Clients, Menu, Shop |
|---|---|---|---|
| Compact (phones) | Bottom bar, five tabs | Agenda / day grid / week; appointment as a bottom sheet | One pane |
| Medium (unfolded, small tablets) | Navigation rail | As compact, wider | List–detail, one pane at a time with a slide |
| Expanded (tablets, landscape) | Rail, with Waitlist and Pieces as extra rows | **Team grid** from `GET /diary/desk`; appointment in a side pane | List and detail side by side (`ListDetailPaneScaffold`) |

---

## 5. Milestones
Each ends with something running on the emulator against the local API (`next dev` on port 3100
in chairtime, reached at `10.0.2.2:3100`), on a phone **and** a tablet emulator, as an owner
(`kit@fold.example`) **and** a non-owner (`ade@fold.example`, development branch only).

Every milestone's screens arrive with their empty state, their offline state, their non-owner
version and their TalkBack labels. Those are not a later pass.

### A0: Foundations
- The project, modules, version catalog, build types, network-security config (§4.1).
- `:core` with `Me`, `Pence`, `ShopClock`, `Loadable`; `:network` with `ApiClient`, `ApiError`,
  `RetryPlan`, `TokenStore`; `:design` with tokens, Archivo, the theme, `WHIcon`.
- `scripts/sync-api-fixtures.sh` (copies chairtime's fixtures into `core/src/test/resources`) and a
  test that decodes **all 29**.
- `scripts/sync-brand.sh`: icons and the adaptive app icon.
- Sign in, stay signed in, shop picker for somebody at more than one shop, sign out.
- `Reachability`, `SignalBar`, `OfflineCache` — in from the start, because every later screen
  leans on them.

**Done when:** the seeded login signs in on a phone and a tablet emulator, survives a relaunch and
a rotation, shows "Today at Fold Barbers" from `GET /me`, and says so plainly in aeroplane mode.
`./gradlew :core:test` passes with no emulator.

### A1: Diary, view only
- Header, the summary sentence, week strip with load bars, staff chips.
- Agenda rows in every state: deposit missing, client replied, past, **in the chair**, at the
  client's (with the postcode).
- Gap dividers with "Fill it"; empty and closed days; pull to refresh; skeletons.
- Day grid at 96 dp an hour with the now-line and hatched develop gaps; week view.
- Expanded width: the team grid and the side pane.
- Appointment sheet, view only: times, client, history, bill and deposit, address and travel
  minutes, notes.
- Money scope: a non-owner sees no colleague's price, because the server sends none — the screen
  must look right with `null`, not broken.

**Done when:** the same seeded day reads the same on the web, the iPhone and the Android phone,
for an owner and a non-owner. Screenshots side by side.

### A2: Diary actions
- Sheet footer: check out, mark done (from 30 minutes before), reschedule, did not turn up, cancel
  with the refund outcome stated.
- Reschedule (3-up slots per day), block time, consent recorded, repeat and stop repeating.
- Grid gestures: long-press (0.35 s, 8 dp slop) picks a booking up, the handle resizes; both snap
  to `slotIntervalMinutes` and move optimistically; a 409 rolls back with "Someone got there
  first…". `DiaryGeometry` and `Snapping` are ported with their tests.

**Done when:** every action shows on the web and on the iPhone, and a clash between the Android
emulator and the iOS simulator is refused cleanly.

### A3: New booking
- Service → Person → Extras (skipped when none) → Time → "Book Wed 11:15". Tapping an answer
  moves on. "Closes a gap exactly". Entry from New booking, a gap's "Fill it", a client's "Rebook".
- Clash and eligibility refusals in the web's words.

**Done when:** a booking made on Android appears on the web and in availability, and the seeded
under-18 client is refused a tattoo sitting.

### A4: Clients
- Search, filter chips (All, Regulars, Due, Lapsed, No-shows), profile with the stats sentence and
  history, Edit, Rebook, the client form.
- **Health record:** `FLAG_SECURE`, never cached, behind `BiometricPrompt`.
- A new client from the phone's contacts through the system's own picker — one line, handed over by
  the user, so **no `READ_CONTACTS` permission** and nothing to declare. (Built as "pick a number",
  not "pick a contact": see Progress.)
- Medium and expanded: list–detail.

**Done when:** create, edit, delete and health changes match the web, and each health read is in
`sensitive_access_log`.

### A5: Menu and Shop, with editing and the ways out
iOS built this in three passes (view, then editing, then ownership, then leaving). Here it is one,
designed around the ownership rule from the start.

- **Menu:** services by category; add, edit, archive; price and length in every mode; steps; who
  performs it and at what price; extras; categories.
- **Shop, for an owner:** hours, booking rules, deposit and cancellation policy, reminders; team
  (add, change, remove, invite, who sees money); outlets (add, change, address privacy, travel
  bands). The Plan row: seats and outlets, informational, leads nowhere. Payments, billing and
  website open the web.
- **Shop, for anybody else:** "Your work" — their own hours, their own prices — and
  `OwnerOnlyNote` elsewhere. **No owner-only request is made** (the performers editor builds its
  row from `service.performers`).
- **The billing rule:** inviting somebody may say it adds a seat to the plan. Never what that costs.
- **Your login:** delete it, password typed again, what is left behind said afterwards.
- **Close this shop:** the address typed out, what is still booked, when the records go; and the
  partner flow — requester, partner, agreed — with withdraw, agree and refuse.
- Sign out.

**Done when:** an owner can set up a service, a person and an outlet from the phone; `ade@` sees
only their own work and triggers no 403; both ways out work against the local API (on a throwaway
shop — chairtime's close-shop test once swept a real test shop, which is why).

### A6: Waitlist, gaps, till and money
- Waitlist list and add. Fill a gap: ranked candidates, send an offer, and the offer as a text
  message with its link filled in, through the system share sheet.
- Till: total, less deposit, extra, tip, Cash / Card machine / Something else.
- Money: month headline, comparison, tiles, bars. The owner sees the shop; staff see their own.

### A7: Push and deep links
**Backend:** §3.1 and §3.2, as one chairtime PR, plus its migration run by hand.

**Android**
- A Firebase project for `com.wunderhand.app` (and `.debug`); `google-services.json` kept out of git.
- `POST_NOTIFICATIONS` asked for **after the first diary load, not at launch** (Android 13+; below
  that, no prompt exists).
- Two **notification channels** — Bookings, Cancellations — so a shop can silence one in system
  settings without losing the other.
- `FirebaseMessagingService`: register on `onNewToken`, per shop; unregister on sign out. Draw the
  notification from the data message. **"Offer the gap"** as an action on a cancellation.
- Tapping opens the appointment. `wunderhand://` scheme, and **App Links** for
  `wunderhand.com/diary/*` with `autoVerify`.

**Needs from you:** a Firebase project under your Google account and its service-account key in
Vercel's environment (`FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, `FCM_PRIVATE_KEY`).

**Done when:** a booking made through the web's client flow reaches a **real Android phone** with
the app closed and the phone idle, shows no price to a non-owner, and tapping it opens the
appointment. The iPhone still gets its own, unchanged.

### A8: Hardening and release
- **Quality:** TalkBack on a real device, screen by screen; 200% font scale; Accessibility
  Scanner's findings cleared; process death (the system killing the app in the background) on
  every form; rotation and fold/unfold mid-flow; slow and failing network through `mockwebserver`.
- **Release build:** R8 rules for `kotlinx.serialization` checked by running the **release** build
  against every fixture — the classic Android failure is a release build that cannot decode what
  the debug build could. Baseline profile for start-up and diary scrolling.
- **Signing:** an upload key made once, kept out of git and backed up by you; Play App Signing
  holds the real one. Its SHA-256 goes into `assetlinks.json`.
- **Play Console:** the listing from `../wunderhand/APPSTORE.md` (the copy carries over; the short
  description is 80 characters, the full one 4,000), **Data safety**, **Health apps declaration**,
  content rating, target audience (18+, not for children), the account-deletion URL, review
  instructions with the demo login. Written out in `PLAYSTORE.md` the way `APPSTORE.md` was.
- **Screenshots:** phone, 7" and 10" tablet, from the emulator, on the seeded shop — the same five
  and four, for the same reasons.
- **Testing tracks:** internal, then closed, then production (§9).

### Later (not v1)
As iOS: a front desk for the big screen, a "next in the chair" widget (Glance), promotions and
consent wording, Pieces, dark mode, the client booking app, Tap to Pay. Android-only: app
shortcuts ("New booking" from a long-press on the icon), Wear OS — neither before somebody asks.

---

## 6. Testing
| Layer | What |
|---|---|
| chairtime vitest | Already there for every route. New: `/devices` with `platform`, the FCM sender behind a mock, `assetlinks.json` is public. **Single files only, never the suite.** |
| `:core` JVM tests | Decode every shared fixture · an older reply for each key screen · unknown enum words · `Pence` and durations · shop-timezone days across the clock change · diary geometry and snapping (ports of the Swift cases, so the three platforms agree) · the words |
| `:network` JVM tests | `mockwebserver`: retry plan, `Retry-After`, 401 signs out, error decoding, no retry on a write |
| Compose UI tests | On the emulator against the seeded dev branch: sign in → diary → open appointment → book → cancel; the ways out are there and ask first; every control has a label. Phone and tablet. |
| Release-build check | The fixture decoding run against the minified build |
| Manual per milestone | Web, iPhone and Android side by side on the same seeded day, owner and non-owner |

---

## 7. Risks and how the plan handles them
| Risk | Handling |
|---|---|
| **Play's closed-testing rule.** A *personal* developer account created after November 2023 must run a closed test with at least 12 testers for 14 days before it may publish to production. Organisation accounts are exempt. | Decide the account type **before A0** (§8). If personal, recruit the 12 during A5–A6 so the fortnight runs alongside the work, not after it. |
| R8 strips what serialization needs, and only the release build breaks | Release-build fixture check in A8, and from A0 in CI-less form: `./gradlew :core:test` plus one instrumented decode on `release` |
| ~~Better Auth refuses a native request (origin check)~~ | Closed in A0: sign-in from the emulator is accepted. |
| FCM held back by Doze or a manufacturer's battery saver (Xiaomi, Huawei, some Samsung modes) | High-priority data messages; tested on a real phone left idle; the support page says where the setting is, because no code fixes an aggressive OEM |
| The shipped iOS app breaks when `/devices` changes | `platform` defaults to `'ios'`; a contract test posts the body build 6 sends, byte for byte |
| Device variety: small phones, 21:9, foldables, tablets, 200% type | Window size classes, not device checks; emulators for compact, medium and expanded; a fold/unfold test mid-form |
| The system kills the app mid-form | `SavedStateHandle` in every form's `ViewModel`; tested with "Don't keep activities" |
| A token restored onto another phone by backup | Excluded in `data_extraction_rules.xml` |
| Health data on the device | Never cached, `FLAG_SECURE`, biometrics, decrypted only on the server |
| The two apps drifting apart in words or behaviour | One set of fixtures, ported tests with the same cases, the words ported verbatim, side-by-side check every milestone |
| Google's billing policy questions an app with sign-in and no purchases | Same position as Apple's and already built: no prices, no plans, no link to buy. Review notes say so. |

---

## 8. Needed from you, and when
| When | What |
|---|---|
| Before A0 | Sign-off on this plan |
| Before A0 | **Google Play developer account** ($25, once). As an **organisation** — Paradigm Shift Multimedia Ltd — it needs a D-U-N-S number (free, can take a couple of weeks) and skips the 12-tester rule. As a personal account it is instant but the rule applies. Worth starting now either way: verification is the slowest thing in this plan. |
| A0 | Say when to create the private GitHub repo |
| A7 | A Firebase project; the service-account key into Vercel; OK for the chairtime PR and the migration run |
| A7 | A real Android phone for push — the emulator receives FCM, but Doze and OEM battery savers only show on hardware |
| A8 | The upload keystore backed up somewhere that is not this Mac; testers' Google accounts for the closed track |

## 9. Google Play notes
- **Sign-up and billing on the web.** Same as Apple §9. Play's payments policy forbids steering to
  outside payment for digital goods; the app steers nowhere and shows no price.
- **Account deletion.** Play requires deletion in the app **and** at a public web address entered in
  the Data safety form. Both exist: Shop → Your login in the app, and the web's delete-login page.
- **Data safety.** The same seven kinds of data as Apple's form — name, email, phone, address,
  health, other user content, device ID — all for app functionality, none shared, encrypted in
  transit, deletion available. Play also asks about **health info** specifically, and its **Health
  apps declaration** applies: the wording from the App Store review notes (a shop's business record
  of its client, encrypted, behind biometrics, no Health Connect) is the answer.
- **Review** is usually faster than Apple's but a new account's first app can take a week.

---

## Progress

### A0: Foundations (built 18 September 2026)
Signs in against chairtime's dev server from the emulator, stays signed in across a cold launch and
a rebuilt activity, picks a shop for somebody at several, opens without signal on what it last saw,
and signs out. 58 tests: 57 on the JVM with no emulator, 1 on the emulator against the real API.

| | |
|---|---|
| Project | Four modules as §4.2. Gradle 9.2.1, **AGP 8.13.1**, Kotlin 2.3.20, compile and target SDK 36, min 26. |
| Versions held back | Found by building: from September 2026 the newest Compose (BOM 2026.08+), core-ktx 1.19, lifecycle 2.11, navigation 2.10, adaptive 1.3 and OkHttp 5.5 all ask for **compileSdk 37 and AGP 9.1**, which the Android Studio on this Mac (2025.3) does not open. So they sit one release back — Compose BOM 2026.06.01, OkHttp 5.4.0 — and move together when Android Studio is updated. Nothing in the app needs what the newer ones add. `gradle/libs.versions.toml` says this where the numbers are. |
| `:core` | `Me`, `TenantStatus` (an unknown word is kept, not refused), `Pence`, `Durations`, `ShopClock`, `SignalWords`, `Loadable`, `OfflineCache`, and `ChairtimeJson` — tolerant by construction: unknown keys ignored, missing ones null, unknown enum words to the default. |
| Fixtures | The same 29 files the iOS tests decode, byte for byte (copied from `../wunderhand`; `scripts/sync-api-fixtures.sh` refreshes them from chairtime). chairtime-m5's own copies differ only in ids and times — every key path is the same, checked. |
| One difference from iOS, found by a test | Java's en-GB writes "Sep" where the web and the iPhone write "Sept". `ShopClock` says "Sept", and a test holds it there. |
| `:network` | `ApiClient` over OkHttp: no cookies (Better Auth checks Origin only on a request that carries them), bearer token, `X-Tenant-Id`, `X-Wunderhand-Client: android/1.0`, `RetryPlan` for reads and 502/503/504 only, `Retry-After` capped at 5 s, every refusal code as a typed `ApiError`. 21 tests against a local web server. |
| **A crash the JVM tests could not see** | On a cold launch the app died with `NetworkOnMainThreadException`: OkHttp's `Response` was handed back to the caller and its body read there — on the main thread. It only shows when the body arrives in chunks, which is why the first emulator run passed. Now the whole reply is read on the I/O dispatcher before anything is returned, and a test reads a chunked body from a thread standing in for main and fails if the body was read on it. |
| Better Auth and a native request | Settled: sign-in from the emulator is accepted with no `Origin`, as on iOS. The risk in §7 is closed. |
| Token | `KeystoreTokenStore`: AES-256-GCM under a key that never leaves the Android Keystore, the sealed bytes in the no-backup folder. Anything that will not open is a signed-out phone, not a crash. Survives cold launches and a reinstall over the top. |
| Backups | None at all: `allowBackup="false"` and both halves of `data_extraction_rules.xml` exclude everything, so neither the token nor the last day seen can arrive on another phone. |
| Cleartext | The release config allows none. The debug build's own copy allows `10.0.2.2`, `localhost` and `127.0.0.1` only. A real phone reaches the Mac with `adb reverse tcp:3100 tcp:3100` and the sign-in screen's Development server row. |
| `:design` | The `.desk` palette with the iOS contrast fixes already in, Archivo at four weights (OFL text beside it), `WunderhandTheme` (fixed scheme, no dynamic colour, light only), and the components A0 needs: `Lockup`, `ScreenHeader`, `Eyebrow`, `PrimaryButton`, `FooterBar`, `WHField`, `NoteCard`, `WHCard`, `SignalBar`, `EmptyNote`. Touch targets are 48 dp even where the words are small. |
| Icons | `scripts/sync-brand.sh` writes the same 23 Lucide icons as **VectorDrawables** (Lucide's circles, rects and lines rewritten as the paths they are — a VectorDrawable knows nothing else), the mark for the lockup, and the launcher icon as an **adaptive icon, all vector**: the shears from `lib/brand/mark.ts` at 54 dp, inside the 66 dp circle every launcher promises, over the red plate, plus the monochrome layer for themed icons. And `play/icon-512.png`. |
| Session | `AppModel` as iOS: launching, signed out, choosing a shop, signed in, unreachable, upgrade required. A passing failure keeps the screen and says so in a bar; a phone that has been here before opens on what it last saw; the signal coming back asks again by itself. 12 tests against a server that answers on cue. |
| Screens | Sign in (the web's words for every refusal; the password is held in a `ViewModel`, never in saved state), Which shop?, and a Today stand-in drawn from `GET /me` in the shop's date (replaced by the diary in A1). Checked unfolded (expanded width), folded (compact) and at 200% font scale. |
| Not in A0 after all | Navigation, the adaptive scaffolds and the tab bar arrive with the diary in A1, where there is something to navigate between. Their versions are already in the catalog. |

### A1: Diary, view only (built 18 September 2026)
The diary, read from chairtime, in both layouts: on a phone the agenda for the whole shop, one
person's day by the hour, and the week; on a tablet the team's columns, the week and the list — with
the appointment opening over it or beside it. 96 JVM tests and one test on the emulator that signs
in, reads the diary, opens an appointment, survives a rebuilt activity, checks every control has
words for a screen reader, and signs out. It passes folded and unfolded.

| | |
|---|---|
| `:core` | `DiaryResponse` and everything under it, `AppointmentResponse`, and the diary's logic ported with its tests case for case: `Agenda` (the same gap across people is one line; a booking comes before a gap at the same minute), `DaySummary`, `WeekSummary`, `GridGeometry`, `Intervals`, `IsoDay`, `DiaryWords`. Later fields (`toTakePence`, `atClient`, `repeat`) default, so an older server still decodes; a test strips every `…Pence` key from the fixture and the day still reads. `OfflineCache` keeps one day per shop, and only hands it back for the day it is. |
| `:network` | `diary(date)` — today is asked for with no date, so the shop decides what today is — and `appointment(id)`. `WunderhandApi` is what the app depends on, so screens are tested against something that answers on cue. |
| `DiaryViewModel` | As iOS's `DiaryModel`: a person changes the date, a load never does; the day on screen stays (dimmed) until the one asked for arrives; a failed reload keeps the day and says how old it is; no signal at launch opens on the day this phone last saw, but never yesterday's as today; a session that has ended goes to the app, not onto the diary. The date, the view, the person and the open appointment come back after the system kills the app. 14 tests. |
| Tabs | `NavigationSuiteScaffold`: a bar on a phone, a rail from medium width up, chosen by the window. Clients, Menu and Money say which milestone builds them; Shop holds who is signed in, Switch shop and Sign out until A5. |
| Two widths, three rules | **840 dp** of *window* (not of what the rail leaves) is the team grid. **1000 dp** is an appointment *beside* the grid; below that — an unfolded Pixel Fold is 841 dp — it is a sheet over it, because a panel left five columns a letter wide each. And a column is never narrower than 112 dp: the grid scrolls sideways instead. Past six people each keeps 150 dp, as on the web. |
| The week strip | Seven days share a phone's width; at 130% type and up each takes the room its words need and the strip scrolls. The tablet's strip drops to name, date and bar where "27 · £1,836" will not fit whole — cut off at "27 ·" it said less than nothing. |
| Found by looking | The web stacks gaps under breaks under bookings; drawn in time order, a gap that overlapped a booking wrote "45m free" across somebody's name. · A quarter-hour block clipped its own name. · The week's "Taken" tile showed **£2,040.2** for £2,040.25: a figure is never cut short, so it shrinks to fit (`TextAutoSize`). · **After sign-out the sign-in form still held the last email and password** — the form belongs to the activity, not the screen. It now forgets both the moment sign-in works; a test holds it there. |
| Money scope | Checked as `ade@fold.example` on the dev branch: Ade's own price shows, no colleague's does, the summary has no takings, and the alert reads "Deposits are not being collected" with no sum. Nothing was needed in the app — the server sends none and every screen already reads `null` as "not yours to see". |
| Grids stop growing at 130% type | A time grid is a picture of the day. Day and Week scale the whole way. |
| Accessibility | Every card, block and day reads as one sentence ("09:00 to 09:45, Ellis Warner, Root tint, with Ade Balogun, £68"); eyebrows are uppercased where drawn, so TalkBack reads words; every target is 48 dp. The emulator test fails if any pressable thing has no words. Test tags are exposed as resource ids for UI Automator. |
| Left for their own milestones, on purpose | "Fill it" on a gap, and the waiting count as a link (A6). New booking, Block time, drag to move, and the appointment's actions — check out, reschedule, cancel, record consent, start or stop a repeat (A2, A3). The sheet shows the consent wording and a series' dates now; the buttons arrive with what they do. A control that does nothing is worse than no control. |

### A2: Diary actions (built 18 September 2026)
Running the day from the phone: mark done, did not turn up, cancel, move it, record consent, start
and stop a repeat, block time off and take it back — and on a grid, hold a booking to pick it up or
drag its handle to change how long it takes. 130 JVM tests; the emulator test now also opens the
move screen and blocks half an hour on a quiet day six weeks out, sees it, and removes it, folded
and unfolded. Everything it changes it puts back.

| | |
|---|---|
| `:core` | `CloseOutcome`, `CloseResponse`, `SlotsResponse`, `RepeatStarted`/`Stopped`, `BlockRequest`, `Arrival`, `DragSnap` — the iOS cases ported — and `ActionWords`: every sentence the sheet says after something was done, in one place with tests. `CloseResponse.outcome` is kept as the word it arrived as: the app knows what it asked for, and a word added later must not make a finished action look failed. |
| `:network` | `ActionsApi`: close, slots, move, resize, consent, repeat, stop repeat, block, unblock. Instants go out as the iPhone writes them, UTC to the millisecond. Tested for the path, the body and the refusal each one can meet. None is ever retried: they change the day. |
| `AppointmentModel` | As iOS: one thing at a time; the sentence and the appointment it describes arrive together; a refusal is said as a problem **and everything is reloaded anyway**, since a refusal usually means something changed elsewhere. A time taken while somebody was looking stays on the times, which are asked for again. |
| The sheet's footer | Mark done from half an hour before (before that, a grey line saying when it opens — not offered rather than offered and refused); Reschedule; Did not turn up once it has started; Cancel appointment. The two that end it are red words behind a question whose way out is "Keep it" — never a bare Cancel beside "Cancel appointment". **Check out** arrives with the till (A6) and **Book them again** with new bookings (A3). |
| Dragging | `BlockDrag`, shared by both grids. Hold 0.35 s (Android's own long press is nearer half a second) to lift, with a haptic; the handle stretches at once. Both snap to the shop's slot. The lifted block shows where it would land; dropped, it stays there dimmed until chairtime answers; a clash puts it back with chairtime's sentence above the day. Nothing is checked in the app. **For somebody who cannot drag** — TalkBack, a switch, a keyboard — the block carries four named actions: earlier, later, longer, shorter, one step of the grid each. |
| Block time | Kind, who, date, from, to, note — sent as the shop's wall clock in words, so nothing here converts a timezone. The footer rides above the keyboard. |
| The ownership rule, again | chairtime lets somebody block and unblock their own time, and an owner anybody's. So a non-owner is not asked "Who", and a colleague's break is not tappable: the app does not offer what would be refused. |
| **Better than the other two, on purpose** | The web's phone grid and the iPhone's never draw a break — so blocked time looks like unexplained empty space, and somebody who blocks the wrong hour on a phone cannot take it back without a laptop. Here the phone's grid draws breaks, and a tap offers to remove one. Worth taking back to iOS and the web. |
| Found by the emulator test | **Tap a day, then Block time before it had loaded, and the wrong day was blocked** — the sheet took its date from the day still on screen. It is now the day asked for (`dayInHand`), with a test. · The new phone footer was added outside the column and took the diary's place: caught because the test runs folded too. · A test tag written after `clearAndSetSemantics` is cleared with everything else; three were. · chairtime refuses a block that overlaps another with its double-booking sentence — "Nothing has been booked" — so the sheet says "Something is already in the diary then" instead. |
| A question for chairtime | The block sheet's own words (from the web) say "Appointments already there stay put", and blocking over an appointment does work — but blocking over another *block* is refused by `time_off_no_overlap`, and **the web's block action does not catch that**, so on the web it is an error page. Not touched from here. |
| One leftover, cleaned up | A failed run left a block on Kit's Tuesday in the dev branch; removed by hand from the app. The test now blocks six weeks out, names its note "Android test — safe to remove", and reports chairtime's words if it is refused. |

### A3: New booking (built 18 September 2026)
Service → person → extras when the service has any → a time → "Book Sat 09:00", and the diary goes
to that day with the new appointment open. 161 JVM tests; the emulator test now books a walk-in all
the way through and cancels it from its sheet, folded and unfolded.

| | |
|---|---|
| `:core` | The booking types decoded from the three booking fixtures; `ServiceFacts` for how a price and a length read wherever a service is listed ("from £40", "£90/hr", "3h–8h") — a pricing mode this build has not heard of is still a price; `BookingStep`, where "Step 3 of 3" counts only the steps this booking has; `Ineligible`; `BookingWords`. |
| `:network` | The menu, a service, slots and book. An extra is asked for once each (`addon=a&addon=b`), so the client's query became a list of pairs. A booking is never retried, and a walk-in goes out with no `clientId` in it at all. |
| `NewBookingViewModel` | As iOS: tapping an answer moves on by itself and only extras wait for Continue; back undoes one answer; changing an extra throws away times that were for another length; an answer to a question that has since changed is dropped; a time tapped on the grid is kept only if it is one on offer, and a person only if they do this service. A time taken while choosing is said, dropped, and the times asked for again. An age limit is said and cannot be ticked past; a consultation done elsewhere can. **A rule this build has not heard of is still said, in chairtime's words.** The answers live in `SavedStateHandle`, so a phone call in the middle does not lose the booking. 14 tests. |
| The screens | Rows under a rule on a phone with what has been chosen as tags under the title; where there is room, cards two across with the booking-so-far beside them as a receipt that fills in — the web's laptop layout. Prices show on the times only when a rule brings some down, and the rule is named ("Wednesday mornings"), so the pro can explain the number rather than discover it. Every row and time reads as one sentence. |
| Ways in | The phone's footer — the filled "New booking" with the small block-time button beside it, as the web's — the tablet header's button, **a tap on an empty stretch of somebody's column** (the person, and the time if they are working then and it has not passed; a screen reader gets "New booking with Tomas" as an action on the column), and **Book them again** under a finished appointment that has a client. |
| Who it is for | As on the web and the iPhone, a booking started from the diary is a walk-in; one started from somebody's appointment is theirs. Choosing a client arrives with clients, in A4, from their profile's Rebook. |
| Checked by hand | Booked Saturday 09:00 with Tomas as a walk-in: the flow closed, the diary moved to Saturday, the appointment opened — then cancelled it from its sheet: chip, sentence and footer all said so, and a walk-in rightly had no "Book them again". That was A2's cancel for real, too. |
| Found by the emulator test | The sheet's notice was a live region with no words of its own — its sentence sat in a child — so TalkBack would have announced nothing when "Cancelled." appeared. The sentence is merged into it now. |

### A4: Clients (built 18 September 2026)
The list with its search and filters, a profile, the form, medical notes behind the phone's own
lock, and a new client from the phone's contacts. 205 JVM tests; the emulator test now searches for
nobody, opens a profile and its medical notes, and adds a client and removes them again, folded and
unfolded.

| | |
|---|---|
| `:core` | `ClientRow`, `ClientProfileResponse`, `ClientInput`, `HealthResponse`, decoded from the `clients`, `client` and `health` fixtures; `NotesLock` (two minutes, and a clock moved back is not a way in); `ContactFill`; `ClientWords`. The iOS cases ported one for one. |
| `:network` | `ClientsApi`. The list asks only for what is set; the typed erase word is sent as typed, because it is chairtime's to judge. |
| The list | Search waits 300 ms for the typing to stop (tested with a virtual clock); a filter asks at once and keeps what is typed; the count is "9 people", or "The first 200" with a sentence that everybody past it is still there. On a tablet the list keeps its own 340 dp pane and the person opens beside it. It comes back after process death to who was open — **but never straight into their medical notes**. |
| A profile | The four numbers as one sentence, what is coming (soonest first) and what happened, pieces in progress, and on file: the formula, the notes, and medical notes as a way in, never their contents. Rebook starts a booking that is theirs; a visit opens its appointment; an owner gets a price column and anybody else gets no column of dashes. "Add to waitlist" arrives with the waiting list, in A6. |
| The form | Kept in `SavedStateHandle`; a field chairtime refuses is outlined so the sentence has somewhere to point; a date of birth is a calendar date picked in UTC, so no travelling phone turns the 4th into the 3rd; the notes hint says plainly that allergies do not belong there. |
| **Medical notes** | Nothing is fetched — so nothing is written to the access log — until the phone says yes. `BiometricGate`: a fingerprint or face where there is one, the screen lock otherwise; the app learns only yes or no. Android does not say *which* biometric, so the words are "your fingerprint or face". A phone with no screen lock opens them and says it cannot lock them. One gate for the process: `ON_STOP`, signing out and changing shop all close it. While the screen is up the window is `FLAG_SECURE` — **checked: the system returned a zero-byte screenshot**. The boxes live in a ViewModel with no saved state, so turning the phone keeps half a sentence and leaving the screen loses everything; the keyboard is told not to learn from them. |
| **From your contacts — a number, not a contact** | Built first with `PickContact`, which failed on the emulator: Android's grant for a picked contact covers its own row — a name — and reading its numbers is a `SecurityException` without permission to read every contact on the phone. So it picks a *number* (`ACTION_PICK` on `Phone.CONTENT_TYPE`), which comes with the name: what a shop knows a client by, and the number *they* chose rather than one guessed from a label. Email and date of birth are typed. Still no permission, still nothing to declare. Checked with a made-up contact: "Filled the name and mobile from your contacts. Check them before adding." |
| Found by running it | `androidx.biometric` 1.1.0 brings `fragment` 1.2, which is older than the Activity Result API and throws "Can only use lower 16 bits for requestCode" the moment anything is launched for a result. `fragment` is now named in the catalog, with why. · `MainActivity` is a `FragmentActivity` only because the biometric prompt asks for one. |
| Left in the dev branch by the test | One access-log line per run (opening medical notes is a reading, and is written down — that is the feature), and hidden "Zz Android Test" clients: removing a client hides them, as it should. |

### A5: Menu and Shop, with editing and the ways out (built 18 September 2026)
The Menu tab and the Shop tab, whole: a service made, shaped and archived from the phone; hours,
rules, policy, reminders, the team and the outlets; and the two ways out. 295 JVM tests; the
emulator test now puts a service on the menu — steps with a gap in the middle, somebody to do it,
an extra made and retired — and takes it off again, then walks every Shop screen, folded and
unfolded. Built in four commits: types and calls, Menu, Shop, leaving.

| | |
|---|---|
| `:core` | `Menu.kt`, `Shop.kt`, `Leaving.kt`, decoded from the fifteen menu and shop fixtures. The index row words, `TeamWords`, `OutletWords`, `ReminderWords` and `LeavingWords` are the web's and the iPhone's, word for word. `ServiceDraft`, `StepDraft` and `OutletDraft` turn what was typed into what chairtime wants, or name the field they could not read. |
| `:network` | `MenuApi`, `ShopApi`, `LeavingApi` — 39 calls. `not_owner` comes back as its own case with chairtime's sentence. |
| Menu | The list with its two warnings ("nobody can book", "not online"); a service's page as the hub, with "Change" on each part; the form shows a field only when it means something (an hourly rate for "by the hour", limits for "a range"); steps are added, moved and removed freely and saved whole, with the bar redrawn as they are typed and "35m is sellable" under it; performers; extras, where one can be changed as well as made and retired. Unfolded, the menu keeps its own pane and the service opens beside it. |
| Shop | The index in the web's groups, each row carrying its one fact; on a tablet it keeps its pane and the screen opens beside it. Hours use Material's clock, as wall-clock "HH:MM" and never an instant. A day that closes before it opens is said against its row; chairtime's "days.2.closesAt" is turned back into the day it meant. Rows that open the web say so to a screen reader. |
| **The ownership rule** | Read from the session, never from a loaded screen, so nothing opens as an owner's for a moment. `ade@` gets "Your work" — their own hours, their own prices — and no row that leads to a refusal. A place saved from before somebody stopped being an owner shows `OwnerOnlyNote` and **creates no view model, so asks chairtime nothing**. The performers editor builds a non-owner's single row from the service it was handed (tested: no call at all). Checked against chairtime's routes: every owner-only call in the app is behind an owner-only screen. |
| **The billing rule** | The Plan row counts seats and outlets and leads nowhere; the emulator test fails if it ever contains "£" or "month". A new outlet "is added to your shop's plan"; an invitation says a seat is charged from when it is sent. Neither says what that costs. |
| **The ways out** | *Your login*: what stays is said before the button is offered; the password typed again; one more press. *Close this shop*: what is still booked (in red when anything is), when the records go, the password, the address typed out — which is chairtime's to check, so it is sent as typed — and one more press. The partner flow: requester takes it back; a partner agrees with their own password or ends it; somebody who agreed can change their mind. **The password lives in memory only**: never saved state, let go of after every attempt whatever the answer, when the screen goes, and on `ON_STOP` — what this guards against is an unlocked phone on a counter. |
| Forms and the system | The service, person and outlet forms keep what is typed in `SavedStateHandle`. The four settings screens do not: they are a few numbers loaded from chairtime, kept across a fold by their ViewModel and looked at afresh each time they are opened (a visit counter, so turning the phone is not a visit). Process death there loses a typed number — a candidate for A8 if it proves to matter. |
| Found by running it | **The keyboard inset.** A form inside the tab bar floated a tab bar's height above the keyboard: the inset is measured from the bottom of the window and the page does not reach it. Taking the bar's height off then hid the button by exactly the gesture bar, which the scaffold had already taken off. `MainScaffold` now measures what is below the page, reads what is already consumed, and consumes the difference — flush to the pixel, and nothing taken off beside a rail. · Number pads have no way to be put away, so every money and number field has Done. · A footer beside a rail ran under the gesture bar. · Tags inside a bottom sheet are not resource ids (a sheet is its own window), which matters only to `adb`. |
| **Not run** | Both ways out were read on the emulator and never pressed: `kit@fold.example` and Fold Barbers are what Apple's reviewer signs in to. They are covered by unit tests against a stub and by the network tests' paths and bodies. **Still to do before release: press them for real against a throwaway shop** — as the plan said, and for the same reason. Adding a person was likewise stopped at the form's own refusal, so the dev shop does not grow by a suspended "Zz" person a run. |
| Left in the dev branch by the test | One archived "Zz Android Test" service and one retired extra per run, both hidden, as they should be. Booking rules are saved as they were found. |

### A6: Waitlist, gaps, till and money (built 19 September 2026)
The last of the screens: who is waiting, offering them a gap, ringing a bill through, and what the
month took. 328 JVM tests; the emulator test now puts a client on the waiting list from their
profile and takes them off, opens a gap and reads who fits it, opens the till on its own walk-in
when the slot is soon enough, and reads the Money tab — folded and unfolded. Every tab is now real;
the "Coming" placeholder is gone.

| | |
|---|---|
| `:core` | `Waitlist.kt` and `Money.kt`, decoded from the `waitlist`, `gap`, `offer-sent`, `checkout` and `money` fixtures; the iOS cases ported one for one. `MoneyWords.comparison` is like for like — this month against the same span of last — and a fall rounds the way a rise does (Kotlin's `roundToInt` does not, for a negative half). `Owing.with(extra, tip)` is chairtime's own sum (`lib/money/checkout.ts`, checked line for line), so the figure can move as it is typed; what is *recorded* is always chairtime's. A scope or a payment method this build has not heard of is nobody's shop and its own word. |
| `:network` | `WaitlistApi`, `MoneyApi`. Instants go out to the millisecond. `already_settled` is its own refusal — and only reads are ever retried, so a press of "Mark paid" is sent once whatever the signal does. |
| The waiting list | From the diary's "2 waiting" — now its own 48dp button beside the day's sentence, not a word inside it — from a gap nobody fits, and from a client's profile, where the form opens on them with nothing to look up. Ordered by how long they have waited. The form: a client search that waits 250 ms for the typing to stop, what for, with whom, two calendar dates picked in UTC against the *shop's* today, days and times of day. Blank means any. Kept in `SavedStateHandle`. |
| Fill it | On every free stretch of the agenda; the whole block on the phone's grid; "+ Fill it" inside a team-grid outline where there is room for a finger (48dp — a shorter stretch is left to tap-to-book). The best fits come ticked; they go out in chairtime's order whatever order they were ticked in. |
| **What went out** | For somebody with no email, "Send" opens the phone's own messages app on *their number* with the words and the link already in it (`smsto:` with `sms_body`) — better than a share sheet, which would make the pro find the contact. With no number it falls back to the share sheet. **The app sends nothing**: whoever holds the phone presses send. |
| The till | "Check out · £38" beside "Mark done" once they have arrived, for whoever sees the money; "Mark done" alone for anybody else. The bill, less what was paid, plus anything sold and a tip, with **"To pay" moving as it is typed** (iOS shows it only after). Overpaid says so out loud. Settled shows the receipt and no button: a second press cannot count the same money twice. What is typed survives the phone ringing. |
| Money | The month, its comparison directly under it, and everything else as evidence: month by month, who earned it (an owner only), what sells, at the till (only for a shop that has used it), deposits, what discounting cost. Two columns unfolded. The owner sees the shop; anybody else sees "Your work" — **chairtime decides and sends only that**; nothing is fetched and hidden. Every figure is the shop's own takings: nothing on it is what Wunderhand costs. Bars are read out as "June, £4,844", not "Jun". |
| Found by running it | The join form was keyed on the list's length, so it was rebuilt — ticks lost, button disabled — the moment the list behind it loaded. Now a visit counter. · A test tag after `clearAndSetSemantics` is wiped: caught twice more here, and the source is now grepped for it. · The emulator shows a 12-hour clock: "12:06" was six past midnight, which for a while looked like chairtime offering gaps in the past. It was not. |
| **Not run** | **"Offer it" was never pressed**: on this server an offer emails whoever is on the seeded list, and that is not a test's to send. **"Mark paid and finish" was never pressed**: a bill rung through cannot be un-rung, and it would change the dev shop's takings for every other session using it. Both are covered by unit tests against a stub and by the network tests' paths and bodies; the till was opened on a live bill (yesterday's open walk-in: £38, a £4 tip typed, £42 to pay) and closed unpaid. The SMS hand-off was therefore not seen either. **Before release: one offer and one bill, for real, on a throwaway shop.** |
| Left in the dev branch by the test | Nothing new: the client put on the waiting list is taken off again, and the walk-in is cancelled as before. |

### A7: Push and deep links (built 19 September 2026 — not yet delivered end to end)
Both halves are written and tested as far as they can be without a Firebase project and without
chairtime's change being live. 354 JVM tests here; 49 pure tests in chairtime. The emulator test now
follows a link from another tab and checks that something which only looks like one opens nothing.

**What it still needs, all yours to give:** (1) a Firebase project, with Android apps for
`com.wunderhand.app` and `com.wunderhand.app.debug` — then `scripts/firebase-config.sh
~/Downloads/google-services.json`; (2) the OK to push chairtime's branch and open its PR, and to
run migration 0111 on the development database and then production; (3) the service account's
three values in Vercel (`FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, `FCM_PRIVATE_KEY`). Then "done when" —
a real booking reaching a real, idle Android phone — can be tried.

| | |
|---|---|
| **No plugin, no json in the build** | `firebase-messaging` alone. The `google-services` Gradle plugin fails the build when `google-services.json` is missing; instead `firebase.properties` (git-ignored, written by `scripts/firebase-config.sh`) becomes `BuildConfig` fields and Firebase is started by hand from them. With no such file push is off and **nothing else notices** — which is how it runs today. Firebase's own start-up is switched off in the manifest: no token is minted at first launch, for nobody. Release build checked: R8 is content, 3.7 MB. |
| `:core` | `DeepLink` — `wunderhand://appointment/<id>?shop=`, `https://wunderhand.com/diary/<id>`, and what a push carries — with the iOS cases ported and more: a host dressed up as ours (`wunderhand.com@evil.example`), a path that climbs, `intent://`. **An id is a UUID or it is not a link**, and it is checked again on the way out of saved state. `PushPayload` reads chairtime's data: the words are chairtime's and the app adds nothing; news with no words is no notification; a crafted one opens nothing. |
| `PushCoordinator` | Behind two small interfaces, so it is tested with no phone: chairtime hears once for each token and shop; a phone that would swallow the news is not registered and **no token is even asked for** until somebody has said yes; a refusal is tried again on the next diary load; a new token from Firebase goes to the shop that is open. |
| Asking | After the first diary has loaded, never at launch; once; and not at all in a build that could not receive one. |
| Drawing it | chairtime sends **data only**, so the app draws the notification: two channels — Bookings, Cancellations — each silenced on its own in the phone's settings; grouped by shop; "Offer the gap" on a cancellation that carried its time, which opens the gap screen and sends nothing. `VISIBILITY_PRIVATE` with a public version that says only that there is news — **no client's name on a lock screen set to hide sensitive content**. |
| **Signing out** | The phone comes off chairtime's list while the session can still say so — at every shop — then the token is deleted, then any of the shop's news still showing is cleared. And a message that arrives with nobody signed in is dropped before it is drawn: so even with no signal on the way out, nothing more appears for whoever has left. Checked on the emulator: one notification signed in; none after signing out; none for a push arriving signed out. |
| Following a link | One activity, `singleTop`. The link waits through sign-in; the tabs come to the diary; a link for another of their shops changes shop first and is followed there; one for a shop that is not theirs is dropped — knowing an id is not being allowed to see it. App Links' filter matches the *shape* of an id (`/diary/........-....-....-....-............`), so `/diary/block` stays in the browser. |
| A way to see it without Firebase | `DebugPushReceiver`, in the debug source set only, guarded by `DUMP` — which adb's shell holds and no app does. It takes the same path a real message takes. With it, on the emulator: the heads-up with the mark on the brand colour; a tap from the Money tab opening the exact appointment; "Offer the gap" opening who could take the freed hour; `wunderhand://` opening an appointment; `wunderhand://appointment/../../shop/close` opening nothing. |
| **chairtime** — `feat/android-push`, commit 714e19f, **local only, not pushed**, in its own worktree (`../chairtime-a7`) so the shared folder and the dev server were never touched | `POST /devices` takes `platform`, `'ios'` when unsaid so the shipped iOS app is untouched; one service's token under the other's name is refused by the API *and* by the table. `lib/push/fcm.ts`: a service account's RS256 JWT → access token (kept 55 minutes) → FCM HTTP v1, data only, high priority, 12-hour TTL; `UNREGISTERED` forgets the token. `provider.ts` splits by platform, and **each service stands in for itself** — Apple's being set up says nothing about Google's. The words are chosen above the split, so no price reaches a non-owner on either phone. `assetlinks.json` as a route, public by exact path. The support page names both stores' apps. |
| Two things the plan had not seen | The `device_tokens` **table** checks for hex as well as the API, so 0111 replaces two column checks with one that knows the platform. · `DELETE /devices/{token}` puts the token in the address. Fine for 64 hex characters; FCM's is several times that, and a device's token in a URL is a device's token in every access log on the way. So Android signs out with **`POST /devices/release`**, token in the body. The iOS route is unchanged. |
| 0111 is safe to run first | Every existing row defaults to `'ios'`; the six-argument `register_device_token` the deployed code calls **stays**, as a wrapper. So it can go on production by hand before the deploy, as always, and nothing is broken in between. |
| **Not run** | The database-backed cases added to `tests/api-v1-devices.test.ts` — they need 0111 on the development database, and that is a change to a database other sessions share. No real FCM message has been sent or received; Doze, and OEM battery savers, only show on hardware. App Links are unverified until `assetlinks.json` is live. |
| For `ANDROID_DEBUG_CERT_SHA256` | This Mac's debug key: `8D:55:A7:6A:F7:BB:A2:50:A6:89:EA:A2:3C:40:E4:36:AF:A0:95:44:08:1E:C5:5F:7F:C2:69:5E:75:A5:50:A6`. Public, as a certificate fingerprint is. The store build's is Play's app-signing certificate, known once the app exists in Play Console (A8). |
| For A8's Data safety form | Firebase Messaging brings an installation id and the push token: "Device or other IDs", for app functionality, not shared, not for tracking. No Analytics — it is not a dependency. |
| Found in passing, in chairtime, not touched | `tests/push-words.test.ts` compares against a hard-coded "today" of 17 September while `whenFor` asks the real clock whether it is today. It has failed on `main` since the 18th. A test-only fault: in production both clocks are the same one. |

### Running it locally
```sh
# chairtime, beside this project, on port 3100 (the emulator reaches it as 10.0.2.2:3100)
# the contract fixtures, after chairtime's API tests change them
CHAIRTIME=../chairtime-m5 scripts/sync-api-fixtures.sh
# the icons, after the brand or the icon list changes
CHAIRTIME=../chairtime-m5 scripts/sync-brand.sh
# everything that needs no emulator
./gradlew :core:test :network:test :app:testDebugUnitTest
# signing in for real, on a running emulator (it removes the app when it finishes)
./gradlew :app:connectedDebugAndroidTest
# onto the emulator to use
./gradlew :app:installDebug
# push, once there is a Firebase project (neither file goes in git)
scripts/firebase-config.sh ~/Downloads/google-services.json
# a pretended push, debug build only — see app/src/debug/AndroidManifest.xml
```
