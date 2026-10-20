/*
 * Copyright (C) 2026 Jimvixx
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
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsManager;

import androidx.core.content.ContextCompat;

import org.jimvixx.smsecure.database.model.SmsMessageRecord;
import org.jimvixx.smsecure.util.Util;
import org.jimvixx.smsecure.util.dualsim.SubscriptionInfoCompat;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Numeric routing data and allowlisted callback fields only; never message or SIM identities. */
public final class SmsSendDiagnostics {
  private static final String TAG = SmsSendDiagnostics.class.getSimpleName();

  public enum SelectionSource {
    MANUAL, RECIPIENT_PREFERENCE, SYSTEM_DEFAULT, FALLBACK, DISABLED, UNKNOWN
  }

  private SmsSendDiagnostics() {}

  public static void logQueued(long messageId, int uiAppSub, SelectionSource source) {
    try {
      Log.i(TAG, "SMS queued: message=" + messageId + " uiAppSub=" + uiAppSub
              + " selectionSource=" + (source == null ? SelectionSource.UNKNOWN : source));
    } catch (RuntimeException failure) {
      logUnavailable(failure);
    }
  }

  public static void logSend(Context context, SmsMessageRecord message,
                             int resolvedDeviceSub, SmsManager smsManager,
                             int parts, boolean deliveryReports) {
    try {
      // Resolution already refreshed this cache. Reading it must not refresh or write SIM mappings.
      List<SubscriptionInfoCompat> subscriptions = SubscriptionManagerCompat.from(context)
              .getLastKnownActiveSubscriptionInfoList();
      Log.i(TAG, "SMS send: message=" + message.getId()
              + " appSub=" + message.getSubscriptionId()
              + " deviceSub=" + resolvedDeviceSub
              + " managerDeviceSub=" + integerValue(smsManager::getSubscriptionId)
              + " defaultDeviceSub=" + integerValue(SmsManager::getDefaultSmsSubscriptionId)
              + " defaultSmsRole=" + booleanValue(() -> Util.getDefaultSmsAppStatus(context))
              + " defaultSmsPackage=" + defaultSmsPackageState(context)
              + " sendPermission=" + booleanValue(() -> ContextCompat.checkSelfPermission(
                      context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED)
              + " deliveryReports=" + deliveryReports
              + " secure=" + message.isSecure()
              + " keyExchange=" + message.isKeyExchange()
              + " endSession=" + message.isEndSession()
              + " parts=" + parts
              + " mappingSource=lastKnownSnapshot "
              + describeSubscriptions(subscriptions, message.getSubscriptionId()));
    } catch (RuntimeException failure) {
      logUnavailable(failure);
    }
  }

  public static void logRouteBlocked(Context context, SmsMessageRecord message) {
    try {
      List<SubscriptionInfoCompat> subscriptions = SubscriptionManagerCompat.from(context)
              .getLastKnownActiveSubscriptionInfoList();
      Log.w(TAG, "SMS route blocked: message=" + message.getId()
              + " appSub=" + message.getSubscriptionId()
              + " reason=unresolved_subscription mappingSource=lastKnownSnapshot "
              + describeSubscriptions(subscriptions, message.getSubscriptionId()));
    } catch (RuntimeException failure) {
      logUnavailable(failure);
    }
  }

  public static void logSentResult(Intent intent, long messageId, int resultCode,
                                   int partIndex, int partsTotal) {
    try {
      String diagnostic = "SMS sent result: message=" + messageId
              + " result=" + resultCode + " part=" + partIndex + "/" + partsTotal
              + " " + callbackFields(intent);
      if (resultCode == Activity.RESULT_OK) {
        Log.i(TAG, diagnostic);
      } else {
        Log.e(TAG, diagnostic);
      }
    } catch (RuntimeException failure) {
      logUnavailable(failure);
    }
  }

  static String describeSubscriptions(List<SubscriptionInfoCompat> subscriptions, int appSub) {
    Set<Integer> appIds = new HashSet<>();
    boolean duplicateAppSub = false;
    int appSubMatches = 0;
    int mappingConflicts = 0;
    StringBuilder mappings = new StringBuilder();
    for (SubscriptionInfoCompat subscription : subscriptions) {
      int id = subscription.getSubscriptionId();
      if (subscription.hasMappingConflict()) mappingConflicts++;
      duplicateAppSub |= !appIds.add(id);
      if (id == appSub) appSubMatches++;
      if (mappings.length() != 0) mappings.append(',');
      mappings.append("{appSub=").append(id)
              .append(" deviceSub=").append(subscription.getDeviceSubscriptionId())
              .append(" slot=").append(subscription.getIccSlot()).append('}');
    }
    return "activeCount=" + subscriptions.size()
            + " duplicateAppSub=" + duplicateAppSub
            + " appSubMatches=" + appSubMatches
            + " mappingConflicts=" + mappingConflicts
            + " mappings=[" + mappings + "]";
  }

  static String defaultSmsPackageState(Context context) {
    try {
      String packageName = Telephony.Sms.getDefaultSmsPackage(context);
      return packageName == null ? "unavailable"
              : (packageName.equals(context.getPackageName()) ? "matches" : "mismatch");
    } catch (RuntimeException failure) {
      return "unavailable";
    }
  }

  static String callbackFields(Intent intent) {
    Bundle extras = intent.getExtras();
    return "errorCode=" + integerExtra(extras, "errorCode")
            + " noDefault=" + booleanExtra(extras, "noDefault")
            + " format=" + formatExtra(extras, "format")
            // This reports Android's callback IMS-service path, not IMS registration or capability.
            + " ims=" + booleanExtra(extras, "ims");
  }

  private static String integerExtra(Bundle extras, String key) {
    if (extras == null || !extras.containsKey(key)) return "absent";
    int first = extras.getInt(key, 0);
    int second = extras.getInt(key, 1);
    return first == second ? Integer.toString(first) : "malformed";
  }

  private static String booleanExtra(Bundle extras, String key) {
    if (extras == null || !extras.containsKey(key)) return "absent";
    boolean first = extras.getBoolean(key, false);
    boolean second = extras.getBoolean(key, true);
    return first == second ? Boolean.toString(first) : "malformed";
  }

  private static String formatExtra(Bundle extras, String key) {
    if (extras == null || !extras.containsKey(key)) return "absent";
    String value = extras.getString(key);
    return "3gpp".equals(value) ? "3gpp" : ("3gpp2".equals(value) ? "3gpp2" : "unknown");
  }

  private static String integerValue(IntSupplier supplier) {
    try {
      return Integer.toString(supplier.getAsInt());
    } catch (RuntimeException failure) {
      return "unavailable";
    }
  }

  private static String booleanValue(Supplier<Boolean> supplier) {
    try {
      Boolean value = supplier.get();
      return value == null ? "unavailable" : Boolean.toString(value);
    } catch (RuntimeException failure) {
      return "unavailable";
    }
  }

  private static void logUnavailable(RuntimeException failure) {
    try {
      // Exception messages can contain framework-supplied private values.
      Log.w(TAG, "SMS diagnostics unavailable: " + failure.getClass().getSimpleName());
    } catch (RuntimeException ignored) {
      // A failed diagnostic sink must not alter sending or callback processing.
    }
  }
}
