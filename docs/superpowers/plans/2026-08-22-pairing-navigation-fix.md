# Pairing Navigation Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make 家庭媒体管家 enter the home screen immediately after pairing and after restoring an existing pairing, then produce a verified `1.0.0-rc2` APK.

**Architecture:** Keep the existing `ConnectViewModel` state and Navigation Compose graph. Add a small testable navigation decision function and call it from a `LaunchedEffect` keyed by `ui.paired`, so navigation occurs on the false-to-true transition and on restored paired state without running on unrelated recompositions.

**Tech Stack:** Kotlin, Jetpack Compose, Navigation Compose, JUnit 4, Gradle 8.9, Android SDK 35, JDK 21.

## Global Constraints

- Do not change the pairing protocol, token persistence, server database, or media features.
- Do not delete the two revoked `codex-smoke-*` device records.
- Set Android `versionCode` to `4` and `versionName` to `1.0.0-rc2`.
- The project is not a Git repository; record verification evidence instead of creating commits.

---

### Task 1: Pairing Navigation Regression

**Files:**
- Create: `source/android/app/src/test/java/com/mediareview/app/feature/connect/ConnectNavigationTest.kt`
- Modify: `source/android/app/src/main/java/com/mediareview/app/feature/connect/ConnectScreen.kt`

**Interfaces:**
- Consumes: `ConnectUiState.paired: Boolean` and `ConnectScreen(onContinue: () -> Unit)`.
- Produces: `continueWhenPaired(paired: Boolean, onContinue: () -> Unit): Unit`.

- [x] **Step 1: Write the failing unit tests**

```kotlin
package com.mediareview.app.feature.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectNavigationTest {
    @Test
    fun unpairedStateDoesNotContinue() {
        var calls = 0
        continueWhenPaired(paired = false) { calls += 1 }
        assertEquals(0, calls)
    }

    @Test
    fun pairedStateContinuesOnce() {
        var calls = 0
        continueWhenPaired(paired = true) { calls += 1 }
        assertEquals(1, calls)
    }
}
```

- [x] **Step 2: Run the targeted test and verify RED**

Run from `source/android` with `JAVA_HOME=C:\Users\30566\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2`:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.mediareview.app.feature.connect.ConnectNavigationTest
```

Expected: compilation fails because `continueWhenPaired` is unresolved.

- [x] **Step 3: Add the minimal navigation implementation**

Add to `ConnectScreen.kt`:

```kotlin
internal fun continueWhenPaired(
    paired: Boolean,
    onContinue: () -> Unit,
) {
    if (paired) onContinue()
}
```

After collecting `ui`, add:

```kotlin
LaunchedEffect(ui.paired) {
    continueWhenPaired(ui.paired, onContinue)
}
```

- [x] **Step 4: Run the targeted test and verify GREEN**

Run the same Gradle command. Expected: both tests pass.

- [x] **Step 5: Run all JVM tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: zero failed tests.

### Task 2: Version and APK Delivery

**Files:**
- Modify: `source/android/app/build.gradle.kts`
- Create: `dist/家庭媒体管家-1.0.0-rc2.apk`

**Interfaces:**
- Consumes: Task 1's corrected Android source.
- Produces: installable Debug APK with application ID `com.mediareview.app`, label `家庭媒体管家`, version code `4`, version name `1.0.0-rc2`.

- [x] **Step 1: Update version metadata**

```kotlin
versionCode = 4
versionName = "1.0.0-rc2"
```

- [x] **Step 2: Build the Debug APK**

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` and `source/android/app/build/outputs/apk/debug/app-debug.apk` exists.

- [x] **Step 3: Copy the verified artifact**

Copy `app-debug.apk` to `dist/家庭媒体管家-1.0.0-rc2.apk` without replacing unrelated artifacts.

- [x] **Step 4: Verify artifact identity and integrity**

Use Android build tools `aapt2 dump badging` and `apksigner verify --verbose --print-certs`, then calculate SHA-256.

Expected:

```text
package: name='com.mediareview.app' versionCode='4' versionName='1.0.0-rc2'
application-label:'家庭媒体管家'
Verified using v2 scheme (APK Signature Scheme v2): true
```

- [x] **Step 5: Record the handoff**

Report the APK absolute path, size, SHA-256, test count, build result, and the remaining physical-phone verification boundary.
