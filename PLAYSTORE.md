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
| Developer account | An **organisation** account, Paradigm Shift Multimedia Ltd, since 22 September 2026. The 12-testers rule below no longer applies; the listing shows the company's name and address. |
| Create the app | App name `Wunderhand` · default language English (United Kingdom) · **App**, not game · **Free** · accept the declarations. |
| Package name | `com.wunderhand.app`. Permanent once the first bundle is uploaded. |
| Play App Signing | Accept it (the default). Google holds the key phones trust; you hold only an upload key, which can be replaced if lost. |

### Personal or organisation

A personal account made after 13 November 2023 must hold a closed test with **12 testers, each
opted in for 14 days without a break**, before Play will let it apply for production. (Made before
that date, the rule does not apply: the console says so by simply offering Production.) An
organisation account never has the rule.

**The account can be changed in place**, personal to organisation, and the apps stay where they
are: Play Console → Developer account → About you → Change account type. Google's own page
describes it as making a new payments profile of the organisation type, verifying it, and linking
it. It needs a **D-U-N-S number** for Paradigm Shift Multimedia Ltd (free from Dun & Bradstreet; a
UK company often has one already, so look it up before applying; a new one takes from a few days
to a few weeks), an address, a website and an email that agree with what D&B holds, and proof of
who you are as the company's representative. **It cannot be changed back.**

What it buys: no testers rule; the listing says the company's name and address instead of your own
legal name and home address (a personal account that earns nothing shows the name and country; one
that sells shows the full address, and this one sells nothing); and cover for the health question.
Google says health apps "such as Medical apps and Human Subjects Research apps" *should* come from
an organisation account. Wunderhand is neither, but it does hold health notes and does fill in the
health declaration (§3), and a reviewer who reads that broadly would ask for exactly this change.

**Done: the account became an organisation account on 22 September 2026.** The rest of this
section is kept for the record. If the console nonetheless shows the testers rule on the production
track, that is Google's console catching up, not a rule to satisfy: contact Play support from the
console with the account's new type, rather than start a closed test.

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

**Full description** (4,000; this is about 1,100):

> Wunderhand is the staff app for appointment-based businesses: barbershops, hair and beauty
> salons, tattoo studios and any other business that runs on appointments.
>
> Sign in with your shop's Wunderhand account and see everything you need to run the day: who is
> coming in, what they are booked for, which team members are free and where the gaps are.
>
> Manage appointments, look after clients and keep the whole team working from the same schedule.
> On a tablet or an unfolded phone, see everyone's appointments side by side, with a column for
> each team member.
>
> YOUR DAY, ALL IN ONE PLACE
>
> • View appointments by day or week, across your team
> • Create bookings, reschedule, and mark appointments done, no-show or cancelled
> • Apply the booking, cancellation and deposit rules your shop has set
> • Spot gaps in the schedule and find clients on the waiting list who fit them
> • Record what was paid at the chair and keep track of the day's takings
>
> YOUR CLIENTS AND YOUR TEAM
>
> • Client profiles, appointment history, notes and outstanding balances
> • Sensitive medical notes protected by your fingerprint or face, for the trades that keep them
> • Your services, team members, locations and working hours
>
> ACCESS AND ACCOUNT REQUIREMENTS
>
> Wunderhand is for the authorised staff of businesses using the Wunderhand platform. Your shop
> must already have a Wunderhand account; staff access is provided and managed by the shop owner.
>
> There is nothing to buy in this app: no subscriptions, upgrades or purchases.
>
> If you work at a participating shop, ask your shop owner for your login details.

The same text as the App Store description of 22 September 2026 (`../wunderhand/APPSTORE.md` §1),
with three Android differences, all deliberate: "record what was paid at the chair", because
Play's payments reviewers read "take payment" as the app processing money, and it does not;
"fingerprint or face" for Face ID; and "a tablet or an unfolded phone" for iPad. The notifications
bullet is left out because push is off in the first release (PLAN.md, A7): a listing should not
promise what the build cannot do. Add it back — "A notification when a booking lands or a client
cancels" — with the release that switches push on. Nothing in it names where sign-up or billing
happens, for the reason the App Store rejection taught (APPSTORE.md §8).

