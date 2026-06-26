# Tootsie — Handoff Notes

State as of end of foundation-building session. Pick up here.

## What Tootsie Is

A fork of [Moshidon](https://github.com/LucasGGamerM/moshidon) (which is itself a fork of [megalodon](https://github.com/sk22/megalodon), which is itself a fork of the official [Mastodon for Android](https://github.com/mastodon/mastodon-android)). Tootsie adds first-class support for [Mastodon 4.6 Collections](https://blog.joinmastodon.org/2026/04/designing-collections/) ([FEP-7aa9](https://w3id.org/fep/7aa9)) — curated, publicly-shareable sets of up to 25 accounts for discovery and recommendation.

## Quick Facts

- **Repo:** https://github.com/jcrabapple/tootsie
- **Local:** `~/projects/tootsie/` on `main` branch
- **Upstream remote:** `upstream` → `LucasGGamerM/moshidon` (for pulling rewrite-branch changes)
- **Origin remote:** `origin` → `jcrabapple/tootsie` (our push target)
- **Package ID:** `org.joinmastodon.android.tootsie` (installs alongside Moshidon — different package, different signing)
- **App label:** "Tootsie"
- **Min/target/compile SDK:** 23 / 35 / 35
- **Base branch:** Moshidon `rewrite` (NOT `main` — main is the older stable Compose-free fork)

## Build Environment

Works on the host, no toolbox needed (the Microsoft JDK at `~/.jdks/ms-21.0.9/` has the correct SELinux context):

```bash
export JAVA_HOME=$HOME/.jdks/ms-21.0.9
export PATH=$JAVA_HOME/bin:$PATH
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
cd ~/projects/tootsie

# `local.properties` already created with sdk.dir pointing at ~/Android/Sdk
./gradlew :mastodon:assembleGithubDebug --no-daemon --console=plain
```

APK lands at: `mastodon/build/outputs/apk/githubDebug/tootsie-githubDebug.apk`

Verify label & package:
```bash
~/Android/Sdk/build-tools/35.0.0/aapt dump badging \
  mastodon/build/outputs/apk/githubDebug/tootsie-githubDebug.apk | grep -E 'package:|application-label'
```

Expected:
- `package: name='org.joinmastodon.android.tootsie.debug' versionCode='1' versionName='0.1.0-tootsie.alpha-debug'`
- `application-label:'Tootsie'`

## Session History (in commit order)

1. **`rebrand: Moshidon → Tootsie`** — package ID, OAuth scheme, 52 string locales, update URLs
2. **`fix(build): add missing ic_launcher_foreground drawable`** — Moshidon rewrite branch bug; uses Mo path as placeholder
3. **`rebrand: app label and version string`** — fixes app label that bulk sed missed (German locale override + `mo_app_name` family); also adds initial `Collection.java` model
4. **`feat(collections): API layer for Mastodon 4.6 Collections`** — 10 request classes + Collection model + InteractionPolicy model + NotificationType extensions + Notification.collection field
5. **`feat(collections): model interaction policy & notification payload`** — final model extensions
6. **`docs: add HANDOFF.md for next-session pickup`** — session state capture
7. **`feat(collections): CollectionDetailFragment`** — read-only viewer for a single collection (Priority 1 UI)

## What's Done (Foundation Layer)

All committed, all pushed, all building green:

- [x] **Rebrand** — package ID, OAuth scheme, app label, update URLs, strings
- [x] **Build fix** — Moshidon rewrite branch bug worked around
- [x] **`model/Collection.java`** — Parcel-annotated, Gson-serialized, full postprocess() with required-field validation
- [x] **`api/requests/collections/`** — 10 request classes covering the full REST surface
- [x] **`model/InteractionPolicy.java`** — with `CanFeature` enum (PUBLIC / FOLLOWERS / MANUAL, defaults to MANUAL)
- [x] **`Account.interactionPolicy`** — field + nested postprocess()
- [x] **`NotificationType.ADDED_TO_COLLECTION`** + **`COLLECTION_UPDATE`** — new enum values
- [x] **`Notification.collection`** — payload field, null-safe validation

## What's NOT Done (UI Layer — Start Here)

### Priority 1: Minimum viable feature

- [x] **`CollectionDetailFragment`** — read-only viewer for a single collection. Renders the collection (title, description, hashtag, member count) and a list of member accounts. The "View" button on a Featured-tab collection opens this. Done in commit `937ff3757`.

### Priority 2: Discovery (Featured tab integration)

- [x] **`ProfileFeaturedFragment` integration** — Done in commit `cd4321d89`. Extended `SearchResult` with a `COLLECTION` variant, created `CollectionCardStatusDisplayItem` (compact card), and updated the Featured tab to load collections via `GetAccountCollections` and render up to 3 collection cards. Tapping a card opens `CollectionDetailFragment`.

### Priority 3: Authoring (your own collections)

- [x] **`ManageCollectionsFragment`** — Done in commit `b0d33f209`. Lists collections owned by the current account. FAB → CreateCollectionFragment. Kebab menu per row: Edit / Delete. Otto event subscriptions for created/updated/deleted. Entry point: "Manage collections" menu item on own-profile menu (`profile_own.xml`).
- [x] **`CreateCollectionFragment`** — Done in commit `b0d33f209`. Create or edit a collection (title, description, language, tag). Form built with `FloatingHintEditTextLayout` fields. Create mode: `CreateCollection` API → `CollectionCreatedEvent`. Edit mode: `UpdateCollection` API → `CollectionUpdatedEvent`.
- [x] **`AddToCollectionFragment` + `AddCollectionMembersSearchFragment`** — Done in commit `b0d33f209`. Member picker modeled on `CreateListAddMembersFragment`. Enforces `interactionPolicy.canFeature`: PUBLIC → add immediately, FOLLOWERS → check relationship, reject if not following, MANUAL → informational dialog (server queues for approval), `discoverable=false` → blocked with snackbar, 25-member cap checked locally.

### Priority 4: Notifications

- [x] **`ADDED_TO_COLLECTION` notification UI** — Done in commit `736652176`. `CollectionNotificationStatusDisplayItem` renders "X added you to the collection Y" with a View button (opens `CollectionDetailFragment`) and a Remove me button (confirmation → `RevokeCollectionMembership`).
- [x] **`COLLECTION_UPDATE` notification UI** — Done in commit `736652176`. Same display item, renders "X updated the collection Y" with a View button (no Remove me for this type).
- [x] `NotificationGroup.java` extended with `collectionId` field for the grouped notifications API.

### Priority 5: Polish

- [x] **Test against a 4.6 instance (dmv.community)** — Done June 26, 2026. All flows verified on-device against dmv.community: create, edit, delete, view members, notifications. Three bugs found and fixed (see Session Notes below).
- [x] **Replace placeholder launcher icon** — Done June 26. New adaptive icon: white stylized "T" on teal (#006B5E) background. `drawable/ic_launcher_foreground.xml` + `mipmap-anydpi-v26/ic_launcher.xml`.
- [x] **Dusk Coast color palette** — Done June 26. All 330 M3 color tokens replaced: deep teal primary, warm amber secondary, soft coral tertiary. Color prefix renamed: `masterial*` → `tootsie*`. Splash screen updated.
- [x] **Complete Moshidon branding removal** — Done June 26. Java comments, German locale strings, donation banner XML all updated. `// MOSHIDON:` provenance markers preserved.
- [x] **UI visual differentiation** — Done June 26. Tab bar: flat with divider line (no shadow), teal PrimaryContainer indicator pill. Profile: gradient overlay on cover image for depth.
- [x] **Sunset Coral visual redesign** — Done June 26 (commit `3fcd15fb9`). Complete palette overhaul:
  - Primary: warm coral (#C00013 light / #FFB4A0 dark)
  - Secondary: warm amber/gold (#7D5800 light / #F0BD48 dark) — changed from teal because user said teal "looks blue"
  - Neutral grays: warm-tinted (#1A1C1A dark surface) replacing Moshidon purple-tinted grays
  - All `purple_primary_*`, `primary_*`, `gray_*` in `colors_custom.xml` overwritten to coral/warm values
  - `colors_masterial.xml` fully rewritten with coral/amber M3 tonal palette (all 3 contrast levels)
  - Deleted `values-v31/colors.xml` and `values-v34/colors.xml` — these redirected `m3_sys_*` to `@android:color/system_*` (Android dynamic color from wallpaper), overriding our palette on API 31+/34 devices. This was the root cause of the theme not applying on the Samsung S24 Ultra.
  - `UiUtils.setUserPreferredTheme()` hardcodes `MATERIAL3` palette instead of reading saved Moshidon color preference
  - Shape language: all buttons 100dp pill → 12dp rounded rectangle; dialogs 28dp → 16dp; popups/text fields 4dp → 12dp; bottom sheet 28dp → 16dp; compose FAB 4dp → 16dp
  - Extended colors: boost → teal, launcher icon → coral, splash → coral
- [x] **Collections icon in profile top bar** — Done June 26 (commit `3fcd15fb9`). `profile_own.xml`: Manage Collections menu item now has `android:icon="@drawable/ic_fluent_collections_24_regular"` and `android:showAsAction="always"` — visible as action icon instead of hidden in overflow.
- [x] **v0.1.0-alpha.2 release** — Done June 26. APK uploaded to GitHub release. https://github.com/jcrabapple/tootsie/releases/tag/v0.1.0-alpha.2
- [ ] Translate `mo_*` strings to Tootsie for the untranslated English fallback in `values/strings_mo.xml`
- [ ] First stable 0.1.0 release

## Session: June 26, 2026 — Sunset Coral Visual Redesign

### Problem

Previous session changed color values (teal palette) but app still looked identical to Moshidon. User reported "still blue/periwinkle/purple" after multiple iterations.

### Root Causes Found (3 layers)

1. **`purple_primary_*` still periwinkle** — `ColorPalette.Purple` in `palettes.xml` reads `@color/purple_primary_*` directly, bypassing our `primary_*` aliases. Fix: overwrote all `purple_primary_*` hex values to coral in `colors_custom.xml`.

2. **`gray_*` still purple-tinted** — `ColorPalette.Dark` sets `colorM3Background`/`colorM3Surface` → `?colorGray900` = old `#18131c` (purple-tinted dark). Fix: overwrote all `gray_*` to warm-tinted neutrals (#1A1C1A etc.) in `colors_custom.xml`.

3. **`values-v34/colors.xml` — THE ROOT CAUSE** — On Android 14 (API 34, Samsung S24 Ultra), this file overrode ALL `m3_sys_*` colors to `@android:color/system_*` (Android's dynamic color system that derives colors from the user's wallpaper). Since Jason's wallpaper had purple/periwinkle tones, the system fed those into the app, completely overriding our coral palette. `values-v31/colors.xml` did the same for Android 12+. Fix: deleted both files.

### Secondary palette: teal → amber

The "Edit profile" button (uses `Widget.Mastodon.M3.Button.Tonal` → `?colorM3SecondaryContainer`) and Posts/Media filter buttons initially used teal (#004F4F dark container). User perceived teal as "blue." Fix: changed entire secondary palette from teal to amber/gold in `colors_masterial.xml`.

### Build environment note

The HANDOFF.md says `JAVA_HOME=$HOME/.jdks/ms-21.0.9/` — this path no longer exists on the current Aurora install. Working path is `JAVA_HOME=/var/home/jason/.local/share/jbr` (copied from Android Studio flatpak's `/app/extra/jbr`). Build command:

```bash
cd ~/projects/tootsie
JAVA_HOME=/var/home/jason/.local/share/jbr ./gradlew :mastodon:clean :mastodon:assembleGithubDebug
```

### Files changed in redesign (commit 3fcd15fb9)

```
mastodon/src/main/java/.../ui/utils/UiUtils.java              (hardcode MATERIAL3)
mastodon/src/main/res/drawable/bg_alert.xml                    (28dp → 16dp)
mastodon/src/main/res/drawable/bg_alert_button.xml             (shape update)
mastodon/src/main/res/drawable/bg_bottom_sheet.xml            (28dp → 16dp)
mastodon/src/main/res/drawable/bg_button_m3_*.xml (13 files)  (100dp/20dp → 12dp)
mastodon/src/main/res/drawable/bg_button_new_posts*.xml        (18dp → 12dp)
mastodon/src/main/res/drawable/bg_compose_button.xml          (4dp → 16dp)
mastodon/src/main/res/drawable/bg_m3_*text_field*.xml (4 files)(4dp → 12dp)
mastodon/src/main/res/drawable/bg_popup.xml                    (4dp → 12dp)
mastodon/src/main/res/menu/profile_own.xml                     (Collections icon)
mastodon/src/main/res/values-v31/colors.xml                    (DELETED)
mastodon/src/main/res/values-v34/colors.xml                    (DELETED)
mastodon/src/main/res/values/colors.xml                        (coral updates)
mastodon/src/main/res/values/colors_custom.xml                 (coral/warm overrides)
mastodon/src/main/res/values/colors_masterial.xml              (full rewrite)
mastodon/src/main/res/values/styles.xml                       (splash color)
```

## Session: June 26, 2026 — Bug Fixes

Three bugs found during on-device testing against dmv.community. All three would have been invisible in unit tests — they only manifest against a real Mastodon 4.6 server.

### Bug 1: CollectionDetailFragment endless spinner

**Symptom:** Tapping a collection in Manage Collections → endless loading spinner.

**Root cause:** `CollectionDetailFragment.onCreate()` never called `loadData()` when the preloaded Collection (from the index endpoint) lacked `accounts`. The code fell through without triggering an API call.

**Fix (commit `ba971dbd3`):** Added guard after preloaded-accounts check:
```java
if (collectionID != null && data.isEmpty()) {
    loadData();
}
```
This matches the pattern in `ListTimelineFragment.onCreate()`.

### Bug 2: Create/edit form never renders

**Symptom:** Tapping +FAB → spinner forever, form never appears.

**Root cause:** `CreateCollectionFragment` extends `BaseSettingsFragment` which extends `BaseRecyclerFragment`. The base class's `LoaderFragment` shows the `loading` spinner by default (XML visibility) and only hides it when `onDataLoaded()`/`showContent()` is called. Since no list data loading was triggered, `showContent()` never fired. The form's RecyclerView was technically on top of the spinner in the FrameLayout z-order, but until `showContent()` is called, the `content` view isn't activated.

**Fix (commit `28ecb899f`):** Call `onDataLoaded(Collections.emptyList(), false)` at the end of `onViewCreated()` (after `super.onViewCreated()`). This triggers `showContent()` which hides the spinner and reveals the form.

### Bug 3: Create/edit returns 422 — "Error parsing an API error"

**Symptom:** Filling form, tapping Create → toast "Error parsing an API error", form stays.

**Root cause — two layers:**

1. **Missing required fields.** `CreateCollection.Request` POJO was missing `sensitive` and `discoverable` fields. The server's `collection_creation_params` requires both booleans. This caused a 422 on every create.

2. **Empty optional strings cause silent 422.** Even after adding `sensitive`/`discoverable`, sending `"tag_name": ""` (empty string) caused a 422 with a misleading error ("Name can't be blank" — even though `name` was filled). The server-side parameter parsing appears to corrupt when empty-string optional fields are present.

**Nested error format.** The 422 response uses `{"error":{"error":"...","details":{...}}}` — a nested structure. Moshidon's `MastodonAPIController` tries `error.get("error").getAsString()` but `.get("error")` returns a `JsonObject`, not a string. `.getAsString()` throws `UnsupportedOperationException`, caught by the generic handler which produces "Error parsing an API error."

**Fix (commits `05ab16f2c` and `28ecb899f`):**
1. Added `sensitive` and `discoverable` boolean fields to `CreateCollection.Request` and `Collection` model
2. Switched `CreateCollection` and `UpdateCollection` from POJO+Gson to `JsonObject` body builders that only include optional fields when non-empty:
```java
JsonObject body = new JsonObject();
body.addProperty("name", name);  // always required
if (tagName != null && !tagName.isEmpty())
    body.addProperty("tag_name", tagName);
// ... same for description, language
body.addProperty("sensitive", sensitive);
body.addProperty("discoverable", discoverable);
```

**Debugging technique:** When the server returns 422, test with `curl` directly against the Mastodon instance. The token is at `~/.hermes/secrets/mastodon_token`. Use Python `subprocess.run()` with the token to avoid shell quoting issues:
```python
import subprocess, json
token = open("/var/home/jason/.hermes/secrets/mastodon_token").read().strip()
auth = "Authorization: Bearer *** + token
data = json.dumps({"name": "test", ...})
subprocess.run(["curl", "-sS", "-X", "POST", url, "-H", auth, "-H", "Content-Type: application/json", "-d", data])
```

### Verified on device (Samsung S24, wireless ADB)

All three flows confirmed working:
- **Create:** Form opens, fill name, tap Create → collection appears in list
- **Edit:** Kebab menu → Edit, change name, tap Save → updates in list
- **Delete:** Kebab menu → Delete, confirm → removed

### Key rules learned

1. **Always `loadData()` in `onCreate`** for any `BaseStatusListFragment` subclass — unless you've synchronously called `onDataLoaded()`.
2. **Always `onDataLoaded(emptyList)` in `onViewCreated`** for form fragments extending `BaseSettingsFragment` — otherwise the form hides behind the base loader spinner.
3. **Never use Gson POJOs for request bodies with optional fields.** Use `JsonObject` and only add fields when they have values. Empty strings in Mastodon 4.6 collections cause 422s.
4. **Test new API endpoints with `curl` against the real server** before building the Java layer. The Mastodon 4.6 spec differs from implementation in subtle ways.

## Architectural Notes for the Next Session

**No Room.** Moshidon does not use `androidx.room`. Don't add `@Entity` classes, DAOs, or migrations for collections — just rely on `@Parcel` and Moshidon's in-memory cache + Parcel round-trip-to-disk for persistence.

**`SearchResult` is upstream.** Don't add a `COLLECTION` variant to `SearchResult.Type` to graft Collections into the existing Featured tab row types. The polymorphic switch is touched in too many places. Instead, use a dedicated sub-fragment for Collections inside the Featured tab area. The existing `showAllEndorsedAccounts()` stub is the natural seam.

**No new caches needed.** Moshidon's existing in-memory caches on `AccountSession` work for Collection objects out of the box because they're `@Parcel`-annotated. Just stash `Collection` objects into the existing caches via the standard patterns (look at `PinnableStatusListFragment` for a fragment that holds objects in a list field).

**`interactionPolicy` is independent of `discoverable`.** The user's profile setting "Feature me in discovery experiences" (`discoverable`) is a hard off-switch. The `interactionPolicy.canFeature` field only describes how an *opted-in* account handles inclusion requests. Both checks need to happen in the add-to-collection flow.

**OAuth safety.** `RevokeCollectionMembership` is the safety primitive — any account can self-revoke from any collection. Make sure the UI surfaces this prominently in the notification row for `ADDED_TO_COLLECTION`.

**Build verification cadence.** Run `./gradlew :mastodon:assembleGithubDebug` after each fragment is written. Use `./gradlew :mastodon:compileGithubDebugJavaWithJavac --rerun-tasks` for fast Java-only verification when iterating.

## Recommended Reading Order

1. `model/Collection.java` — the data shape
2. `api/requests/collections/GetCollection.java` — the simplest API call
3. `api/requests/collections/CreateCollection.java` — JSON body construction
4. `model/FollowList.java` — the small-template model
5. `fragments/ListMembersFragment.java` — closest existing analog for `CollectionDetailFragment`
6. `fragments/CreateListFragment.java` — closest existing analog for `CreateCollectionFragment`
7. `fragments/ProfileFeaturedFragment.java` — the Featured tab code that needs to embed Collections

## Files Touched in Foundation Layer

```
mastodon/build.gradle                                                          (rebrand)
mastodon/src/main/AndroidManifest.xml                                          (unchanged, uses mo_app_name)
mastodon/src/main/java/.../api/requests/oauth/CreateOAuthApp.java              (rebrand)
mastodon/src/main/java/.../api/session/AccountSessionManager.java              (rebrand)
mastodon/src/main/java/.../fragments/settings/SettingsAboutAppFragment.java    (rebrand)
mastodon/src/main/java/.../model/Account.java                                  (interactionPolicy field)
mastodon/src/main/java/.../model/Collection.java                               (NEW)
mastodon/src/main/java/.../model/InteractionPolicy.java                        (NEW)
mastodon/src/main/java/.../model/Notification.java                             (collection field + null-safe validation)
mastodon/src/main/java/.../model/NotificationType.java                         (new enum values)
mastodon/src/main/java/.../api/requests/collections/{10 files}.java            (NEW)
mastodon/src/main/res/drawable/ic_launcher_foreground.xml                      (NEW — placeholder)
mastodon/src/main/res/values*/strings_mo.xml (52 locales)                       (rebrand)
mastodon/src/nightly/java/.../updater/GithubSelfUpdaterImpl.java               (rebrand)
README.md                                                                       (rewritten)
HANDOFF.md                                                                      (this file)
```

## Commit Cadence Going Forward

One commit per fragment, ideally. Bundle closely-related fragments (e.g. `CollectionDetailFragment` + its layout + its display item types) into one commit. Bundle UI string additions with the fragment that uses them. Keep model/API changes separate from UI changes.

Commit message prefix convention in this repo:
- `feat(collections):` — new user-visible feature
- `fix:` — bug fix
- `refactor:` — no behavior change
- `docs:` — docs only
- `chore:` — build/tooling/dependency changes
- `rebrand:` — naming/branding changes

## Pulling Upstream

When Moshidon's `rewrite` branch gets fixes worth pulling:

```bash
git fetch upstream
git log upstream/rewrite --oneline -10    # review what changed
git merge upstream/rewrite                # or rebase if you prefer
```

Watch for upstream adding their own Collections support — when they do, we want to absorb it but preserve our Tootsie-specific bits (the comment markers, the model extensions). Resolve by accepting upstream's model class and re-applying our `// TOOTSIE:` annotations on the new Tootsie additions.