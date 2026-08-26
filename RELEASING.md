# Releasing

## Cutting a release

```sh
git tag v1.2.3
git push origin v1.2.3
```

That's the whole trigger. The `Release APK` workflow builds a signed APK, verifies
the signature, and attaches it to a GitHub Release named after the tag, with
auto-generated notes.

- `versionName` comes from the tag with the leading `v` stripped (`v1.2.3` -> `1.2.3`).
- `versionCode` is `git rev-list --count HEAD` -- monotonic and reproducible, so it
  never needs to be hand-edited or committed.

To build a signed APK without publishing anything, run the workflow manually from
the Actions tab; it uploads the APK as a build artifact instead.

## One-time repository setup

The workflow needs four secrets (Settings -> Secrets and variables -> Actions):

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | contents of `~/.android/keystores/KEYSTORE_BASE64.txt` |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | `forrcaho-release` |
| `RELEASE_KEY_PASSWORD` | same as the store password |

The build fails loudly if `KEYSTORE_BASE64` is missing rather than quietly shipping
an unsigned APK.

## Building a signed release locally

Signing credentials are read from `~/.gradle/gradle.properties` (never from this
repository):

```properties
RELEASE_STORE_FILE=/path/to/forrcaho-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=forrcaho-release
RELEASE_KEY_PASSWORD=...
```

Then `./gradlew assembleRelease`. With no credentials present the release build
still succeeds, just unsigned -- a fresh clone is never blocked from building.

## Setting up a second machine

1. Install a JDK 21+ (JDK 25 is what CI and the primary machine use).
2. Install Android Studio, or just the command line tools, and let it provision
   the SDK. `local.properties` is deliberately not committed; set `ANDROID_HOME`
   or let Studio write its own.
3. Copy the keystore across by hand -- over `scp` or a password manager's secure
   file storage, never through the repository -- and add the four properties above
   to that machine's `~/.gradle/gradle.properties`.

Only step 3 matters for signing. Skip it and you can still develop and run debug
builds; you just cannot cut a release from that machine. Tagging works from
anywhere regardless, since CI does the signing.

> **Note on debug builds across machines.** Android's debug keystore is generated
> per machine, so a debug APK from one machine cannot be installed over a debug APK
> from another -- Android rejects it as a signature mismatch. Uninstall first, or
> copy `~/.android/debug.keystore` between machines to keep them consistent.
