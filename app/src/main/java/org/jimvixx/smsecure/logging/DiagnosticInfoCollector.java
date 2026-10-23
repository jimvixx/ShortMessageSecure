/*
 * Copyright (C) 2025 Jimvixx
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jimvixx.smsecure.logging;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.format.DateFormat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.jimvixx.smsecure.BuildConfig;
import org.jimvixx.smsecure.crypto.MasterSecretUtil;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.Util;

import java.text.SimpleDateFormat;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Collects environment and app diagnostic information for support reports.
 */
public final class DiagnosticInfoCollector {

  private DiagnosticInfoCollector() {
  }

  @NonNull
  public static String collect(@NonNull Context context) {
    StringBuilder sb = new StringBuilder(4096);

    sb.append("==== SMSecure Diagnostic Report ====\n");
    sb.append("Generated: ").append(formatNow()).append("\n\n");

    appendAppSection(context, sb);
    appendDeviceSection(sb);
    appendEnvironmentSection(context, sb);
    appendPermissionsSection(context, sb);
    appendSettingsSection(context, sb);

    return sb.toString();
  }

  private static void appendAppSection(@NonNull Context context, @NonNull StringBuilder sb) {
    sb.append("== App ==\n");

    sb.append("Application ID: ").append(BuildConfig.APPLICATION_ID).append('\n');
    sb.append("Version name: ").append(BuildConfig.VERSION_NAME).append('\n');
    sb.append("Version code: ").append(BuildConfig.VERSION_CODE).append('\n');
    sb.append("Build type: ").append(BuildConfig.BUILD_TYPE).append('\n');
    sb.append("Source commit (build-reported): ").append(BuildConfig.SOURCE_COMMIT).append('\n');
    sb.append("APK signer SHA-256 (client-reported): ").append(getSignerFingerprints(context)).append('\n');
    sb.append("Debug build: ").append(BuildConfig.DEBUG).append('\n');
    sb.append("Min SDK: ").append(getMinSdk(context)).append('\n');
    sb.append("Target SDK: ").append(getTargetSdk(context)).append('\n');

    try {
      PackageManager pm = context.getPackageManager();
      PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);

      sb.append("First install: ").append(formatTime(pi.firstInstallTime)).append('\n');
      sb.append("Last update: ").append(formatTime(pi.lastUpdateTime)).append('\n');
    } catch (Throwable t) {
      sb.append("Package info: <unavailable>\n");
    }

