#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

test "$(cat app/src/main/resources/META-INF/xposed/java_init.list)" = \
  "io.github.jahn2007.fourma.DiagnosticModule"
test "$(cat app/src/main/resources/META-INF/xposed/scope.list)" = "com.tencent.mm"

grep -qx 'minApiVersion=102' app/src/main/resources/META-INF/xposed/module.prop
grep -qx 'targetApiVersion=102' app/src/main/resources/META-INF/xposed/module.prop
grep -qx 'autoHotReload=true' app/src/main/resources/META-INF/xposed/module.prop
grep -q "compileOnly 'io.github.libxposed:api:102.0.0'" app/build.gradle
grep -q "implementation 'io.github.libxposed:service:102.0.0'" app/build.gradle
grep -q 'de.robv.android.xposed.category.MODULE_SETTINGS' app/src/main/AndroidManifest.xml
grep -q 'android:name=".LauncherAlias"' app/src/main/AndroidManifest.xml
grep -q 'android:name=".ReportProvider"' app/src/main/AndroidManifest.xml
grep -q 'boolean onHotReloading' app/src/main/java/io/github/jahn2007/fourma/DiagnosticModule.java
grep -q 'void onHotReloaded' app/src/main/java/io/github/jahn2007/fourma/DiagnosticModule.java

if grep -n 'prefs\.edit' app/src/main/java/io/github/jahn2007/fourma/DiagnosticModule.java; then
  echo 'Hooked processes must not write libxposed RemotePreferences.' >&2
  exit 1
fi

if grep -R -nE 'POST_NOTIFICATIONS|NotificationManager|NotificationChannel' app/src/main; then
  echo 'Notifications are forbidden in 4MA.' >&2
  exit 1
fi

if grep -R -nE 'android\.webkit\.WebViewClient|okhttp3|java\.net\.|setRequestProperty|shouldInterceptRequest' \
  app/src/main/java; then
  echo 'Potential network or credential capture code found.' >&2
  exit 1
fi

echo '4MA static checks passed.'
