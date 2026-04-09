/*
 * Copyright (C) 2016 Open Whisper Systems
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

package org.jimvixx.smsecure.util.dualsim;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.util.SMSecurePreferences;

public class SubscriptionInfoCompat {

  private final Context context;
  private final int deviceSubscriptionId;
  private final int mcc;
  private final int mnc;
  private final @Nullable CharSequence displayName;
  private final @Nullable String number;
  private final @Nullable String iccId;
  private final int iccSlot;
  private final boolean duplicateDisplayName;
  private int subscriptionId;

  public SubscriptionInfoCompat(@NonNull Context context,
                                int deviceSubscriptionId,
                                @Nullable CharSequence displayName,
                                @Nullable String number,
                                @Nullable String iccId,
                                int iccSlot,
                                int mcc,
                                int mnc,
                                boolean duplicateDisplayName) {
    this.context = context.getApplicationContext();
    this.deviceSubscriptionId = deviceSubscriptionId;
    this.displayName = displayName;
    this.number = number;
    this.iccId = iccId;
    this.iccSlot = iccSlot;
    this.mcc = mcc;
    this.mnc = mnc;
    this.duplicateDisplayName = duplicateDisplayName;

    this.subscriptionId = findAppId(this.context, number, iccId);
  }

  private static int findAppId(@NonNull Context context,
                               @Nullable String number,
                               @Nullable String iccId) {
    int appSubscriptionId = findAppIdFromNumber(context, number);
    if (appSubscriptionId == -1) appSubscriptionId = findAppIdFromIccId(context, iccId);
    if (appSubscriptionId == -1) appSubscriptionId = bumpAppSubscriptionId(context);

    saveInfo(context, appSubscriptionId, number, iccId);
    return appSubscriptionId;
  }

  private static int findAppIdFromNumber(@NonNull Context context, @Nullable String number) {
    if (TextUtils.isEmpty(number)) return -1;

    int lastAppSubscriptionId = SMSecurePreferences.getLastAppSubscriptionId(context);
    for (int i = 0; i <= lastAppSubscriptionId; i++) {
      String eligibleNumber = SMSecurePreferences.getNumberForSubscriptionId(context, i);
      if (number.equals(eligibleNumber)) return i;
    }

    return -1;
  }

  private static int findAppIdFromIccId(@NonNull Context context, @Nullable String iccId) {
    if (TextUtils.isEmpty(iccId)) return -1;

    int lastAppSubscriptionId = SMSecurePreferences.getLastAppSubscriptionId(context);
    for (int i = 0; i <= lastAppSubscriptionId; i++) {
      String eligibleIccId = SMSecurePreferences.getIccIdForSubscriptionId(context, i);
      if (iccId.equals(eligibleIccId)) return i;
    }

    return -1;
  }

  private static int bumpAppSubscriptionId(@NonNull Context context) {
    int lastAppSubscriptionId = SMSecurePreferences.getLastAppSubscriptionId(context);
    int next = lastAppSubscriptionId + 1;
    SMSecurePreferences.setLastAppSubscriptionId(context, next);
    return next;
  }

  private static void saveInfo(@NonNull Context context,
                               int appSubscriptionId,
                               @Nullable String number,
                               @Nullable String iccId) {
    if (!TextUtils.isEmpty(number)) {
      SMSecurePreferences.setNumberForSubscriptionId(context, appSubscriptionId, number);
    }

    if (!TextUtils.isEmpty(iccId)) {
      SMSecurePreferences.setIccIdForSubscriptionId(context, appSubscriptionId, iccId);
    }
  }

  public @NonNull CharSequence getDisplayName() {
    if (!TextUtils.isEmpty(displayName)) {
      return getEligibleDisplayName();
    }
    return context.getString(R.string.SubscriptionInfoCompat_slot, iccSlot);
  }

  private @NonNull String getEligibleDisplayName() {
    final String dn = displayName != null ? displayName.toString() : "";

    if (duplicateDisplayName) {
      final String num = getNumber();
      if (!TextUtils.isEmpty(num)) {
        return num;
      }
      return context.getString(R.string.SubscriptionInfoCompat_display_name, dn, iccSlot);
    }

    return dn;
  }

  public int getSubscriptionId() {
    return subscriptionId;
  }

  public void setSubscriptionId(int subscriptionId) {
    SMSecurePreferences.setAppSubscriptionId(context, deviceSubscriptionId, subscriptionId);
    this.subscriptionId = subscriptionId;
  }

  public int getIccSlot() {
    return iccSlot;
  }

  public int getDeviceSubscriptionId() {
    return deviceSubscriptionId;
  }

  public @NonNull String getNumber() {
    return number != null ? number : "";
  }

  public @Nullable String getIccId() {
    return iccId;
  }

  public int getMnc() {
    return mnc;
  }

  public int getMcc() {
    return mcc;
  }
}
