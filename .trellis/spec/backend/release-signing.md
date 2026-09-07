# Android Release Signing and Publication

> Executable contract for producing a distributable Void Music APK without exposing or replacing its long-lived identity.

## Scenario: Build, validate, and publish a signed Release

### 1. Scope / Trigger

Apply this contract when changing Gradle signing, versioning, ABI filters, R8 rules, native build inputs, release scripts, GitHub Actions, tags, or Release artifacts.

### 2. Signatures

Local Gradle properties:

```properties
storeFile=/secure/path/void-music-release.jks
storePasswordFile=/secure/path/release-password.txt
keyAlias=void-music-release
keyPasswordFile=/secure/path/release-password.txt
```

CI environment consumed by Gradle:

- `VOID_MUSIC_STORE_FILE`
- `VOID_MUSIC_STORE_PASSWORD`
- `VOID_MUSIC_KEY_ALIAS`
- `VOID_MUSIC_KEY_PASSWORD`

Repository Secrets consumed only by the Release job:

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

Validation commands:

```bash
./gradlew -PenableNativeBuild=true :app:assembleRelease
bash scripts/validate_release_shrinker.sh
bash scripts/prepare_release.sh +  app/build/outputs/apk/release/app-release.apk +  release-dist +  v<VERSION_NAME>
```

### 3. Contracts

- Release APKs use the dedicated `void-music-release` PKCS12 key. Expected certificate SHA-256:
  `D1:51:A2:61:C0:CB:73:86:F9:FF:8E:29:91:55:38:C3:7F:4C:88:0E:7F:28:8E:36:8B:90:FC:D3:DB:EC:ED:59`.
- Debug uses application ID suffix `.debug` and its own signature. Debug and test tasks must not require Release secrets.
- Keystore, password files, `keystore.properties`, decoded runner key, and `release-dist/` are never committed or uploaded as cache.
- `preReleaseBuild` depends on `validateReleaseSigningInputs` and fails if any value or keystore file is absent. Never emit an unsigned Release candidate.
- Release enables R8/resource shrinking and packages only `arm64-v8a`. Native build uses NDK `27.2.12479018`, CMake `3.22.1`, and shared libc++.
- Keep JNI declarations, MediaPipe, Flogger stack-inspection frames, and `GeneratedMessageLite` fields according to `proguard-rules.pro`.
- Tag must be exactly `v<APK versionName>`. `prepare_release.sh` verifies signature, expected certificate, package/version metadata, checksum, and build information.
- `validate_release_shrinker.sh` verifies required Flogger/protobuf rules against generated R8 outputs.
- Push/PR to master runs Debug verification. Tag or manual dispatch runs signed Release creation. Only a tag publishes GitHub Release; manual dispatch uploads a verified Actions Artifact.
- Published milestone releases are public, not Draft or Pre-release, and use `latest=false` until stable-phase device gates are enabled.
- Never rotate the signing key as a build fix. Every distributed upgrade must use the same certificate, with encrypted offline backup.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Any signing value missing | fail before Release packaging and name missing field |
| Keystore path missing | fail `validateReleaseSigningInputs` |
| Debug build without secrets | succeed independently |
| Native build disabled for publication | reject release procedure |
| APK certificate mismatch | `prepare_release.sh` fails; upload/publish does not run |
| Tag/versionName mismatch | validator fails; no GitHub Release |
| Required R8 contract absent | shrinker validator fails |
| Tag already has a Release | edit metadata and upload assets with clobber |
| Manual dispatch | create signed artifact only; no GitHub Release |
| Missing GitHub keystore secret | fail without echoing secret |

### 5. Good / Base / Bad Cases

- Good: bump versionCode/versionName, locally validate a signed native Release, commit, tag `v<versionName>`, push master and tag, then verify the published checksum.
- Base: a pull request runs unit tests, Lint, and native Debug with no signing inputs.
- Bad: use the debug key, accept unsigned output, decode the keystore inside the workspace, print secret environment values, or create a tag that differs from APK metadata.

### 6. Tests Required

- Shell syntax-check both release scripts.
- Run Debug unit tests, Lint, and native Debug without signing inputs.
- Prove a Release task fails cleanly when signing inputs are absent.
- Build signed Release; verify APK signature/certificate/version/checksum/build-info and shrinker outputs.
- Check tracked files for keystore/password/release artifacts.
- Signed physical-device: cold start, MediaPipe initialization under R8, hand tracking, colour hit/audio, step playback, imported kit, route change, and background recovery.
- Before stable publication, complete high/mid/low device FPS, segmentation, hit-to-sound latency, 20-minute stability, temperature, and background restore gates.

### 7. Wrong vs Correct

#### Wrong

```yaml
- run: ./gradlew assembleRelease || cp app-debug.apk release.apk
- run: echo "$ANDROID_RELEASE_STORE_PASSWORD"
```

#### Correct

```yaml
- run: ./gradlew -PenableNativeBuild=true :app:assembleRelease
- run: bash scripts/validate_release_shrinker.sh
- run: bash scripts/prepare_release.sh app-release.apk release-dist "$GITHUB_REF_NAME"
```

The correct pipeline is fail-closed: no valid signing identity, native build, shrinker proof, or matching tag means no public artifact.
