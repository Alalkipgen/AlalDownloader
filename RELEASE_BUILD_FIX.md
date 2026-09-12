# Release Build Fix - Complete Documentation

## Root Cause: Why Signed Release APKs Were Missing

### Primary Issues

1. **Conditional Keystore Decoding Bug in release.yml**
   - Line 29: `if: github.event_name == 'push' && startsWith(github.ref, 'refs/tags/')`
   - Keystore only decoded for tag pushes
   - Manual `workflow_dispatch` runs skipped keystore decode entirely
   - Line 35: `KEYSTORE_PATH` set to empty string for manual triggers
   - Result: Gradle fell back to debug signing config

2. **android.yml Produced Unsigned APKs**
   - Line 44: Executed `assembleRelease` with NO signing environment variables
   - Build succeeded but APK was unsigned/debug-signed
   - No artifacts uploaded to GitHub Releases

3. **Missing ProGuard Enhancements**
   - No `SourceFile`/`LineNumberTable` attributes for readable crash traces
   - No explicit Service/BroadcastReceiver keeps
   - Missing v1/v2/v3/v4 APK signing enablement

4. **Workflow Redundancy**
   - Four overlapping workflows: android.yml, ci.yml, build.yml, release.yml
   - Conflicting configurations and duplicated logic

## Solution Implemented

### 1. Fixed release.yml Workflow

**Key Changes:**
- Always decode keystore for both tag pushes AND workflow_dispatch
- Explicit secret validation before build
- APK signature verification with apksigner
- Version tag from git refs or manual input
- Artifact renaming: `AlalDownloader-<version>.apk`
- Fail-fast error handling (no continue-on-error)

**New Steps:**
```yaml
- name: Validate secrets
  run: |
    if [[ -z "${{ secrets.KEYSTORE_BASE64 }}" ]]; then
      echo "::error::Missing KEYSTORE_BASE64 secret"
      exit 1
    fi
    # ... validates all 4 secrets

- name: Decode keystore
  run: |
    echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > ${{ runner.temp }}/keystore.jks

- name: Verify APK signature
  run: |
    $ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
      app/build/outputs/apk/release/app-release.apk
```

### 2. Enhanced Gradle Signing Config

**app/build.gradle.kts changes:**

```kotlin
// Version management
val buildTag = providers.environmentVariable("GITHUB_REF").orNull
    ?.takeIf { it.startsWith("refs/tags/v") }?.removePrefix("refs/tags/v")
    ?: providers.environmentVariable("VERSION_TAG").orNull
val appVersionName = buildTag?.takeIf(String::isNotBlank) ?: "0.1.0"

// Signing with v1/v2/v3/v4 enablement
signingConfigs {
    create("release") {
        if (hasReleaseSigning) {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        } else {
            initWith(getByName("debug")) // Local fallback
        }
    }
}

// Build types
buildTypes {
    getByName("release") {
        isMinifyEnabled = true
        isShrinkResources = true
        isDebuggable = false
        signingConfig = signingConfigs.getByName("release")
    }
    getByName("debug") {
        applicationIdSuffix = ".debug"
        versionNameSuffix = "-debug"
        isDebuggable = true
    }
}

// META-INF exclusions
packaging {
    resources {
        excludes += setOf(
            "META-INF/LICENSE.md",
            "META-INF/DEPENDENCIES",
            "META-INF/NOTICE"
        )
    }
}
```

### 3. Enhanced ProGuard Rules

**Added to app/proguard-rules.pro:**

```proguard
# Readable crash traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Services and BroadcastReceivers
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends androidx.work.Worker

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
```

### 4. Simplified CI Workflow

**ci.yml changes:**
- Debug builds only (no release)
- Added permissions: `contents: read`
- Timeout limits
- Proper artifact naming
- Test report uploads

### 5. Deleted Redundant Workflows

- **android.yml** - Had unsigned release build
- **build.yml** - Duplicate of ci.yml

## Required GitHub Secrets

Add in **Settings → Secrets and variables → Actions**:

1. **KEYSTORE_BASE64** - Base64-encoded keystore
   ```bash
   base64 -w 0 keystore.jks
   ```

2. **KEYSTORE_PASSWORD** - Keystore password
3. **KEY_ALIAS** - Key alias (e.g., `alal`)
4. **KEY_PASSWORD** - Key password

## Generate Keystore

```bash
keytool -genkeypair -v -keystore keystore.jks -alias alal \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Alal Downloader,O=Alal,C=US"
```

## How to Release

### Automatic (Tag Push)
```bash
git tag -a v1.0.0 -m "Release 1.0.0"
git push origin v1.0.0
```

### Manual (workflow_dispatch)
1. Go to Actions → Release Build
2. Click Run workflow
3. Optionally specify version (e.g., v1.0.0)

## Verify Signature

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

## Files Changed

1. **.github/workflows/release.yml** - Fixed keystore decode, added verification
2. **.github/workflows/ci.yml** - Simplified to debug only
3. **app/build.gradle.kts** - Enhanced signing, version management, packaging
4. **app/proguard-rules.pro** - Added crash trace and keep rules
5. **README.md** - Added release documentation
6. **Deleted:** android.yml, build.yml

## Output Artifacts

**Release APK:**
```
AlalDownloader-v1.0.0.apk
```

**Release AAB:**
```
AlalDownloader-v1.0.0.aab
```

Both uploaded to GitHub Releases with auto-generated release notes.

## Summary

**Root Cause:** Conditional keystore decoding made workflow_dispatch builds use debug signing.

**Fix:** Always decode keystore, validate secrets upfront, verify signatures, enhance ProGuard.

**Result:** Properly signed, minified release APKs and AABs ready for distribution.
