# Publishing Wunderhand on Google Play

Everything Play Console asks for, written out. Drafts here are meant to be pasted in and edited if
you disagree — nothing here is in the build. The iOS twin of this file is
`../wunderhand/APPSTORE.md`; where the two say the same thing, they say it in the same words.

The one rule behind all of it: **the app sells nothing.** Shops sign up and pay on the web, and the
app never shows a price, a plan to buy or an upgrade prompt. The Plan row on the Shop tab says how
many seats and outlets a shop has and leads nowhere.

Play Console's forms change more often than Apple's, and the labels below are from memory of them,
not from the console in front of me. Where a label has moved, the *answer* is what matters: what the
app does is stated plainly in each section, so the right box can be found whatever it is called.

---

## 0. Before anything

| | |
|---|---|
| Developer account | Play Console, $25 once. As an **organisation** (Paradigm Shift Multimedia Ltd) it needs a D-U-N-S number and skips the testers rule below. As a **personal** account it is instant, but a new personal account must run a closed test with **12 testers for 14 days** before it may publish. Decide this first: it sets the whole timetable. |
| Create the app | App name `Wunderhand` · default language English (United Kingdom) · **App**, not game · **Free** · accept the declarations. |
| Package name | `com.wunderhand.app`. Permanent once the first bundle is uploaded. |
| Play App Signing | Accept it (the default). Google holds the key phones trust; you hold only an upload key, which can be replaced if lost. |

---

## 1. The upload key — yours to make, once

The build reads it from `keystore.properties`, which is git-ignored, as is every `.jks`. **I have
not made this key and should not**: its password is yours, and nobody else should have seen it.

```bash
keytool -genkeypair -v -keystore upload-keystore.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

It asks for a password and a name (your name or the company's; it is not shown to anybody). Then
create `keystore.properties` beside `PLAN.md`:

```properties
storeFile=upload-keystore.jks
storePassword=the password you chose
keyAlias=upload
keyPassword=the same password
```

**Back both files up somewhere that is not this Mac** — a password manager's file attachment is
ideal. Then the signed bundle is:

```bash
./gradlew :app:bundleRelease
```

and it lands at `app/build/outputs/bundle/release/app-release.aab` (7.7 MB). Without the two files
the same command still works and the bundle is simply unsigned, which Play will refuse.

Each upload needs a higher `versionCode` in `app/build.gradle.kts` (it is `1` now). `versionName`
is what people see: `1.0`.

---

## 2. Store listing

**App name** (30): `Wunderhand`

**Short description** (80 — this one is exactly 80):

> Your shop's diary at the chair: bookings, clients, the waiting list and takings.

**Full description** (4,000; this is about 1,250):

> Wunderhand is the staff app for a Wunderhand shop — barbers, salons, tattoo studios, and anybody
> who works by appointment.
>
> Sign in with your shop's account and the day is there: who is coming, what they are having, and
> who is free. Book somebody in, move them, mark them done, or cancel and see the gap it leaves. On
> a tablet or an unfolded phone the whole team sits side by side, a column each.
>
> WHAT IT DOES
>
> • The day, the week, and every chair at once
> • Book, reschedule, mark done, no-show or cancel — with the deposit rules your shop set
> • Gaps, and who on the waiting list fits one
> • Record what was paid at the chair and see what the day took
> • Clients: their history, their notes, what they are owed
> • Medical notes behind your fingerprint or face, for the trades that need them
> • Your menu, your team, your outlets and your hours
> • A notification when a booking lands or a client cancels
>
> WHAT IT DOES NOT DO
>
> Sign-up and billing are on the web at wunderhand.com. There is nothing to buy in the app.
>
> Wunderhand needs an account. If your shop does not have one yet, start on the web.

Two changes from the App Store copy, both deliberate: "Take payment at the chair" became "Record
what was paid", because Play's payments reviewers read "take payment" as the app processing money,
and it does not; and "Face ID" became "your fingerprint or face". **Drop the notifications bullet**
if the first release ships before push is switched on (PLAN.md, A7) — a listing should not promise
what the build cannot do yet.

**Category**: Business. **Tags**: Business, Productivity (choose what the console offers nearest).

**Contact details**: email `mike@wunderhand.com` (chairtime's own contact address — change it if
you want reviewers' mail elsewhere) · website `https://wunderhand.com`. A phone number is optional;
leave it out.