    sb.append('\n');
  }

  /** Current APK signers, not historical certificates from a signing-key rotation. */
  @NonNull
  public static String getSignerFingerprints(@NonNull Context context) {
    try {
      Signature[] signers = getSigners(context);

      if (signers == null || signers.length == 0) return "<unavailable>";

      String[] fingerprints = new String[signers.length];
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (int i = 0; i < signers.length; i++) {
        byte[] hash = digest.digest(signers[i].toByteArray());
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
          hex.append(Character.forDigit((value & 0xff) >>> 4, 16));
          hex.append(Character.forDigit(value & 0x0f, 16));
        }
        fingerprints[i] = hex.toString();
      }
      Arrays.sort(fingerprints);
      return android.text.TextUtils.join(", ", fingerprints);
    } catch (Exception e) {
      // Diagnostics must remain available even if package metadata cannot be read.
      return "<unavailable>";
    }
  }

  @Nullable
  @SuppressWarnings("deprecation")
  private static Signature[] getSigners(@NonNull Context context)
          throws PackageManager.NameNotFoundException {
    PackageManager pm = context.getPackageManager();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      PackageInfo info = pm.getPackageInfo(context.getPackageName(),
              PackageManager.GET_SIGNING_CERTIFICATES);
      return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
    }

    return pm.getPackageInfo(context.getPackageName(),
            PackageManager.GET_SIGNATURES).signatures;
  }

  private static void appendDeviceSection(@NonNull StringBuilder sb) {
    sb.append("== Device ==\n");
    sb.append("Manufacturer: ").append(nullSafe(Build.MANUFACTURER)).append('\n');
    sb.append("Brand: ").append(nullSafe(Build.BRAND)).append('\n');
    sb.append("Model: ").append(nullSafe(Build.MODEL)).append('\n');
    sb.append("Device: ").append(nullSafe(Build.DEVICE)).append('\n');
    sb.append("Product: ").append(nullSafe(Build.PRODUCT)).append('\n');
    sb.append("Hardware: ").append(nullSafe(Build.HARDWARE)).append('\n');
    sb.append("Android release: ").append(nullSafe(Build.VERSION.RELEASE)).append('\n');
    sb.append("SDK_INT: ").append(Build.VERSION.SDK_INT).append('\n');
    sb.append("Build ID: ").append(nullSafe(Build.ID)).append('\n');
    sb.append("Build display: ").append(nullSafe(Build.DISPLAY)).append('\n');
    sb.append("Security patch: ").append(getSecurityPatch()).append('\n');
    sb.append("ABIs: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
    sb.append('\n');
  }

  private static void appendEnvironmentSection(@NonNull Context context, @NonNull StringBuilder sb) {
    sb.append("== Environment ==\n");
    sb.append("Locale: ").append(Locale.getDefault().toLanguageTag()).append('\n');
    sb.append("Timezone: ").append(TimeZone.getDefault().getID()).append('\n');
    sb.append("24-hour format: ").append(DateFormat.is24HourFormat(context)).append('\n');
    Boolean defaultSmsApp = Util.getDefaultSmsAppStatus(context);
    sb.append("Default SMS app: ")
            .append(defaultSmsApp == null ? "<unavailable>" : defaultSmsApp.toString()).append('\n');
    sb.append("Battery optimization ignored: ").append(isIgnoringBatteryOptimizations(context)).append('\n');
    sb.append("Airplane mode: ").append(isAirplaneModeEnabled(context)).append('\n');
    sb.append("Low RAM device: ").append(isLowRamDevice(context)).append('\n');
    sb.append("Telephony present: ").append(hasTelephony(context)).append('\n');

    String operator = getNetworkOperatorName(context);
    if (!operator.isEmpty()) {
      sb.append("Network operator: ").append(operator).append('\n');
    }

    sb.append('\n');
  }

  private static void appendPermissionsSection(@NonNull Context context, @NonNull StringBuilder sb) {
    sb.append("== Permissions ==\n");
    appendPermission(context, sb, Manifest.permission.READ_SMS);
    appendPermission(context, sb, Manifest.permission.RECEIVE_SMS);
    appendPermission(context, sb, Manifest.permission.SEND_SMS);
    appendPermission(context, sb, Manifest.permission.READ_CONTACTS);

    if (Build.VERSION.SDK_INT >= 33) {
      appendPermission(context, sb, Manifest.permission.POST_NOTIFICATIONS);
    } else {
      sb.append("android.permission.POST_NOTIFICATIONS: not_applicable\n");
    }

    sb.append('\n');
  }

  private static void appendSettingsSection(@NonNull Context context, @NonNull StringBuilder sb) {
    sb.append("== SMSecure settings ==\n");

    try {
      sb.append("Log level: ").append(Log.getLevel().name()).append('\n');
      sb.append("System log enabled: ").append(SMSecurePreferences.isSystemLogEnabled(context)).append('\n');
    } catch (Throwable t) {
      sb.append("Log config: <unavailable>\n");
    }

    try {
      sb.append("Passphrase initialized: ").append(MasterSecretUtil.isPassphraseInitialized(context)).append('\n');
    } catch (Throwable t) {
      sb.append("Passphrase initialized: <unavailable>\n");
    }

    try {
      sb.append("Language: ").append(nullSafe(SMSecurePreferences.getLanguage(context))).append('\n');
    } catch (Throwable t) {
      sb.append("Language: <unavailable>\n");
    }

    try {
      sb.append("Theme: ").append(nullSafe(SMSecurePreferences.getTheme(context))).append('\n');
    } catch (Throwable t) {
      sb.append("Theme: <unavailable>\n");
    }

    sb.append('\n');
  }

  private static void appendPermission(@NonNull Context context,
                                       @NonNull StringBuilder sb,
                                       @NonNull String permission) {
    boolean granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    sb.append(permission).append(": ").append(granted ? "granted" : "denied").append('\n');
  }

  private static boolean isIgnoringBatteryOptimizations(@NonNull Context context) {

    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
  }

  private static boolean isAirplaneModeEnabled(@NonNull Context context) {
    try {
      return Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    } catch (Throwable t) {
      return false;
    }
  }

  private static boolean isLowRamDevice(@NonNull Context context) {
    try {
      android.app.ActivityManager am =
              (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
      return am != null && am.isLowRamDevice();
    } catch (Throwable t) {
      return false;
    }
  }

  private static boolean hasTelephony(@NonNull Context context) {
    try {
      PackageManager pm = context.getPackageManager();
      return pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    } catch (Throwable t) {
      return false;
    }
  }

  @NonNull
  private static String getNetworkOperatorName(@NonNull Context context) {
    try {
      TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
      if (tm == null) return "";
      String name = tm.getNetworkOperatorName();
      return name == null ? "" : name;
    } catch (Throwable t) {
      return "";
    }
  }

  @NonNull
  private static String getSecurityPatch() {
    try {
      return Build.VERSION.SECURITY_PATCH;
    } catch (Throwable ignore) {
    }
    return "<unknown>";
  }

  private static int getTargetSdk(@NonNull Context context) {
    try {
      ApplicationInfo ai = context.getApplicationInfo();
      return ai.targetSdkVersion;
    } catch (Throwable t) {
      return -1;
    }
  }

  private static int getMinSdk(@NonNull Context context) {
    try {
      ApplicationInfo ai = context.getApplicationInfo();
      return ai.minSdkVersion;
    } catch (Throwable t) {
      return -1;
    }
  }

  @NonNull
  private static String nullSafe(@Nullable String value) {
    return value == null ? "<null>" : value;
  }

  @NonNull
  private static String formatNow() {
    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date());
  }

  @NonNull
  private static String formatTime(long millis) {
    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date(millis));
  }
}
