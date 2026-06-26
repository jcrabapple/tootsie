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

- [ ] **`ProfileFeaturedFragment` integration** — add a "Collections" section to the Featured tab. NOT by extending the polymorphic `SearchResult` (which would touch every switch in the codebase) — instead, embed a dedicated sub-fragment inside the Featured tab area. The existing `showAllEndorsedAccounts()` stub is the natural place.

### Priority 3: Authoring (your own collections)

- [ ] **`ManageCollectionsFragment`** — list of collections owned by the current account. Entry point: a "Manage collections" button in your own profile's menu. Uses `GetCollections` API request.
- [ ] **`CreateCollectionFragment`** — create/edit form. Fields: title, description (multi-line), language, tag (single hashtag picker). Uses `CreateCollection` / `UpdateCollection`.
- [ ] **`AddToCollectionFragment`** — multi-select account picker for adding members to a collection. Uses `AddAccountsToCollection`. **Respect `account.interactionPolicy.canFeature`** here:
  - `PUBLIC` → add without confirmation, show inline notice
  - `FOLLOWERS` → check viewer-follows-account, reject if not follower, otherwise proceed
  - `MANUAL` → show a "Request to be added" sheet; the account owner will get a notification and accept/deny
  - If account `discoverable == false` → show "This account has opted out of collections" and disable add

### Priority 4: Notifications

- [ ] Wire `NotificationType.ADDED_TO_COLLECTION` into `BaseNotificationsListFragment`'s row layout — render the Collection's title + description snippet + a "View" button + a "Remove me from this collection" button (uses `RevokeCollectionMembership`)
- [ ] Wire `NotificationType.COLLECTION_UPDATE` — render "X updated the collection Y" with a "View" button. The body should show what changed (title/description diff).

### Priority 5: Polish

- [ ] Test against a 4.6 instance (dmv.community is a good target — Jason runs it). Verify all flows: create, edit, add/remove members, revoke, notifications arrive, `canFeature` enforcement
- [ ] Replace the placeholder `drawable/ic_launcher_foreground.xml` (currently uses Moshidon's Mo path) with a proper Tootsie icon
- [ ] Translate `mo_*` strings to Tootsie for the untranslated English fallback in `values/strings_mo.xml`
- [ ] First 0.1.0 release tag

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