# My Task List

A modern Android app (Kotlin + Jetpack Compose + Material 3) for managing
shared task lists - Short term, Long term, Garden, House, or anything else
you organize your life into.

- **Multiple lists**, each with its own **visibility**: `Private` (only
  you) or `Shared` (invite people by email).
- **Tasks** can be **assigned** to a list member, given a **due date**, and
  optionally trigger a **reminder notification**. Open tasks can be
  **reordered** by long-pressing and dragging the handle on a task row.
- **Sign in** with Google.
- **Localized**: the UI is available in English, Slovak, Czech, French,
  German, Spanish, Italian, and Russian, following the device's language
  automatically - see [Localization](#localization) below for how to add
  more.
- **CI/CD**: a GitHub Actions workflow builds an APK on every manual run
  (for testing) and attaches a release APK to every published GitHub
  Release.

Firebase Cloud Messaging is still the push transport (see
[Architecture](#architecture) below; sign-in and data no longer touch
Firebase at all) - but there's no committed `google-services.json` or
google-services Gradle plugin: `FirebaseApp` is initialized manually from
four non-secret build-time values instead (see `MyTaskListApp.kt` and
[Backend setup](#backend-setup)). To build and run against real sign-in,
data, and push, you'll need your own Appwrite Cloud project and a Firebase
project registered for FCM - see [Backend setup](#backend-setup) below for
both.

## Architecture

- **UI**: Jetpack Compose, Material 3, single-Activity + Navigation Compose.
- **DI**: Hilt.
- **Data**: Appwrite Databases - one database (`mytasklist`) with three
  collections, schema below. Appwrite has no subcollections, so
  `lists/{listId}/tasks/{taskId}` becomes a flat `tasks` collection scoped
  by a `listId` field instead of a Firestore-style nested path. There is
  no offline persistence: Appwrite's SDK has no equivalent to Firestore's
  local cache/sync, so the app now requires connectivity for every read
  and write - see [Notes & tradeoffs](#notes--tradeoffs), this is the
  single most user-visible change from the old backend.
- **Auth**: Appwrite Account, Google sign-in only - via Android's native
  Credential Manager / Google Identity Services "Sign in with Google" UI
  (no browser, no Appwrite-branded page ever shown to the user), bridged
  into a real Appwrite session by a **custom-token exchange**: the
  Credential Manager flow yields a Google ID token, which the
  `maintenance` Function's `/google-sign-in` route verifies server-side
  against Google, creates/looks up the matching Appwrite Auth user, and
  mints a one-time token via `users.createToken`; the app then exchanges
  that `{userId, secret}` pair for a session with
  `account.createSession` - Appwrite's documented "Custom Token" login
  pattern. See `LoginScreen.kt`, `AuthRepository.kt`, and
  `appwrite/functions/maintenance/src/googleSignIn.ts`.
- **Notifications**: still delivered over Firebase Cloud Messaging as the
  transport, but sent via **Appwrite Messaging** rather than a direct call
  to FCM's API. The Android app registers each device as an Appwrite
  Messaging push **Target** (`AuthRepository.registerPushTarget`, backed
  by `account.createPushTarget`/`updatePushTarget`), and the
  `notifications` Appwrite Function just calls `messaging.createPush({
  users: [uid], ... })` on task assignment and on a 15-minute due-date
  sweep - Appwrite's own FCM **Provider** (configured once in Console,
  see [Backend setup](#backend-setup) step 7) handles dispatch, retries,
  and pruning dead tokens, none of which this app's code does anymore.
  `ReminderScheduler.kt` also schedules a local WorkManager reminder
  on-device as a fallback.
- **Backend**: Appwrite Functions (TypeScript, `node-appwrite`) in
  `/appwrite/functions` handle push notifications, list/task permission
  sync, and account deletion.

### Appwrite schema

One Appwrite database, `mytasklist`, with three collections:

```
users/{uid}    displayName, email, photoUrl, locale
               (document ID = the Appwrite Auth user ID; push-device
               registration lives in Appwrite Messaging's own Targets,
               not a field here)
lists/{listId} name, icon, colorHex, visibility (PRIVATE|SHARED), ownerId,
               ownerName, memberIds[], members (JSON-encoded string -
               Appwrite has no array-of-objects attribute type)
tasks/{taskId} listId, title, description, assigneeId, assigneeName,
               priority (LOW|MEDIUM|HIGH), dueAt, notify, completed,
               order, createdBy, createdByName, reminderSent
               (flat collection - listId is the sole scoping field, there
               is no lists/{listId}/tasks subcollection)
```

None of the three carry a `createdAt`/`updatedAt`/`lastSignedInAt` field -
Appwrite's built-in `$createdAt`/`$updatedAt` system fields on every
document supersede them.

Unlike Firestore's declarative security rules, Appwrite authorizes access
with a **permission array stored on every document** - a static ACL, not
a rule engine that can evaluate against a *different* (e.g. parent)
document at read/write time. `users/{uid}` is readable by any signed-in
user and writable only by the user themself (delete is in practice only
ever done server-side by the `maintenance` Function's account-deletion
handler), mirroring the old rules. `lists/{listId}` carries read for the
owner plus every `memberIds` entry, and update/delete for the owner only,
recomputed on every membership-changing write. `tasks/{taskId}` is meant
to carry the *same* owner+members permissions as its parent list (so any
list member can fully CRUD its tasks) - but Appwrite has no way to express
"authorize like some other document." That gap is why the `maintenance`
Function's other handler, `syncListPermissions`, exists: it fires on
every list-membership change and rewrites permissions on every task under
that list to match. Without it, a list's membership and its tasks' actual
accessibility would silently drift apart over time.

## Localization

Every user-visible string in the app is a resource in
[`app/src/main/res/values/strings.xml`](app/src/main/res/values/strings.xml)
(English, the default) - there are no hardcoded strings left in the Kotlin
source. Translations live alongside it, one `values-<language code>/`
directory per language: [`sk`](app/src/main/res/values-sk/strings.xml)
(Slovak), [`cs`](app/src/main/res/values-cs/strings.xml) (Czech),
[`fr`](app/src/main/res/values-fr/strings.xml) (French),
[`de`](app/src/main/res/values-de/strings.xml) (German),
[`es`](app/src/main/res/values-es/strings.xml) (Spanish),
[`it`](app/src/main/res/values-it/strings.xml) (Italian), and
[`ru`](app/src/main/res/values-ru/strings.xml) (Russian). Android picks
whichever matches the device's language automatically, falling back to
English. The app name itself (`app_name` = "My Task List") is intentionally
left untranslated in every locale, as a brand name.

`AndroidManifest.xml` also declares
[`res/xml/locales_config.xml`](app/src/main/res/xml/locales_config.xml)
(the languages above), so on Android 13+ people can also override the
app's language independently of the device's, from Settings → Apps →
My Task List → Language.

To add another language:

1. Copy `values/strings.xml` to a new `values-<language code>/strings.xml`
   (e.g. `values-pl` for Polish) and translate every string, keeping the
   same `name` attributes and any `%1$s`/`%1$d` format placeholders in
   the same order. Leave `app_name` and `notification_channel_tasks_id`
   out - they're intentionally not overridden (see any existing
   `values-*` file for the pattern). `list_members_count` is a
   `<plurals>` resource (grammatical number - "1 member" vs "2 members" -
   varies by language, and Russian's `values-ru` needs four categories
   where most languages need two); provide whichever `quantity`
   categories the language's CLDR plural rules need
   ([cldr.unicode.org/index/cldr-spec/plural-rules](https://cldr.unicode.org/index/cldr-spec/plural-rules)),
   `other` at minimum.
2. Add a `<locale android:name="..."/>` entry for it to
   `res/xml/locales_config.xml`.
3. Add a matching Play Store listing under `app/src/main/play/listings/`
   (see [Publishing to Google Play](#publishing-to-google-play) below) so
   the app's store page is translated too, not just the app itself - use
   the locale code Play Console expects (check the language dropdown when
   adding a translation there; it's not always identical to the Android
   resource qualifier - e.g. Android's `values-cs` pairs with Play's
   `cs-CZ`, not `cs`).
4. Add an entry for the language to `NOTIFICATION_STRINGS` in
   `appwrite/functions/notifications/src/notificationStrings.ts`
   and redeploy the Appwrite Functions, so push notifications are
   localized too (see below).

Push notifications (task-assignment and due-date alerts sent by the
`notifications` Appwrite Function in `/appwrite/functions`) are localized
too: `UserRepository` writes `Locale.getDefault().language` to
`users/{uid}.locale` on every sign-in (this reflects any per-app
language override automatically), and
`appwrite/functions/notifications/src/notificationStrings.ts`
picks the matching translation from its own small `NOTIFICATION_STRINGS`
table when sending, falling back to English for an unset or unsupported
locale. A task's own title/description are user-authored content and are
never translated - only the notification's title and its fallback body
text for an untitled task are. Adding a language there means adding an
entry to that table to match the new `values-<language code>/strings.xml`.

## Backend setup

1. **Create an Appwrite Cloud project** in the
   [Appwrite Console](https://cloud.appwrite.io). Note its API endpoint
   (e.g. `https://fra.cloud.appwrite.io/v1`, needed in step 11) and its
   project ID (needed in step 2, right below).
2. **Set the project ID in `appwrite/appwrite.json`**, replacing its
   `"projectId"` placeholder with the real one from step 1.
   `appwrite/appwrite.json` is the Appwrite CLI's config for the database/
   tables/functions below - the project ID isn't sensitive (same category
   as the Firebase project identifiers in step 7/10 below - it only
   identifies the project, and ships inside the built APK regardless), so
   it's the one place this repo needs it set, rather than a GitHub secret
   duplicated with a separate Android build-time env var. Both
   [`deploy-appwrite.yml`](.github/workflows/deploy-appwrite.yml) and
   `app/build.gradle.kts` read it from here directly.
3. **Register the Android app as a Platform**: Console → project
   Dashboard → **Add Platform** → **Android**, app name of your choice,
   package name `com.github.lukelloyd1985.mytasklist` (this repo's
   `applicationId` - see `app/build.gradle.kts`, must match exactly).
   **Add a second Platform entry the same way for
   `com.github.lukelloyd1985.mytasklist.debug`** - the debug build type
   appends `applicationIdSuffix = ".debug"`, so a debug build (`./gradlew
   assembleDebug`, the CI debug APK, or any local testing) genuinely
   runs under that different package name, not the base one above. Skip
   either registration and every Appwrite call from a build using that
   package name fails, since Appwrite only serves API requests from an
   app whose package name is in the project's registered platform list.
   This is separate from step 6's Google-side certificate registration -
   both are needed, neither substitutes for the other.
4. **No manual Console work needed for the database or tables** -
   [`deploy-appwrite.yml`](.github/workflows/deploy-appwrite.yml) (see
   step 10 for its one-time CI setup) creates them for you when it runs.
   This wasn't always true: `appwrite push tables all --force` has, on
   **four** separate real runs, planned to delete the `mytasklist` database
   outright before creating anything - the first three against an empty
   database (ruling out two real, separately-fixed schema bugs along the
   way: each table's `documentSecurity` should have been `rowSecurity`,
   and the `visibility`/`priority` columns should have been `"type":
   "enum"` rather than `"type": "string"` with `"format": "enum"`, both
   leftovers from the old Collections/Attributes schema), and the fourth
   against a database that already held all 3 correctly-created tables -
   which rules out "empty database" as the cause too. This looks like
   `appwrite push tables` fundamentally not recognizing this project's
   local config as matching its deployed state, not a fixable config
   mistake - see the full trail in `deploy-appwrite.yml`'s top-of-file
   comment. **`appwrite push tables` is not used anywhere in this repo
   as a result** - only `bootstrap-tables.mjs` ever touches the database
   or tables.

   [`appwrite/bootstrap-tables.mjs`](appwrite/bootstrap-tables.mjs)
   creates the database and tables directly via the `node-appwrite`
   server SDK instead, bypassing `appwrite push` entirely.
5. **Schema changes to `appwrite/appwrite.json` take effect on the next
   run of `bootstrap-tables.mjs`** - re-run it (via
   [`deploy-appwrite.yml`](#deploying-appwrite-functions) or locally, see
   step 4) any time you edit a table's columns/indexes. It never deletes
   anything: for a table that already exists, it lists the table's
   current columns/indexes and adds only what's missing from
   `appwrite.json` - existing rows, columns, and indexes are always left
   alone. If a column that already exists has drifted from its local
   declaration (e.g. `required` or `type` changed), the script logs a
   warning and leaves it as-is rather than trying to alter it in place -
   reconcile that by hand in Console, since some changes (like narrowing
   a string's size, or changing a column's type) aren't safely automatable
   without risking the existing data anyway.
6. **Create a Google Cloud OAuth 2.0 Web application Client ID.** Sign-in
   never touches Appwrite's own hosted OAuth2 pages at all (no
   `appwrite.io`-branded page is ever shown to the user) - instead the
   Android app gets a Google ID token natively via Credential Manager,
   and the `maintenance` Function verifies that token server-side and
   bridges it into an Appwrite session (see
   [Architecture](#architecture)'s Auth bullet). Both sides of that
   bridge need to agree on one Google OAuth client:
   1. [Google Cloud Console](https://console.cloud.google.com) → APIs &
      Services → **Credentials** → Create credentials → **OAuth client
      ID** → Application type **Web application**. (If this is the
      project's first OAuth client, Google makes you configure the
      **OAuth consent screen** first - the defaults are fine for
      getting a working client.) Give it any name; no redirect URI is
      needed here, since there's no redirect - the ID token goes
      straight from the device to the `maintenance` Function. Save, and
      copy the **Client ID** it generates (the **Client secret** isn't
      needed for this flow - Credential Manager only ever sends the
      Client ID, never the secret, off-device).
   2. **Register the app's signing-certificate fingerprint** with that
      same OAuth client: Google Cloud Console → the OAuth client from
      step 1 → it also needs an **Android** OAuth client (Create
      credentials → OAuth client ID → Application type **Android**)
      with this repo's package name (`com.github.lukelloyd1985.mytasklist`,
      and a second one for `com.github.lukelloyd1985.mytasklist.debug` -
      see step 3's Platform registration for why both) and that build's
      signing-certificate **SHA-1** fingerprint (`./gradlew
      signingReport` prints both the debug and release ones). Unlike
      the old OAuth2 browser-redirect flow, Credential Manager runs
      natively on-device, so Google does need to recognize the calling
      app's own certificate - see
      [Notes & tradeoffs](#notes--tradeoffs). One more Android OAuth
      client (same package name, a third SHA-1) gets created later,
      after the first Play Store upload - see
      [Publishing to Google Play](#publishing-to-google-play) step 5's
      note on Play App Signing.
   3. Use the **Web application** Client ID from step 1 (not either
      Android client ID from step 2 - those exist only to satisfy
      Google's cert check, Credential Manager never sends them
      anywhere) as both `GOOGLE_WEB_CLIENT_ID` (step 11) and the
      `maintenance` Function's `GOOGLE_WEB_CLIENT_ID` variable (step 9) -
      it's what makes the ID token's `aud` claim match what
      `googleSignIn.ts` verifies server-side.
7. **Configure the FCM Provider for Appwrite Messaging**: Console →
   Messaging → **Providers** → Add provider → **FCM** (under Push). Give
   it a name (e.g. `fcm`) and provide the same two values push
   notifications have always needed - a Firebase service account's JSON
   key (Firebase Console → Project settings → Service accounts →
   **Generate new private key** - needs the "Firebase Cloud Messaging
   API" role) and that service account's Firebase project ID (Firebase
   Console → Project settings → General → **Project ID** - not the
   Appwrite project ID from step 1, or the Platform registration from
   step 3, which is unrelated). This is a one-time Console step, not
   part of `appwrite.json`: the credential lives with the Provider, not
   as a Function secret - see [Architecture](#architecture)'s
   Notifications bullet for why (Appwrite Messaging handles FCM dispatch
   directly; the `notifications` Function only decides what to send and
   to whom).
8. **Register two Android apps in that same Firebase project** - one per
   package name this repo actually ships, so the app itself can obtain
   FCM tokens under either one: Firebase Console → Project settings →
   General → **Your apps** → Add app → **Android**, using this repo's
   release `applicationId` (`com.github.lukelloyd1985.mytasklist` - see
   `app/build.gradle.kts`; the package name has to match exactly or the
   registration won't apply to this app). **Repeat Add app a second time**
   for `com.github.lukelloyd1985.mytasklist.debug` (the debug build type's
   `applicationIdSuffix`). Two separate registrations are needed - each
   app gets its own **App ID**, which Firebase/Crashlytics use to
   identify which app a given build belongs to - but they share
   **one** underlying Android API key: Firebase auto-creates a single
   Android key *per project* (see the table below), and registering a
   second Android app just adds its package name + signing certificate
   as another entry to that same key's Android restrictions in Google
   Cloud Console, rather than minting a new key. This still mirrors step
   3's Appwrite Platform and step 6.2's Google OAuth Android client,
   which both need the release/debug pair registered separately - just
   for App ID here, not the API key too.

   You can skip the SDK setup instructions Firebase shows after adding
   each app (the `google-services.json` download, the Gradle plugin) -
   this repo doesn't use either, see step 11. Once both apps are
   registered, note down these values from Project settings → General →
   **Your apps** → each app:

   | Value | Where to find it |
   | --- | --- |
   | Project ID | Firebase Console → Project settings → General → **Project ID** (same value as step 7; shared by both apps - no duplicate needed) |
   | App ID (release) | Firebase Console → Project settings → General → **Your apps** → the `com.github.lukelloyd1985.mytasklist` Android app → **App ID** |
   | App ID (debug) | Same, for the `com.github.lukelloyd1985.mytasklist.debug` Android app |
   | API key | Not in Firebase Console at all - only visible in [Google Cloud Console → APIs & Services → Credentials](https://console.cloud.google.com/apis/credentials) (same underlying project) as the single **"Android key (auto created by Firebase)"** - one key total, shared by both apps you registered above (see this step's intro). This is *not* the "Web API Key" shown in Firebase Console's General tab - that's a different key, generated for the Web platform specifically. |
   | Sender ID (Project number) | Firebase Console → Project settings → General → **Project number** (also shared by both apps) |

   **Check the shared key's restriction** in
   [Google Cloud Console → Credentials](https://console.cloud.google.com/apis/credentials) →
   the Android key → **Application restrictions**. A newly-created
   Firebase project's key is typically **None** (unrestricted) - if
   yours shows that, both apps' FCM tokens work with just the API key
   value itself, and nothing further is needed here. If it instead
   shows **Android apps** with a restriction list, that list needs both
   apps' certificates or FCM silently fails for whichever one is
   missing: the release signing certificate, and the **stable debug
   keystore**'s SHA-1 (see [Building the APK](#building-the-apk) →
   GitHub Actions - the same one step 6.2 registers with Google). Add
   each Android app's SHA certificate fingerprint under Firebase
   Console → Project settings → General → **Your apps** → the app →
   **Add fingerprint** (Firebase syncs it into the key's restriction
   list automatically) if it wasn't prompted for one when you
   registered it.
9. **Deploy the two Appwrite Functions** under
   [`appwrite/functions/`](appwrite/functions/): `notifications` (sends a
   push both when a task's assignee changes - database event trigger -
   and on the CRON due-date reminder sweep every 15 minutes) and
   `maintenance` (HTTP-invoked Google sign-in bridge and account
   deletion, cascading through the caller's lists/tasks; and a database
   event trigger that keeps task permissions in sync with their parent
   list's membership - see [Appwrite schema](#appwrite-schema)). Each is
   one Appwrite Function serving multiple triggers/routes - dispatched
   internally by the `x-appwrite-trigger` request header and, for
   `maintenance`'s two HTTP routes, the request path (see either
   function's `src/main.ts`) - specifically to fit inside Appwrite
   Cloud's free-tier limit of **2 Functions per project**: this repo now
   declares exactly 2, so no plan upgrade or consolidation should be
   needed for a standard setup. If you ever add a third Function, you'll
   hit "The maximum number of functions allowed for the selected plan
   has reached" and need to either merge it into one of the existing two
   the same way, or upgrade the plan.

   Push them via [`deploy-appwrite.yml`](#deploying-appwrite-functions)
   (`appwrite push function all --force` - unlike tables, this hasn't
   shown any destructive behavior), or create/deploy each one by hand in
   the Console. `notifications` needs no environment variables - the FCM
   credential lives with the Provider from step 7, not a Function
   secret. `maintenance` needs one, `GOOGLE_WEB_CLIENT_ID` (the Web
   application Client ID from step 6 - `googleSignIn.ts` uses it to
   verify the audience of every Google ID token it's handed) - CI sets
   this automatically on every deploy run (see
   `set-function-variables.mjs`, step 11's
   `GOOGLE_WEB_CLIENT_ID` secret, and
   [Deploying Appwrite Functions](#deploying-appwrite-functions) below),
   so there's nothing to click in Console for a standard CI-driven
   setup. Only needed by hand if deploying via Console instead of CI:
   Console → Functions → `maintenance` → **Variables** → add
   `GOOGLE_WEB_CLIENT_ID`, same value.
10. **Create a server API key** for CI: Console → Overview →
   Integrations → **API Keys** → Create API key, scoped to
   **`databases.read`** and **`databases.write`**, **`tables.read`** and
   **`tables.write`**, **`columns.read`** and **`columns.write`** (the
   Console's own scope list has already dropped the legacy
   `collections`/`attributes` scopes in favor of `tables`/`columns` -
   don't grant the deprecated ones), **`functions.read`** and
   **`functions.write`** (also covers setting the `maintenance`
   Function's `GOOGLE_WEB_CLIENT_ID` variable - see
   `set-function-variables.mjs` and
   [Deploying Appwrite Functions](#deploying-appwrite-functions) below -
   Appwrite's scopes are per-resource, not per-subresource, so this
   isn't a separate grant; if a deploy run ever disagrees, that's the
   first scope to check), **`rules.read`** (needed by `appwrite push
   function` - it's listed under the **Proxy** category in the scope
   picker, not Functions, which is easy to miss), and **`users.write`**
   (needed by `maintenance`'s cascading Auth-account deletion). This
   becomes the `APPWRITE_API_KEY` secret used by CI - see
   [Deploying Appwrite Functions](#deploying-appwrite-functions) below.
11. **Set the build-time env vars** the Android app reads (see
    `app/build.gradle.kts`). The Appwrite project ID doesn't need one -
    it's read straight from `appwrite/appwrite.json` (step 2) - and the
    database/table/function IDs below already default to this repo's own
    fixed values, so only the endpoint needs setting explicitly there. In
    CI, `android-build.yml` already reuses the same `APPWRITE_ENDPOINT`
    secret [Deploying Appwrite Functions](#deploying-appwrite-functions)
    has you create for the Appwrite endpoint, and separately reads the
    Firebase values and the Google Web Client ID below from repository
    secrets it already expects (Settings → Secrets and variables →
    Actions) - create those with the values from step 8's table and step
    6. Note that `FIREBASE_APPLICATION_ID` and
    `FIREBASE_APPLICATION_ID_DEBUG` are genuinely different
    values (one per Firebase app registration, per step 8) - not the
    same secret duplicated under two names; `FIREBASE_API_KEY`
    has no `_DEBUG` counterpart, since that one's shared. For a local
    build, export whichever set you need as shell env vars yourself
    before running Gradle (`assembleDebug` reads the `_DEBUG` App ID,
    `assembleRelease`/`bundleRelease` the non-suffixed one; both read the
    same `FIREBASE_API_KEY` - see `app/build.gradle.kts`'s
    `buildTypes` block):

    | Env var | Value |
    | --- | --- |
    | `APPWRITE_ENDPOINT` | Appwrite endpoint from step 1 |
    | `FIREBASE_PROJECT_ID` | Project ID from step 8's table |
    | `FIREBASE_API_KEY` | API key from step 8's table (shared by both apps) |
    | `FIREBASE_APPLICATION_ID` | App ID (release) from step 8's table |
    | `FIREBASE_APPLICATION_ID_DEBUG` | App ID (debug) from step 8's table |
    | `FIREBASE_SENDER_ID` | Sender ID (project number) from step 8's table |
    | `GOOGLE_WEB_CLIENT_ID` | Web application Client ID from step 6 |

    The rest (`APPWRITE_DATABASE_ID`,
    `APPWRITE_COLLECTION_USERS_ID`/`_LISTS_ID`/`_TASKS_ID`,
    `APPWRITE_FUNCTION_MAINTENANCE_ID`) are override knobs for
    a contributor customizing those IDs away from this repo's defaults
    (`mytasklist`/`users`/`lists`/`tasks`/`maintenance`) - not something
    you need to set for a standard setup.

## Deploying Appwrite Functions

[`.github/workflows/deploy-appwrite.yml`](.github/workflows/deploy-appwrite.yml)
is a manually-triggered ("Run workflow" in the **Actions** tab - works
from the GitHub mobile site or app, no local Appwrite CLI or login
needed) job that pushes both Appwrite Functions from
[`appwrite/appwrite.json`](appwrite/appwrite.json) using a server API key
instead of an interactive login, and also sets the `maintenance`
Function's `GOOGLE_WEB_CLIENT_ID` environment variable via
`set-function-variables.mjs` (see [Backend setup](#backend-setup) step
9 - `appwrite push function` itself never touches Function variables, so
this fills that gap directly through the API instead of requiring a
manual Console click on every fresh setup). It never runs on its own - a
Functions deploy going out on every push felt like too much blast radius
for something this easy to trigger on demand instead.

Every run also uploads a brand-new deployment for each Function and
activates it - `appwrite push function` never deletes the ones it
replaces, and a Function's `deploymentRetention` setting (how many days
Appwrite keeps non-active deployments before auto-deleting them) defaults
to 0, meaning keep forever. Left alone, every manual redeploy leaves one
more inactive deployment sitting in Console permanently. The workflow's
last step, `prune-function-deployments.mjs`, closes that gap the same way
`set-function-variables.mjs` closes the environment-variable one: it sets
`deploymentRetention` (1 day) on both Functions via the API directly
(also not something `appwrite push function` manages), and explicitly
deletes any already-existing non-active deployment older than that
window - never touching whichever deployment is currently active.

It does **not** push the database/tables - `appwrite push tables` has
repeatedly planned to delete the `mytasklist` database outright (see
[Backend setup](#backend-setup) step 4 and `deploy-appwrite.yml`'s
top-of-file comment for the full trail), so it's dropped from this
workflow entirely. `bootstrap-tables.mjs` (also run by this workflow) is
the only thing that creates or updates the database and tables, and it
does so non-destructively - see step 5.

One-time setup - three repository secrets (Settings → Secrets and
variables → Actions); the project ID isn't among them - it's read from
`appwrite/appwrite.json`, see [Backend setup](#backend-setup) step 2:

| Secret | Value |
| --- | --- |
| `APPWRITE_ENDPOINT` | Your project's API endpoint, e.g. `https://fra.cloud.appwrite.io/v1` |
| `APPWRITE_API_KEY` | The server API key from [Backend setup](#backend-setup) step 10 |
| `GOOGLE_WEB_CLIENT_ID` | The same secret [Backend setup](#backend-setup) step 11 has the Android build read - reused here rather than duplicated under a second name, since it's the identical Client ID value both sides need to agree on (see step 6.3) |

That's nearly the whole setup - one scoped API key plus one shared Client ID secret, considerably simpler
than the old Firebase deploy's Google Cloud service account juggling five
separate IAM roles (Firebase Admin, Cloud Build Editor, Service Account
User, Cloud Scheduler Admin, Artifact Registry Admin) plus its
Eventarc/Artifact-Registry first-deploy gotchas - none of that GCP-
specific machinery has an Appwrite equivalent to configure.

From then on: **Actions tab → Deploy Appwrite (Functions) → Run
workflow**.

## Building the APK

### Locally

```
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Release builds fall back to signing with the debug keystore when no
release-signing secrets are configured, so `assembleRelease` always
produces an installable APK even before you've set up a keystore.

### GitHub Actions

[`.github/workflows/android-build.yml`](.github/workflows/android-build.yml):

- **Run it manually** any time from the repo's **Actions** tab → *Android
  Build* → **Run workflow**, choosing a **build_type**: `debug` (the
  default) or `release`. Either way it builds just the one APK and
  attaches it to the workflow run as a downloadable artifact - handy for
  giving testers a build, or for testing a real release-signed APK,
  without cutting an actual release. A manual `release`-variant run still
  never touches the AAB or the Play publish steps below - those stay
  scoped to an actual published Release, since they publish to Play
  Console.
- **Publishing a GitHub Release** automatically builds a release APK *and*
  an Android App Bundle (`.aab`, what Google Play requires), attaches both
  to that release (and to the workflow run as artifacts), and - if the
  `PLAY_SERVICE_ACCOUNT_JSON` secret is set - uploads the AAB to Google
  Play's closed testing track. See
  [Publishing to Google Play](#publishing-to-google-play) below.

To get a properly **signed** release build (instead of the debug-keystore
fallback), generate a keystore **using exactly this alias** - it's
hardcoded in `app/build.gradle.kts`'s `signingConfigs.release`, not
read from a secret, since it isn't sensitive (it's public right here)
- and add these repository secrets (Settings → Secrets and variables →
Actions):

```
keytool -genkey -v -keystore release.keystore -alias mytasklist \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -i release.keystore | pbcopy   # or base64 -w0 on Linux
```

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | base64-encoded keystore file |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password - also used as the key password (see below) |

Only one password secret, not two: `keytool` defaults to PKCS12
keystores now, which don't support a separate per-key password (it
silently ignores `-keypass` and reuses the store password for every
key), so `signingConfigs.release` reads `RELEASE_KEYSTORE_PASSWORD` for
both `storePassword` and `keyPassword`.

**A stable debug keystore for CI builds is required for Google Sign-In
to work on a CI-built debug APK**, and for debug push notifications too
if your Firebase Android API key turns out to be restricted (see below).
Credential Manager's Google Identity Services flow verifies the calling
app's signing certificate against the Android OAuth client registered in
step 6.2 - unless that certificate is registered there,
`GetGoogleIdOption`/`GetSignInWithGoogleOption` fails, unconditionally.
The shared Firebase Android API key (step 8) is a separate, *conditional*
concern: if its **Application restrictions** is set to **Android apps**
rather than **None**, it needs the debug app's SHA fingerprint on file
the same way, or FCM token retrieval silently fails (no crash, no
visible error, just no push - see
[Notes & tradeoffs](#notes--tradeoffs)); check Google Cloud Console →
Credentials → the Android key to see which applies to your project.
Without a stable keystore, `assembleDebug` falls back to AGP's
built-in debug signing, which auto-generates a brand-new random keystore
on every run (CI runners are a fresh VM each time), so the fingerprint
registered in Google Cloud Console would only ever match one specific CI
run. It's also worth setting up for the same reason even ignoring
sign-in: it lets repeat CI debug builds install *over* each other on a
test device rather than requiring an uninstall first (Android refuses to
install an update signed with a different certificate than what's
already on the device). Generate one the same way, again using exactly
this alias (hardcoded in `signingConfigs.debug` the same way as the
release one above), and add it as its own set of secrets:

```
keytool -genkeypair -v -keystore debug.keystore -alias mytasklistdebug \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -i debug.keystore | pbcopy   # or base64 -w0 on Linux
```

| Secret | Value |
| --- | --- |
| `DEBUG_KEYSTORE_BASE64` | base64-encoded debug keystore file |
| `DEBUG_KEYSTORE_PASSWORD` | debug keystore password - also used as the debug key password, same reason as above |

No further registration step is needed - unlike the old Firebase setup,
there's no per-certificate step to complete afterwards.

## Publishing to Google Play

Getting onto Google Play has two kinds of steps: a handful that **only you
can do**, by hand, in Play Console (there's no API for them - creating an
app and its first release requires a human in the Play Console UI), and
everything after that, which CI automates.

### 1. One-time setup you do by hand

1. **Host the privacy policy and account deletion page.** Play requires a
   live URL for each. [`docs/privacy.html`](docs/privacy.html) and
   [`docs/delete-account.html`](docs/delete-account.html) are starting
   drafts - replace every `[bracketed placeholder]` in both (especially
   the support email), then enable Pages at Settings → Pages → *Build and
   deployment* → *Source* → **GitHub Actions** (not "Deploy from a
   branch" - that built-in source rebuilds on every push to `main`
   regardless of what changed, with no way to scope it to `/docs`;
   [`.github/workflows/pages.yml`](.github/workflows/pages.yml) is a
   custom deployment that only runs when `docs/` actually changes). Both
   pages are served from the same deployment, at
   `https://<your-github-username>.github.io/MyTaskList/privacy.html` and
   `.../delete-account.html`.
2. **Create the app** in [Play Console](https://play.google.com/console):
   *Create app* → name it, set default language, "App" (not game), and
   Free. The package name is fixed at creation to whatever you tell it -
   use `com.github.lukelloyd1985.mytasklist` to match `applicationId` in
   `app/build.gradle.kts`. Whatever default language you pick here must
   match the locale directory under `app/src/main/play/listings/` (and
   `default-language.txt`) - the Play Developer API rejects listing/image
   uploads for a language the app doesn't have a store listing for yet,
   with a 404 "Listing for language '...' not found", so a mismatch (e.g.
   picking English (UK) here but leaving the repo on `en-US`) breaks the
   `publishListing` CI step. This repo currently uses `en-GB`.
3. **Complete "App content"** (Play Console won't allow any release
   without these): Privacy policy URL (from step 1), Ads (No ads, unless
   you've added some), Content ratings questionnaire, Target audience,
   **App access** - the whole app sits behind Google Sign-In (there's no
   guest/anonymous mode: `MainActivity` shows `LoginScreen` until
   `AuthViewModel.currentUser` is non-null), so this must be set to "All
   or some functionality is restricted," not "available without special
   access." Add one login instruction: leave *Username* and *Password*
   blank (there aren't any - it's Sign in with Google, not a password
   form) and put something like the following in *Instructions*: "This
   app only supports Sign in with Google - there's no username/password.
   Tap 'Continue with Google' and sign in with any Google Account to
   access all functionality." Reviewers use their own Google account for
   this, the same way a real user would.
   **Data safety** section - this should mirror `docs/privacy.html`:
   personal info collected (name, email, photo - via Google Sign-In),
   task/list content, shared with third parties = **No**, encrypted in
   transit = **Yes**, users can request data deletion = **Yes**, with the
   account deletion URL set to `docs/delete-account.html`'s published
   address from step 1 (see [Notes & tradeoffs](#notes--tradeoffs) for
   what that page and the in-app "Delete my account" action actually do).
4. **Fill in the Store listing**: short/full description, app icon
   (512×512 PNG), feature graphic (1024×500 PNG), and at least 2 phone
   screenshots. Ready-to-upload versions of all three live in
   [`docs/store-assets/`](docs/store-assets/) - upload them by hand this
   first time, or, once you've done the one-time API access setup in
   step 2 below, run `./gradlew publishListing` locally instead: it
   publishes the same content straight from
   `app/src/main/play/listings/en-GB/` (title, descriptions, and the
   `docs/store-assets/` graphics, already copied in there). From then on,
   CI keeps this in sync automatically on every release - see step 4 in
   part 2 below. The repo also ships Slovak (`sk`), Czech (`cs-CZ`),
   French (`fr-FR`), German (`de-DE`), Spanish (`es-ES`), Italian
   (`it-IT`), and Russian (`ru-RU`) store listings under the same
   `listings/` directory - same rule as above applies to all of them:
   Play Console rejects `publishListing` uploads for any language the
   app doesn't already have a listing for (see the note on step 2
   above), so add every one of these under Store presence → Main store
   listing → Manage translations → Add language *before* the first CI
   release, or `publishListing` will 404 on them the same way it did on
   a mismatched default language.
5. **Do the first release by hand.** Build an AAB
   (`./gradlew bundleRelease`, or download one from a GitHub Release once
   you've tagged one - see below), go to Testing → Closed testing →
   *Create new release*, upload it, add release notes, and roll it out.
   Add yourself (and any other testers) as a closed tester so you can
   install it.

   **This first upload also enrolls the app in Play App Signing** (Google's
   own signing key, mandatory for a new app) - which means an APK
   installed *from the Play Store* is signed with a **different**
   certificate than the AAB you just uploaded (the "upload key" -
   `RELEASE_KEYSTORE_*`'s key, or the debug-keystore fallback - see
   [Building the APK](#building-the-apk)). Google Sign-In's account-reauth
   check cares which certificate actually signed the APK on the device, so
   a Play-installed copy needs its own SHA-1 registered too, or it fails
   the same `TYPE_USER_CANCELED`/"Account reauth failed" way a build
   signed with an unregistered certificate always does. Get it from Play
   Console's **App signing** page (Play Console has renamed/relocated
   this over time - currently under **Setup → App integrity**, look for
   **App signing key certificate**) → SHA-1. A Google Cloud Android OAuth
   client holds exactly **one** SHA-1 each - there's no "add another
   fingerprint" on an existing client - so this means **creating a
   separate Android OAuth client** (Create credentials → OAuth client ID
   → Android) with the *same* package name
   (`com.github.lukelloyd1985.mytasklist`) as the one from
   [Backend setup](#backend-setup) step 6.2, but this new SHA-1. Google's
   server-side check matches against every registered (package name,
   SHA-1) pair across all your project's Android OAuth clients, so
   multiple clients sharing one package name is the normal way to cover
   more than one valid signing certificate for it - not a workaround.
   Keep the original upload-key client too, rather than replacing it: a
   directly-sideloaded GitHub Release APK is still signed with the upload
   key and needs that entry to keep working.

   Play Console now shows **four** fingerprints on that page - SHA-1 and
   SHA-256, each in both a "classic" and a newer "quantum-resistant"
   variant (Google's hybrid post-quantum signing rollout, additive to the
   traditional certificate, not a replacement of it). **Use the classic
   SHA-1** - that's the certificate Android's package manager and
   Google's own APIs (Firebase, GMS package verification) actually check
   today; the quantum-resistant one is a forward-looking layer nothing
   mainstream depends on yet. This app's release App Signing key's
   classic SHA-1 is
   `A6:5A:C0:AD:77:01:1F:BC:1C:7C:F8:6E:78:35:E4:6F:01:87:C6:20`
   (recorded here since, unlike the upload keystore's fingerprints below,
   there's no local `signingReport` for a key only Google holds).

   **This same fingerprint also needs registering with Firebase**, not
   just the Google Cloud OAuth client above: Firebase Console → Project
   settings → General → Your apps → the
   `com.github.lukelloyd1985.mytasklist` app → **Add fingerprint**. Skip
   this and `FirebaseApp.initializeApp()` (see `MyTaskListApp.kt`) fails
   on a Play-Store-installed copy in a way this app's own crash handler
   never even sees - not the "silent, no-crash" FCM failure the shared
   API key's restriction note above describes, but a hard, native-level
   process kill on every launch, indistinguishable from the app simply
   never starting (stuck on the OS's default icon-on-background starting
   window, no crash screen, no ANR, nothing in Play Console vitals).
   Confirmed by bisecting `MyTaskListApp.onCreate()` with temporary
   `Toast` checkpoints (no adb access on the affected device) - the app
   launched fine the moment `initFirebase()` was skipped, and this
   fingerprint registration is what let it be reintroduced safely.

### 2. Let CI handle every release after that

1. Play Console's old **Setup → API access** page is gone - Google
   removed it, and the replacement flow is no longer inside Play Console
   at the start. In [Google Cloud Console](https://console.cloud.google.com)
   (any project - it doesn't need to be one already linked to this app),
   go to **APIs & Services → Library**, search for "Google Play Android
   Developer API", and enable it. Then **IAM & Admin → Service Accounts →
   Create Service Account** (any name, e.g. `mytasklist-ci-publisher`) - it
   doesn't need any Google Cloud IAM roles for this. Open it → **Keys →
   Add Key → Create new key → JSON** to download the key file whose
   contents become the `PLAY_SERVICE_ACCOUNT_JSON` secret below.
2. Back in Play Console, go to **Users and permissions → Invite new
   user**, and invite the service account by pasting its email address
   (the `client_email` field in the JSON key you just downloaded, looks
   like `mytasklist-ci-publisher@<project>.iam.gserviceaccount.com`) exactly
   as if inviting a person. Under **App permissions**, add this app and
   grant it **both** **Release management** permissions (needed for
   `publishReleaseBundle`) **and** **Store listing / "Manage store
   presence"** permissions (needed for `publishListing`, which pushes the
   title, description, and graphics from `app/src/main/play/` - see step
   4 above). These are separate permission groups in Play Console;
   granting only "Release manager" covers releases but not the store
   listing, and `publishListing` will fail with a permissions error
   without the second one.
3. Add the JSON key's full contents as the `PLAY_SERVICE_ACCOUNT_JSON`
   repository secret (Settings → Secrets and variables → Actions) -
   paste the raw JSON, not base64.
4. From then on, **publishing a GitHub Release** builds the AAB and runs
   `publishReleaseBundle`, which uploads it straight to the **closed
   testing** track with `releaseStatus = COMPLETED` (see the `play { }`
   block in `app/build.gradle.kts`). CI never touches the production
   track - promote a release from closed testing to production
   yourself in Play Console once you're happy with it. It also runs
   `publishListing`, which pushes the store listing text and graphics
   committed under `app/src/main/play/` - edit those files (and
   `docs/store-assets/` for the graphics themselves) and the next release
   picks up the changes automatically, no manual re-upload needed.
5. **Version each release with a git tag** (e.g. `v1.0.1`) when you
   create the GitHub Release - it becomes the app's `versionName`.
   `versionCode` auto-increments from `GITHUB_RUN_NUMBER`, so Play's
   "each upload needs a higher version code than the last" requirement is
   handled for you automatically.

## Notes & tradeoffs

- **`appwrite push tables all --force` can delete the `mytasklist` database
  outright** - confirmed on four separate real runs, including one
  against a database that already held all 3 correctly-created tables,
  which rules out "only happens when empty". See
  [Backend setup](#backend-setup) step 4 and the `WARNING` in
  `deploy-appwrite.yml`'s top-of-file comment. This is why
  `appwrite/bootstrap-tables.mjs` creates the database and tables
  directly via the API instead, and why `appwrite push tables` isn't run
  anywhere in this repo, including in CI - it's not just deferred until
  "after bootstrap," it's removed.
- **`bootstrap-tables.mjs` only ever adds - it never deletes or alters
  anything.** A table that already exists has its columns/indexes
  diffed against `appwrite/appwrite.json` and only what's missing is
  added; a column that already exists but has drifted from its local
  declaration is left as-is with a warning logged, not altered in place
  (see [Backend setup](#backend-setup) step 5). Reconciling a genuinely
  changed column (e.g. a type change) still needs a manual step in
  Console, since that can't always be done without risking the existing
  data - this script deliberately won't attempt it automatically.
- **`appwrite push function` needs the `rules.read` scope**, which
  Console's API key scope picker lists under the **Proxy** category, not
  Functions - easy to miss (see [Backend setup](#backend-setup) step 10).
- **Only 2 Appwrite Functions exist (`notifications`, `maintenance`),
  each serving two triggers**, specifically to fit inside Appwrite
  Cloud's free-tier limit of 2 Functions per project - see
  [Backend setup](#backend-setup) step 9. This also happened to remove
  duplicated code: `listAll.ts` was byte-identical between the two
  functions now merged into `maintenance`.
- **An unregistered Platform and an unregistered Google OAuth Android
  client are two separate, easy-to-conflate failure modes** - the
  former (Console → project Dashboard → Platforms, see
  [Backend setup](#backend-setup) step 3) is Appwrite's own check on
  which app package names may call the project's API at all; the latter
  (step 6.2) is Google's own check, independent of Appwrite, on which
  app package name + signing certificate may obtain a Google ID token
  in the first place. A build failing either one fails sign-in, but at
  a different point in the flow, with a different error. Register
  **both** the release package name (`com.github.lukelloyd1985.mytasklist`)
  and the debug one (`com.github.lukelloyd1985.mytasklist.debug`, from the
  debug build type's `applicationIdSuffix`) with **both** systems - a
  build using either package name only works if that exact package name
  is registered everywhere it needs to be. A related but distinct split
  applies to the Firebase Android app registration (step 8) - see the
  next bullet - though there it's *App ID only*, not everything.
- **Debug and release builds each need their own registered Firebase
  Android app and App ID, but share one API key** - unlike the
  Appwrite Platform and Google OAuth Android client above, getting this
  one wrong doesn't fail loudly. Firebase auto-creates a single Android
  API key *per project*, not per app (confirmed against a real
  project's Google Cloud Console - only one "Android key (auto created
  by Firebase)" shows up regardless of how many Android apps are
  registered); registering a second Android app adds its package name +
  signing certificate as another entry to that *same* key's Android
  restrictions, rather than minting a new key - *if* that key is
  restricted at all. So `app/build.gradle.kts` reads a single shared
  `FIREBASE_API_KEY` for both builds, but a debug-specific
  `FIREBASE_APPLICATION_ID_DEBUG` alongside the release
  `FIREBASE_APPLICATION_ID` - App ID is the one value genuinely
  unique per registered app - see [Backend setup](#backend-setup) step
  8. Whether anything further is needed per build depends on the key's
  **Application restrictions** setting (Google Cloud Console →
  Credentials → the Android key): a newly-created project's key is
  typically **None**, in which case the API key value alone is enough
  for both builds and there's nothing else to do. If it's instead
  restricted to **Android apps**, each app's SHA certificate fingerprint
  needs to be on file with Firebase (Project settings → General → the
  app → **Add fingerprint**, which syncs into the key's restriction list
  automatically) for the shared key's restriction to accept that
  build - miss the debug one under a restricted key and its FCM token
  request is silently rejected, caught by
  `AuthViewModel.registerPushTarget`'s `runCatching`, so nothing
  crashes and nothing errors on screen, push notifications on that
  build just never work. Project ID and Sender ID (project number) are
  project-level too, so both builds share those the same way as the API
  key.
- **Notifications go through Appwrite Messaging, not a direct FCM API
  call** - the Android app registers each device as a Messaging push
  Target (`AuthRepository.registerPushTarget`), and the `notifications`
  Function just calls `messaging.createPush`; Appwrite's own FCM
  Provider (see [Backend setup](#backend-setup) step 7) handles dispatch
  and dead-token pruning. This replaced an earlier version that called
  FCM's HTTP v1 API directly with a service-account credential minted
  via `google-auth-library` - dropped that dependency (it was part of
  what caused the TS18028 build failures worked through earlier) along
  with the hand-rolled token-pruning logic. **Devices that registered
  under the old `fcmTokens` array won't automatically get a Messaging
  Target** - `MainActivity`'s `LaunchedEffect(user)` re-registers on
  every app start for anyone already signed in, so this self-heals the
  next time each existing install is opened; nothing needs a forced
  re-login. The now-unused `fcmTokens` column on an already-deployed
  `users` table is harmless dead data - `bootstrap-tables.mjs` never
  drops columns, so it's left in place; safe to remove by hand in
  Console if you want it gone.
- **No committed `google-services.json` or google-services Gradle
  plugin** - `FirebaseApp` (still needed client-side so
  `FirebaseMessaging.getInstance().token` can mint an FCM token at all,
  independent of the Messaging switch above) is initialized manually in
  `MyTaskListApp.onCreate()` from four `BuildConfig` values (see
  [Backend setup](#backend-setup) step 8 and step 11), matching this
  project's existing pattern of build-time env vars over a committed
  vendor config file rather than introducing a second, inconsistent
  mechanism for one library. Registering Android apps in the Firebase
  project is still required either way - this only changes how those
  values reach the app, not whether the registration itself exists.
  `FirebaseOptions.Builder` only hard-requires `applicationId`/`apiKey`
  (verified against firebase-android-sdk's `FirebaseOptions.java`), but
  `projectId`/`gcmSenderId` are supplied too since FCM's HTTP v1 API is
  project-scoped. `FirebaseInitProvider`'s own resource-based auto-init
  (which would otherwise run first, before `Application.onCreate()`)
  fails silently when those generated resources don't exist - verified
  against its source - so it doesn't conflict with the manual call here.
- `res/drawable/ic_provider_google.xml` is Google's official "G" identity
  mark (sourced from Google's own FirebaseUI-Android library), matching
  their [Sign in with Google branding guidelines](https://developers.google.com/identity/branding-guidelines).
  Don't recolor or restyle it.
- **Offline persistence is gone**, and is the single most user-visible
  regression from the Firebase migration: Firestore's local cache/sync
  had no Appwrite SDK equivalent to carry over, so the app now requires
  connectivity for every read and write - there is no more "keep working
  on a flaky connection and sync later."
- Any signed-in user can look up any other user's basic profile (name,
  email, photo), which is what powers "invite by email" on a shared
  list. See the `users` collection's permissions in
  [Appwrite schema](#appwrite-schema) if you want to tighten this
  further.
- A related, new gap from the move to Appwrite's static per-document
  permissions: any signed-in user can technically create a `tasks`
  document against an arbitrary (but unguessable) `listId`, since the
  `tasks` collection's create permission can't validate list membership
  the way Firestore's rule (`isListMember(parentList())`) could against
  the parent list document. Mirrors the existing "any signed-in user can
  read any profile" tradeoff above.
- Due-date reminders are best-effort: an on-device WorkManager job covers
  the device that set the reminder, and the `notifications` Appwrite
  Function's CRON-triggered sweep runs every 15 minutes as the
  cross-device fallback.
- `deleteList` and `reorderTasks` no longer run as an atomic batch -
  Appwrite has no transactional multi-document write like Firestore's
  `WriteBatch`. A crash mid-operation can leave a partial state (e.g. some
  tasks reordered, some not), but it self-heals on retry rather than
  silently losing data.
- **Sign-in goes through Android's native Credential Manager, not
  Appwrite's hosted OAuth2 pages** - the earlier version of this
  migration used `account.createOAuth2Token`, which opens a Custom Tab
  against an `appwrite.io`-branded page before redirecting back into the
  app. That's a worse sign-in experience (an extra branded hop the user
  didn't ask for) for no real benefit here, so this app instead keeps
  the native Credential Manager UI and bridges the resulting Google ID
  token into an Appwrite session server-side via the `maintenance`
  Function's custom-token exchange - see
  [Architecture](#architecture)'s Auth bullet. The tradeoff: Google
  **does** need the app's signing-certificate SHA-1 fingerprint
  registered again (see [Backend setup](#backend-setup) step 6.2),
  which the OAuth2-redirect approach would have avoided - the
  debug/release keystores matter for this now, not just for Play
  Store/APK signing.
- **Account deletion** satisfies Play's dual in-app + web requirement:
  the Profile screen's "Delete my account" action HTTP-invokes the
  `maintenance` Appwrite Function
  (`appwrite/functions/maintenance/src/deleteAccount.ts`), which transfers or removes
  the user's membership on every list they're part of (a shared list
  they own is handed to another member rather than deleted out from
  under them), unassigns their tasks elsewhere, deletes their
  `users/{uid}` doc, then deletes their Appwrite Auth account.
  `docs/delete-account.html` covers the same thing for someone who no
  longer has the app installed, and gets registered as the "Delete
  account" URL in Play Console's Data safety section (see
  [Publishing to Google Play](#publishing-to-google-play)). This runs
  server-side rather than from the client both because one user must
  never be able to delete another's account, and because the `users`
  collection's permissions (see [Appwrite schema](#appwrite-schema))
  block client deletes of `users/{uid}` outright.
- Kotlin sources compile via AGP 9's built-in Kotlin support (no
  `org.jetbrains.kotlin.android` plugin applied), and Hilt's annotation
  processing runs via KSP rather than the now-incompatible `kapt`. Both
  changes were required together - `kapt` doesn't work under built-in
  Kotlin - see <https://developer.android.com/build/migrate-to-built-in-kotlin>.
- **This app's current signing-certificate fingerprints**, for reference
  when registering them anywhere the setup steps above call for one (the
  Google Cloud OAuth Android clients in [Backend setup](#backend-setup)
  step 6.2, the Firebase Android API key restriction in step 8, and
  Play App Signing's own separate one - see
  [Publishing to Google Play](#publishing-to-google-play) step 5's
  note). Get these yourself any time with `./gradlew signingReport`;
  they only change if the underlying keystore is ever regenerated.

  | | SHA-1 | SHA-256 |
  | --- | --- | --- |
  | Release (`com.github.lukelloyd1985.mytasklist`) | `B7:67:C7:FB:2D:17:9F:6F:5A:52:18:DD:A3:67:8A:84:34:4F:57:32` | `B4:7B:D6:96:12:23:D7:75:37:F3:D3:16:EC:12:3E:0B:E8:E2:A6:1F:46:68:3B:38:20:94:B5:CD:FD:94:EF:F5` |
  | Debug/CI (`com.github.lukelloyd1985.mytasklist.debug`) | `FD:8A:07:4C:03:99:FB:2E:19:AF:4F:9B:F0:04:F7:0E:EC:80:5E:91` | `DC:51:F7:FD:7B:DA:C3:2E:9C:F4:D0:E7:D2:D3:10:BB:40:6C:34:C9:C8:B3:3B:0E:41:40:DD:03:5B:ED:9E:D0` |

  This doesn't cover Play App Signing's fingerprint - that one is
  generated by Google itself once the first release reaches Play
  Console, not derived from either keystore above, so there's no fixed
  value to record here ahead of time.
