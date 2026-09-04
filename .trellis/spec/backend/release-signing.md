# Android Release Signing and Publication

> Contracts for producing a distributable Void Music APK without exposing or replacing its long-lived signing identity.

## Signing identity

- Release APKs must use the dedicated `void-music-release` key. Debug keys and unsigned APKs are never release candidates.
- The keystore and passwords live outside the repository. `*.jks`, `*.keystore`, and the root `keystore.properties` remain ignored.
- The expected certificate SHA-256 is `D1:51:A2:61:C0:CB:73:86:F9:FF:8E:29:91:55:38:C3:7F:4C:88:0E:7F:28:8E:36:8B:90:FC:D3:DB:EC:ED:59`.
- Once an APK is distributed, every upgrade must use the same certificate. Never regenerate or rotate the key as a routine build fix.

## Gradle input contract

Local builds use an untracked root `keystore.properties`. Passwords may be supplied directly or read from repository-external files:

```properties
storeFile=/secure/path/void-music-release.jks
storePasswordFile=/secure/path/release-password.txt
keyAlias=void-music-release
keyPasswordFile=/secure/path/release-password.txt
```

CI uses `VOID_MUSIC_STORE_FILE`, `VOID_MUSIC_STORE_PASSWORD`, `VOID_MUSIC_KEY_ALIAS`, and `VOID_MUSIC_KEY_PASSWORD`. A Release task must fail before packaging if any field or the keystore is unavailable. Debug tasks must remain independent of signing inputs.

## GitHub Actions secret contract

Only the Release job may read these repository Secrets:

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

Decode the keystore only into the ephemeral Runner temp directory. Never echo secret values, upload the keystore, or cache its directory.

## Publication contract

- The tag is `v<APK versionName>` and must match the built APK exactly.
- `scripts/prepare_release.sh` is the shared local/CI validator for APK signature, expected certificate, version metadata, checksum, and build information.
- A tag run publishes a normal GitHub Release automatically. Milestone/internal-test releases are public but use `latest=false`; they are neither Draft nor Pre-release.
- Manual workflow dispatch produces the verified Actions Artifact only and must not create a GitHub Release.
- Stable-phase device acceptance gates are added before publication without weakening signing or metadata validation.

## Quality check

- Run Debug unit tests, Lint, and native Debug build without signing inputs.
- Prove a Release lifecycle task fails cleanly when signing inputs are absent.
- Build a signed Release and run `scripts/prepare_release.sh` with the expected tag.
- Verify no keystore, password file, `keystore.properties`, or generated release directory is tracked.
- Ensure all four Gradle environment names and all four GitHub Secret names match across Gradle, workflow, README, and this spec.
