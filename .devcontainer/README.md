# Codespaces

A remote build box for this repo: Gradle builds, the smoke tests, Morphe CLI runs and
jadx/apktool checks. Use a **4-core** machine (16 GB RAM); the smoke tests start a 4 GB JVM for
every test class.

Not available here: the Android emulator and adb (Codespaces has no hardware acceleration), so
cold starts and UI checks stay on the `morphe-test` AVD. The `.claude/` agents, hooks and tools are
not tracked in git, so they are not here either.

## First start

`setup.sh` runs once when the codespace is created and installs JDK 21, the Android SDK
(build-tools 36.0.0, platforms 35 and 36), jadx 1.5.6, apktool 3.0.3, bundletool 1.18.3,
APKEditor 1.4.9 and Morphe CLI 1.19.0, all checked against SHA-256. Commands: `jadx`, `apktool`,
`bundletool`, `apkeditor`, `morphe-cli`, `apkanalyzer`, `apksigner`, `zipalign`, `aapt2`.

## Morphe registry access

Gradle reads the Morphe plugin and patcher from the MorpheApp GitHub Packages registry with the
codespace's own `GITHUB_TOKEN` (verified 2026-11-07: the first build resolved and built the bundle).
`setup.sh` writes `gpr.user` to `~/.gradle/gradle.properties`, because a codespace has no
`GITHUB_ACTOR`. Editor terminals have `GITHUB_TOKEN`; `gh codespace ssh` sessions don't, so load it
there first:

```
export GITHUB_TOKEN="$(sed -n 's/^GITHUB_TOKEN=//p' /workspaces/.codespaces/shared/.env)"
```

Should a build ever fail with 401/403 (the codespace token lost registry access):

1. Create a classic token with only the `read:packages` scope.
2. Add it at <https://github.com/settings/codespaces> as a secret named `MORPHE_PACKAGES_TOKEN`,
   limited to this repository.
3. Restart the codespace, then run `bash .devcontainer/setup.sh` again (it adds `gpr.key`).

## Test fixtures

`apks/` is gitignored, so upload the fixtures from your PC (only `base.apk` per app; the full set
is ~5.5 GB). Streaming a tar over ssh copies only those files:

```
tar -cf - apks/*/base.apk | gh codespace ssh -c <codespace-name> -- tar -xf - -C /workspaces/Heval-Morphe-Patches
```

(`gh codespace list` shows the name.) They stay until the codespace is deleted.

## Build and test

```
./gradlew :patches:buildAndroid
./gradlew :patches:cleanTest :patches:test --no-build-cache --rerun-tasks
./gradlew :patches:test --tests SofascoreSmokeTest --rerun-tasks
```

Stop the codespace when you are done; stopped codespaces still count against storage.