**Privacy policy**: `https://wunderhand.com/privacy` (live, public).

### Graphics
| Asset | Size | State |
|---|---|---|
| App icon | 512 × 512 PNG | **Done**: `play/icon-512.png`, written by `scripts/sync-brand.sh` |
| Feature graphic | 1024 × 500 PNG or JPG | **Not made.** Required. The lockup on the brand ground, nothing else — no screenshots in it, no words Play will crop on a small phone. |
| Phone screenshots | 2–8, each side 320–3840 px, no side more than twice the other | **Not taken.** From the emulator folded (1080 × 2092), on the seeded shop |
| 7-inch tablet | up to 8 | **Not taken.** From the emulator unfolded (2208 × 1840) |
| 10-inch tablet | up to 8 | **Not taken.** Needs a tablet emulator; the installed system image can be reused, so nothing to download |

The same five and four as the App Store, for the same reasons (`APPSTORE.md` §5): a full diary day,
an appointment open, the waiting list, the menu, the client list filtered to Regulars; and on the
wide screens the team grid, a service beside the menu, a client beside the list, the Shop index
with hours open. Not the Money tab (the seeded month reads "down 78%") and not a gap nobody fits.

---

## 3. App content — the declarations

### App access
"All or some functionality is restricted" → add instructions:

- Username `kit@fold.example` · Password `chairtime-demo-1`
- *"This is the owner of a demo shop, Fold Barbers, with six staff. All of its data is invented.
  The account stays live for future reviews. Accounts are created on our website; there is no
  registration, and nothing to buy, in the app. No other set-up is needed."*

Do not tick any box saying the credentials are for one-time use.

### Ads
No, the app does not contain ads.

### Content rating (the IARC questionnaire)
Category: **Utility, Productivity, Communication, or Other**. Every content question — violence,
sexuality, language, controlled substances, gambling, and the rest — **No**. Does the app let users
interact or exchange content with each other? **No**: a shop's staff see their own shop's records,
which is not user-to-user communication. Shares the user's location? **No**. Digital purchases?
**No**. The result should be Everyone / PEGI 3.

### Target audience and content
Age groups: **18 and over** only. Could it appeal to children unintentionally? **No**. It is a
work tool for a business's staff.

### News app · Government app
No, and no.

### Financial features
**The app provides no financial features.** It is worth being exact about why, because the app has
a till screen and a Money tab: the till *records* what a client handed over in person — cash, or the
shop's own card machine — and moves no money; the Money tab is the shop's own takings. No lending,
no banking, no payments processing, no crypto, no advice.

### Health apps declaration
The app is **not a health or medical app** and offers no health features to the person using it.
But it *does* hold health information, and the Data safety form below says so — so do not pick an
answer that denies it. If the form offers "my app does not have health features" alongside a place
to explain, choose that and paste the note; if it insists on a category, choose the nearest "other"
and paste the same note:

> Wunderhand is appointment software for barbers, salons and tattoo studios. It is not a medical
> app and gives no medical advice. Some trades record a client's allergies, medication or skin
> conditions against the shop's own consent form before a treatment; that is the shop's business
> record of its client. It is encrypted before it is stored, every access is logged, it is opened
> in the app only behind the phone's fingerprint, face or screen lock, the screen cannot be
> captured, nothing is cached on the device, and it is shared with nobody. The app does not use
> Health Connect, sensors or any health API.

### Data safety
Does the app collect or share any of the required user data types? **Yes.** Is all of it encrypted
in transit? **Yes.** Do you provide a way to request deletion? **Yes** — the URL below.

