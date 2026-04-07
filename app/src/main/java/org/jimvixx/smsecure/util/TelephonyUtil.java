/*
 * Copyright (C) 2013 Open Whisper Systems
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

package org.jimvixx.smsecure.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.jimvixx.smsecure.logging.Log;

import java.util.Collections;
import java.util.List;

public final class TelephonyUtil {

  private static final String TAG = TelephonyUtil.class.getSimpleName();

  private TelephonyUtil() {
  }

  public static @Nullable TelephonyManager getManager(@NonNull Context context) {
    return (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
  }

  public static @Nullable SubscriptionManager getSubscriptionManager(@NonNull Context context) {
    return SubscriptionManager.from(context);
  }

  public static boolean hasReadPhoneStatePermission(@NonNull Context context) {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            == PackageManager.PERMISSION_GRANTED;
  }

  public static boolean hasReadPhoneNumbersPermission(@NonNull Context context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS)
              == PackageManager.PERMISSION_GRANTED;
    }

    return false;
  }

  /**
   * Returns true if the app can reasonably attempt to read the line number.
   * On modern Android devices, either READ_PHONE_STATE or READ_PHONE_NUMBERS
   * may be involved depending on API level and OEM behavior.
   */
  public static boolean hasReadNumberPermission(@NonNull Context context) {
    return hasReadPhoneStatePermission(context) || hasReadPhoneNumbersPermission(context);
  }

  /**
   * Returns true if the app can query active subscriptions.
   */
  public static boolean canReadSubscriptions(@NonNull Context context) {
    return hasReadPhoneStatePermission(context);
  }

  /**
   * Returns the current active subscription list.
   * <p>
   * This method never throws and returns an empty list when:
   * - permission is missing
   * - telephony is not ready yet
   * - the framework temporarily returns null
   */
  public static @NonNull List<SubscriptionInfo> getActiveSubscriptionInfoListSafe(@NonNull Context context) {
    if (!canReadSubscriptions(context)) {
      Log.w(TAG, "getActiveSubscriptionInfoListSafe(): READ_PHONE_STATE not granted");
      return Collections.emptyList();
    }

    SubscriptionManager subscriptionManager = getSubscriptionManager(context);

    if (subscriptionManager == null) {
      Log.w(TAG, "getActiveSubscriptionInfoListSafe(): SubscriptionManager is null");
      return Collections.emptyList();
    }

    try {
      List<SubscriptionInfo> list = subscriptionManager.getActiveSubscriptionInfoList();

      if (list == null) {
        Log.w(TAG, "getActiveSubscriptionInfoListSafe(): framework returned null");
        return Collections.emptyList();
      }

      return list;
    } catch (SecurityException e) {
      Log.w(TAG, "getActiveSubscriptionInfoListSafe(): SecurityException", e);
      return Collections.emptyList();
    } catch (RuntimeException e) {
      Log.w(TAG, "getActiveSubscriptionInfoListSafe(): RuntimeException", e);
      return Collections.emptyList();
    }
  }

  public static boolean isMyPhoneNumber(@NonNull Context context, @Nullable String number) {
    return number != null &&
            PhoneNumberUtils.compare(context, getPhoneNumber(context), number);
  }

  @SuppressWarnings("HardwareIds")
  public static @Nullable String getPhoneNumber(@Nullable Context context) {
    if (context == null) {
      return null;
    }

    if (!hasReadNumberPermission(context)) {
      Log.w(TAG, "getPhoneNumber(): missing permission");
      return null;
    }

    TelephonyManager telephonyManager = getManager(context);

    if (telephonyManager == null) {
      Log.w(TAG, "getPhoneNumber(): TelephonyManager is null");
      return null;
    }

    try {
      return telephonyManager.getLine1Number();
    } catch (SecurityException e) {
      Log.w(TAG, "getPhoneNumber(): SecurityException", e);
      return null;
    } catch (RuntimeException e) {
      Log.w(TAG, "getPhoneNumber(): RuntimeException", e);
      return null;
    }
  }

}