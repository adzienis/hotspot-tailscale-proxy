# Hotspot Tailscale Proxy

Android wrapper for [`proxyt`](https://github.com/jaxxstorm/proxyt) aimed at hotspot-only use on a private Android LAN.

## What changed

- `upstream-proxyt/` is a local fork of the upstream Go proxy.
- The fork now supports rewriting Tailscale URLs to a local `http://<private-ip>:<port>` base URL instead of always forcing `https://<domain>`.
- The Android app runs the Go server as a foreground service and exposes it on the phone's hotspot IP.

## Why this is local-only

This project is designed for a phone that is acting as the hotspot host. It does **not** depend on public DNS, Let's Encrypt, or inbound internet reachability.

Tailscale's client code allows `http://` control URLs for private IPs and localhost, which is why a hotspot gateway address like `http://192.168.43.1:8080` can work without local TLS.

The intended flow is:

1. Android starts the proxy on something like `http://192.168.43.1:8080`.
2. iPhone joins the Android hotspot.
3. Tailscale on the iPhone is pointed at that local control URL.

## Build the bundled Go binaries

```bash
./gradlew :app:buildGoAndroidBinaries
```

That produces generated binaries for:

```text
app/build/generated/jniLibs/proxyt/arm64-v8a/libproxyt.so
app/build/generated/jniLibs/proxyt/armeabi-v7a/libproxyt.so
app/build/generated/jniLibs/proxyt/x86/libproxyt.so
app/build/generated/jniLibs/proxyt/x86_64/libproxyt.so
```

The Android app launches the packaged `libproxyt.so` directly from `nativeLibraryDir`.
`assembleDebug`, `assembleRelease`, and `installDebug` now depend on this Gradle task automatically.
The multi-ABI build uses Android NDK `29.0.14206865` for `armeabi-v7a`, `x86`, and `x86_64`.

If you want a faster local debug build, you can limit the generated ABI set:

```bash
./gradlew -Pproxyt.abis=arm64-v8a :app:assembleDebug
```

Use a comma-separated list such as `arm64-v8a,x86_64` when you need multiple debug targets. Release builds should be left on the default full ABI matrix.

If you still want a shell entrypoint, `./scripts/build-android-binary.sh` now delegates to the Gradle task above.

## JDK requirement

Use a standard JDK 17 distribution for Android builds and Gradle sync.

GraalVM 17/21 breaks the Android Gradle Plugin `jlink` path on this project. On macOS, a safe default is:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

CI is pinned to Temurin 17 for the same reason.

## Release signing

`assembleRelease` now enables minification and resource shrinking by default.

If you want a signed release locally, set either Gradle properties or environment variables before running the build:

```text
android.release.keystore.path      / ANDROID_KEYSTORE_PATH
android.release.keystore.password  / ANDROID_KEYSTORE_PASSWORD
android.release.key.alias          / ANDROID_KEY_ALIAS
android.release.key.password       / ANDROID_KEY_PASSWORD
```

Then run:

```bash
./gradlew :app:assembleRelease
```

## Open in Android Studio

1. Install an Android SDK if this machine does not already have one.
2. Install Android NDK `29.0.14206865`.
3. Make sure Android Studio/Gradle is using a standard JDK 17, not GraalVM.
4. Open this folder in Android Studio.
5. Let Gradle sync and build/install the app. The Go binaries are generated automatically during the build.

## Install from the terminal

If you already have Android Studio or the Android SDK plus platform-tools installed:

```bash
./scripts/install-android-debug.sh
```

That script will:

1. Create `local.properties` if `ANDROID_HOME` or `ANDROID_SDK_ROOT` is set.
2. Fail fast if the machine is using GraalVM instead of a standard JDK 17.
3. Verify `adb` can see a connected phone.
4. Run `./gradlew :app:installDebug`, which builds the Go binaries automatically.

On the phone, make sure:

1. Developer options are enabled.
2. USB debugging is enabled.
3. You accept the USB debugging trust prompt.

## Important caveats

- This wrapper now bundles `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.
- The app uses `proxyt serve --http-only`; it does not manage certificates.
- The iPhone side still needs a way to set a custom Tailscale control/login server URL.
- The app defaults to a detected private IPv4 address, but hotspot interface names vary by device, so verify the advertised URL shown in the UI.

## CI

GitHub Actions now runs:

1. `go test ./...` in `upstream-proxyt`
2. `:app:lintDebug`
3. `:app:assembleDebug` with `-Pproxyt.abis=arm64-v8a` for a faster verification build
4. a release build path with minification enabled and optional signing via secrets