**Category**: Business. **Tags**: Business, Productivity (choose what the console offers nearest).

**Contact details**: email `mike@wunderhand.com` (chairtime's own contact address — change it if
you want reviewers' mail elsewhere) · website `https://wunderhand.com`. A phone number is optional;
leave it out.

**Privacy policy**: `https://wunderhand.com/privacy` (live, public).

### Graphics
| Asset | Size | State |
|---|---|---|
| App icon | 512 × 512 PNG | **Done**: `play/icon-512.png`, written by `scripts/sync-brand.sh` |
| Feature graphic | 1024 × 500 PNG | **Done**: `play/feature-1024x500.png`, written by the same script. The lockup on the icon's ground and nothing else: Play puts the screenshots directly underneath, and a sentence would be too small to read on a phone. |
| Phone screenshots | 2–8, each side 320–3840 px, no side more than twice the other | **Done**: `play/phone/`, five, 1080 × 2092, from the emulator folded |
| 7-inch tablet | up to 8 | **Done**: `play/tablet-7/`, four, 2208 × 1840, from the emulator unfolded |
| 10-inch tablet | up to 8 | **Done**: `play/tablet-10/`, four, 2208 × 1380 — the same emulator's inner screen given a 10-inch tablet's shape (`wm size 2208x1380`, `wm density 276`: 1280 × 800dp, a Pixel Tablet's), because this Mac has not the 7 GB a second emulator wants |

Upload them in their file names' order. Phone: a full diary day, an appointment open, the waiting
list, a service with its develop gap, the client list filtered to Regulars. Wide: the team grid, an
appointment (over the grid at 7 inches, beside it at 10), a client beside the list, the Shop index
with hours open. All on the seeded shop's Wednesday 16 September, the one day it has 27 bookings
on; the status bar is Android's own demo mode (09:41, full battery, no notifications).

Left out, and why: the Money tab (the seeded month reads "down 78%"); a gap nobody fits; and **the
menu as a list**, because the development shop's menu has two rows called "ZZ UI service" that the
iOS interface tests left behind — so the phone shows one service open instead, and no wide shot has
the menu in it. Two things in them that a tidier demo shop would not have: the owner's banner about
uncollected deposits across the top of the diary, and "Team · 33 people" on the Shop tab. Both are
true of the seeded shop. If they bother you, the cure is in the data, not the app: finish the demo
shop's Stripe setup or photograph as a barber, and clear the test staff out.

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
2. **Closed testing** — optional for an organisation account (§0); a personal one served its 12 testers here. It needs the
   whole store listing and every declaration in §3 first, and its first bundle is reviewed, so the
   clock starts some days after you press the button, not when you do. Testers join by opening the
   opt-in link on the phone, signed in to the Google account you listed, and installing. Somebody
   who opts out and back in starts their 14 days again, so list 15 or so. They need not be
   barbers, and they need not have a Wunderhand login: opted in and installed is what is counted.
   Afterwards, "Apply for production" asks a few questions about what the test found.
3. **Production** — staged: 20%, then 100%.

A new account's first review can take a week. After that, a day or two.

---

## 5. After the first upload

Play Console → Test and release → App integrity → **App signing key certificate** → copy the
SHA-256. On 22 September 2026 the developer-verification page listed three keys for the package,
none of them the upload key, and which one signs the store copy is only labelled on the App
signing tab. Asset links take a list and an extra fingerprint costs nothing, so all three go in:

```
B7:0C:C3:08:4D:FE:AD:B8:68:42:BF:93:95:38:71:F7:61:88:3E:B5:EB:97:35:72:71:3A:D3:3D:5D:1E:81:ED
BA:56:10:AF:3E:94:00:BB:7D:EE:53:7B:F1:9E:6B:3E:D0:37:81:BA:73:5A:39:80:0F:AA:A5:7D:94:9B:D3:BA
FB:4A:68:57:A1:1C:D9:D3:1A:02:6C:D2:3A:97:D8:62:3D:83:37:22:EB:44:8E:0D:EC:B8:FD:75:8E:30:EE:FD
```

That is what makes `https://wunderhand.com/diary/…` links open the app:

- into chairtime's environment as `ANDROID_CERT_SHA256`, with the upload key's beside it, comma
  separated, for builds installed by hand. The exact value for Vercel, Google's three then the
  upload key:
  `B7:0C:C3:08:4D:FE:AD:B8:68:42:BF:93:95:38:71:F7:61:88:3E:B5:EB:97:35:72:71:3A:D3:3D:5D:1E:81:ED,BA:56:10:AF:3E:94:00:BB:7D:EE:53:7B:F1:9E:6B:3E:D0:37:81:BA:73:5A:39:80:0F:AA:A5:7D:94:9B:D3:BA,FB:4A:68:57:A1:1C:D9:D3:1A:02:6C:D2:3A:97:D8:62:3D:83:37:22:EB:44:8E:0D:EC:B8:FD:75:8E:30:EE:FD,A6:C4:3C:88:DB:78:3C:EA:DF:BA:6F:53:45:E7:8B:98:E0:FC:25:AB:6D:A9:18:70:74:BF:3D:42:42:F5:C0:3A`
- this Mac's debug key for `ANDROID_DEBUG_CERT_SHA256` is in PLAN.md (A7).

It needs the `feat/android-push` branch live, because that is where `/.well-known/assetlinks.json`
is.

---

## Android developer verification

Google's rule from 2026: every app installed on a certified Android phone must come from a verified
developer. **Done, 22 September 2026**: the organisation account is verified and the console shows
the app as registered. If it ever asks again, what belongs on its page is the Play app
`com.wunderhand.app`, and the upload key, because an APK signed with it was installed on a phone
outside Play: `A6:C4:3C:88:DB:78:3C:EA:DF:BA:6F:53:45:E7:8B:98:E0:FC:25:AB:6D:A9:18:70:74:BF:3D:42:42:F5:C0:3A`.
Debug builds installed over adb are exempt.

## Release notes (500 characters)

The same note for internal testing and the first production release:

```
The first Wunderhand release: the diary by day and week, bookings made, moved and marked done, the waiting list and gaps, the till, clients and their notes, medical notes behind the phone's lock, and your menu, team, outlets and hours. Phones, tablets and foldables.
```

## 6. Before pressing publish

- [x] Developer account made, and changed to an organisation account (verified 22 September 2026)
- [x] Upload key made (22 September 2026), the signed bundle built; **backed up somewhere that is not this Mac — do it**
- [x] Signed bundle uploaded to internal testing (22 September 2026, version code 1) — [ ] installed from the Play Store on a real phone
- [ ] TalkBack pass on that phone, screen by screen
- [x] Feature graphic made
- [x] Screenshots taken: phone, 7-inch, 10-inch
- [ ] chairtime's `feat/android-push` merged — for `/delete-account`, and for App Links
- [x] Data safety, content rating, target audience, health, financial and advertising-id declarations, category Business (22 September 2026)
- [x] App access: the demo login (22 September 2026); [ ] production answering (it did not, on the morning of
      19 September 2026, when the database's monthly compute ran out — see PLAN.md)
- [ ] Both ways out pressed for real, once, on a throwaway shop (PLAN.md, A5)
- [ ] One offer sent and one bill rung through for real, on a throwaway shop (PLAN.md, A6)
- [ ] The notifications bullet in the description matches what the build does

## Live

Approved and live on Google Play and the App Store, 24 September 2026 (Android production release, iOS 1.0 build 7). The push freeze on chairtime is over. PR #41 merged and deployed 24 September 2026: `/.well-known/assetlinks.json` serves the four fingerprints, and `/delete-account` is live. **To do in Play Console**: App content → Data safety → account deletion URL → `https://wunderhand.com/delete-account` (was the support page).
