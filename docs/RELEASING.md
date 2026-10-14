# Building and releasing Quillbox

Quillbox ships for eight targets from one repository:

| Target | Artifact | Built by |
|---|---|---|
| Android, ChromeOS | `quillbox-<tag>.apk` | Android CI (`app/`) |
| Windows | `.msi` installer + portable `.zip` | Desktop CI on a Windows runner |
| macOS | `.dmg` (unsigned) + portable `.tar.gz` | Desktop CI on a macOS runner |
| Linux x64 | `.deb` + portable `.tar.gz` | Desktop CI on Ubuntu x64 |
| Raspberry Pi OS (64-bit), Linux arm64 | `.deb` + portable `.tar.gz` | Desktop CI on Ubuntu arm64 |
| Web / self-hosted server (any OS with Java 17+, incl. Termux, ChromeOS Linux, Raspberry Pi) | `quillbox-web-<tag>-any-jvm.zip` | Web CI |
| iOS | `Quillbox-iOS-simulator.app.zip` (unsigned simulator build) | iOS CI on a macOS runner |

Every target shows the same Outlook-style client. The desktop apps and the Android app embed
the web client: they start the same Ktor backend on `127.0.0.1` (in the app process on
Android) and show the UI in your browser / in a WebView. The iOS app shows the same UI served
by a Quillbox server on your network (Apple allows no JVM on iOS). Every feature of the web
client (junk protection, AI rule proposals, etc.) is therefore present everywhere from a
single code base.

**Pushing a tag such as `v1.4.0` triggers all of this.** Each workflow attaches its files to
the same GitHub Release (`Releases` → `Quillbox v1.4.0`) as it finishes.

## Releasing from Termux (Android)

Native installers can only be produced on their own OS (WiX on Windows, `hdiutil` on macOS,
Xcode on macOS for iOS). Termux runs on an ARM Android phone, so the practical workflow is:
build and run what a phone *can* build locally (the web/JVM server, optionally the APK), and
let GitHub Actions build everything else from a tag you push.

### 1. One-time setup

```bash
pkg update && pkg upgrade -y
pkg install -y git openjdk-17 gh unzip
termux-setup-storage            # optional: access to ~/storage/downloads

git config --global user.name  "Paul D. Pruitt"
git config --global user.email "socrtwo@gmail.com"

gh auth login                   # GitHub CLI: choose HTTPS, log in with a browser/token
git clone https://github.com/socrtwo/quillbox.git
cd quillbox
```

Gradle downloads its own wrapper on first use; give it memory head-room on a phone:

```bash
mkdir -p ~/.gradle
printf 'org.gradle.jvmargs=-Xmx1536m\norg.gradle.daemon=false\n' >> ~/.gradle/gradle.properties
```

### 2. Build and run the web/JVM server on the phone itself

```bash
cd ~/quillbox/web
chmod +x gradlew
./gradlew build distZip --no-daemon           # runs the engine tests, packages build/distributions/quillbox-web-1.4.0.zip

# Run it right here on the phone and open http://localhost:8080 in the phone's browser
cd build/distributions && unzip -o quillbox-web-1.4.0.zip && cd quillbox-web-1.4.0
QUILLBOX_DATA_DIR=$HOME/.quillbox PORT=8080 ./bin/quillbox-web
```

`termux-wake-lock` keeps it running with the screen off. The same zip is the release asset
for Windows/macOS/Linux/Raspberry Pi servers: copy it over, `unzip`, run `bin/quillbox-web`
(or `bin\quillbox-web.bat` on Windows) with Java 17+ installed.

To try it against the built-in demo mailbox instead of a real account:

```bash
cd ~/quillbox/web && ./gradlew demo --no-daemon     # then sign in as demo@quillbox.test / demo
```

### 3. Cut a release (all eight targets, built by GitHub Actions)

```bash
cd ~/quillbox
git pull
# bump the version if needed: app/build.gradle.kts (versionCode/versionName),
#   web/build.gradle.kts (version), desktop/build.gradle.kts (version/packageVersion),
#   ios/project.yml (CFBundleShortVersionString/MARKETING_VERSION)
git commit -am "Release v1.4.0"
git push
git tag -a v1.4.0 -m "Quillbox 1.4.0"
git push origin v1.4.0

gh run watch                    # follow the four workflows
gh release view v1.4.0 --web    # open the release page with every artifact
gh release download v1.4.0 -D ~/storage/downloads/quillbox-v1.4.0   # pull the files to the phone
```

The Android CI signs the APK automatically when the repository secrets
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` exist (see the README);
without them the release carries `quillbox-v1.4.0-unsigned.apk`, which Android will not install
until it is signed. You can sign it in Termux:

```bash
pkg install -y apksigner
keytool -genkeypair -v -keystore ~/quillbox-release.jks -alias quillbox -keyalg RSA -keysize 2048 -validity 10000
apksigner sign --ks ~/quillbox-release.jks --ks-key-alias quillbox \
  --out quillbox-v1.4.0.apk ~/storage/downloads/quillbox-v1.4.0/quillbox-v1.4.0-unsigned.apk
```

### 4. Building the APK in Termux itself (optional, unofficial)

Google does not ship the Android SDK for ARM Linux, so an unofficial aarch64 build of the SDK
tools is needed. This works but is not supported by Google:

```bash
pkg install -y aapt2 android-tools   # Termux packages
# Unofficial aarch64 build-tools / platform-tools (Lzhiyong/android-sdk-tools releases):
mkdir -p ~/android-sdk && cd ~/android-sdk
curl -LO https://github.com/lzhiyong/android-sdk-tools/releases/download/34.0.4/android-sdk-tools-static-aarch64.zip
unzip -o android-sdk-tools-static-aarch64.zip
# Platform 35 and the licenses can be fetched with the same tools' sdkmanager:
./cmdline-tools/bin/sdkmanager --sdk_root=$HOME/android-sdk "platforms;android-35"
export ANDROID_HOME=$HOME/android-sdk
cd ~/quillbox && ./gradlew assembleDebug --no-daemon    # app/build/outputs/apk/debug/app-debug.apk
```

If that route gives you trouble, use the CI build from step 3 — it is the supported one.

### What cannot be built on a phone

- **Windows `.msi`, macOS `.dmg`, iOS**: they require WiX/Windows, macOS/`hdiutil` and
  Xcode respectively. The tag push in step 3 builds them on GitHub's runners.
- **A signed, installable iOS build**: needs an Apple Developer account, a signing
  certificate and a provisioning profile. The CI produces an unsigned simulator `.app`.
- **ChromeOS**: no separate build; install the Android APK, or run the Linux `.deb`
  / web zip in the ChromeOS Linux container.

## Releasing from a desktop

Exactly the same tag push (`git tag v1.4.0 && git push origin v1.4.0`). To build locally:

```bash
cd desktop && ./gradlew packageDistributionForCurrentOS   # installer for the OS you are on
cd desktop && ./gradlew createDistributable               # portable app image
cd web     && ./gradlew distZip                           # server zip
cd ios     && xcodegen generate && open Quillbox.xcodeproj  # macOS only
./gradlew assembleRelease                                 # Android, with the SDK installed
```

## Updating the organisation table

The impersonation detector's extended table lives in `web/src/main/resources/brands/*.tsv`
(one organisation per line: name, domains, aliases, category). After editing:

```bash
python3 scripts/verify-brands.py   # checks every domain resolves in DNS
python3 scripts/gen-brands.py      # regenerates BrandKnowledgeBaseExtra.kt (shared by web, desktop and Android)
```