For **every** type below: **Collected** (it leaves the phone for our server) · **Not shared** with
third parties · **not** processed ephemerally · purpose **App functionality** (plus **Account
management** for the email address).

| Play's category → type | What it is here | Required or optional |
|---|---|---|
| Personal info → Name | The person signing in, and the shop's clients | Required |
| Personal info → Email address | The sign-in email, and clients' emails where a shop keeps them | Required |
| Personal info → Phone number | Clients' numbers | Optional |
| Personal info → Address | Where a client is, for an appointment the shop travels to | Optional |
| Health and fitness → Health info | Medical notes a shop keeps against its consent wording | Optional |
| App activity → Other user-generated content | Notes on a client; the shop's menu, hours and settings; what was recorded at the till | Optional |
| Device or other IDs → Device or other IDs | The push token, and Firebase's installation id that comes with it — **only once push is switched on** (A7) | Optional |

Nothing else: no location, no contacts (the system's picker hands over one number the person chose
and the app keeps nothing else), no photos, no advertising id, no analytics, no crash reporting
service. These are the same seven as Apple's privacy labels (`APPSTORE.md` §3), on purpose.

One judgement call, made and written down: **the till's amounts are declared as user-generated
content, not as "Financial info".** Play means the *user's own* financial information by that —
their purchases, their card, their credit. What is here is a business writing down its own
takings. If a reviewer disagrees it is a one-line change to add "Other financial info", same
answers.

### Account deletion
- In the app: **Shop → Your login → Delete my login**, and for an owner **Shop → Close this shop**.
- On the web, for the form's URL field: **`https://wunderhand.com/delete-account`**.

**That page is not live yet.** It is written and committed on chairtime's `feat/android-push`
branch (commit f0e4988), local only. Play checks the link, and `/delete-account` today redirects
to sign-in — which Play rejects. Until the branch is merged, `https://wunderhand.com/support` is a
public page that already describes both ways out and can stand in; swap it afterwards.

"Do you let users delete some data without deleting their account?" **Yes**: a client can be
removed, and medical notes erased, from inside the app.

---

## 4. Testing tracks

1. **Internal testing** — up to 100 people by email, live within minutes, no review. Put the first
   bundle here and install it on a real phone from the Play Store. This is where TalkBack, Doze
   and OEM battery savers get looked at (PLAN.md, A8).
2. **Closed testing** — a personal account must hold 12 opted-in testers for 14 days here before
   production opens. An organisation account may go straight on.
3. **Production** — staged: 20%, then 100%.

A new account's first review can take a week. After that, a day or two.

---

## 5. After the first upload

Play Console → Test and release → App integrity → **App signing key certificate** → copy the
SHA-256. That is what makes `https://wunderhand.com/diary/…` links open the app:

- into chairtime's environment as `ANDROID_CERT_SHA256` (and the upload key's beside it, comma
  separated, for builds you install by hand);
- this Mac's debug key for `ANDROID_DEBUG_CERT_SHA256` is in PLAN.md (A7).

It needs the `feat/android-push` branch live, because that is where `/.well-known/assetlinks.json`
is.

---

## 6. Before pressing publish

- [ ] Developer account made, and organisation or personal decided
- [ ] Upload key made, and backed up somewhere that is not this Mac
- [ ] Signed bundle uploaded to internal testing, installed from the Play Store on a real phone
- [ ] TalkBack pass on that phone, screen by screen
- [ ] Feature graphic made
- [ ] Screenshots taken: phone, 7-inch, 10-inch
- [ ] chairtime's `feat/android-push` merged — for `/delete-account`, and for App Links
- [ ] Data safety, content rating, target audience, health and financial declarations as above
- [ ] App access: the demo login, and production answering (it did not, on the morning of
      19 September 2026, when the database's monthly compute ran out — see PLAN.md)
- [ ] Both ways out pressed for real, once, on a throwaway shop (PLAN.md, A5)
- [ ] One offer sent and one bill rung through for real, on a throwaway shop (PLAN.md, A6)
- [ ] The notifications bullet in the description matches what the build does
