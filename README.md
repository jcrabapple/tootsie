<img src="mastodon/src/main/ic_launcher-playstore.png" alt="Tootsie Logo" width="200" height="200">

## Tootsie — a Material You Mastodon client with first-class Fediverse Collections

> A fork of [Moshidon](https://github.com/LucasGGamerM/moshidon) (itself a fork of [megalodon](https://github.com/sk22/megalodon)). Tootsie adds full support for Mastodon 4.6's [Collections](https://blog.joinmastodon.org/2026/04/designing-collections/) feature ([FEP-7aa9](https://w3id.org/fep/7aa9)) on top of an already feature-rich, modern Android client.

## About

Tootsie keeps everything Moshidon already does well — Material You theming, fully federated timeline, drafts & scheduled posts, bookmarks, alt-text accessibility, multi-account crossposting, on-device polish — and adds a first-class implementation of Fediverse Collections: create, edit, share, browse, and manage collections of up to 25 profiles, with full respect for opt-in preferences, per-collection revoke, and the new `added_to_collection` / `collection_update` notifications.

## Status

**Pre-alpha.** This is the rewrite branch's first real fork. Expect rough edges. Tracking Moshidon's `rewrite` branch upstream; we rebase selectively as upstream stabilizes.

| Surface | Status |
|---|---|
| App rebrand (package ID, OAuth scheme, update URLs) | ✅ Done |
| Build on Android SDK 35 | 🔄 Pending verification |
| Run against a 4.6 instance (dmv.community) | 🔄 Pending verification |
| Collections API client (6 endpoints + revoke) | 🔄 Next |
| Collections Room cache + offline | 🔄 Next |
| Collections UI (list, detail, editor) | ⏳ Planned |
| Profile "Featured" tab integration | ⏳ Planned |
| `added_to_collection` / `collection_update` notifications | ⏳ Planned |
| `interactionPolicy.canFeature` handling | ⏳ Planned |
| First public release (0.1.0) | ⏳ Planned |

## Download

Tootsie is not yet on any store. Once we ship 0.1.0, it'll be available via:

- **GitHub Releases** (primary) — `tootsie.apk` stable, `tootsie-nightly.apk` nightly
- IzzyOnDroid F-Droid repo (likely; same package ID family as Moshidon)

Until then, the only way to run Tootsie is to build it from source.

## Build from source

```bash
git clone https://github.com/jcrabapple/tootsie
cd tootsie
./gradlew :mastodon:assembleGithubDebug
# APK at: mastodon/build/outputs/apk/github/debug/mastodon-github-debug.apk
```

Requires JDK 17+, Android SDK with platform 35, and NDK r26+ for some dependencies.

## Upstream

Tootsie tracks Moshidon's `rewrite` branch. To pull upstream changes:

```bash
git fetch upstream
git merge upstream/rewrite
```

We rebase selectively — Moshidon's Collections implementation will likely land eventually, and we'll absorb it when it does.

## Credits

Tootsie is built on the work of:

- **[Moshidon](https://github.com/LucasGGamerM/moshidon)** by LucasGGamerM and contributors — the actual app
- **[megalodon](https://github.com/sk22/megalodon)** by sk22 — Moshidon's upstream fork
- **[Mastodon for Android](https://github.com/mastodon/mastodon-android)** by Mastodon gGmbH — the original official client

If you find Tootsie useful, please consider supporting [Moshidon](https://github.com/sponsors/LucasGGamerM) — without them, none of this exists.

## License

GNU GPL v3.0, inherited from Moshidon and upstream Mastodon for Android.