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

package org.jimvixx.smsecure.migration.silence;

import android.Manifest;
import android.content.SharedPreferences;
import org.jimvixx.smsecure.crypto.IdentityKeyUtil;
import org.jimvixx.smsecure.crypto.MasterSecretUtil;
import android.content.Context;
import android.content.pm.PackageManager;
import android.preference.PreferenceManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads existing mappings without requesting permission, registering SIMs, or writing preferences. */
public final class SilenceTargetSubscriptionReader {
  private SilenceTargetSubscriptionReader() {}

  public static SilenceTargetSubscriptions read(Context context) {
    if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
      return SilenceTargetSubscriptions.unavailable(SilenceTargetSubscriptions.Status.PERMISSION_REQUIRED);
    }
    try {
      SubscriptionManager manager = context.getSystemService(SubscriptionManager.class);
      if (manager == null) return SilenceTargetSubscriptions.unavailable(SilenceTargetSubscriptions.Status.UNAVAILABLE);
      List<SubscriptionInfo> active = manager.getActiveSubscriptionInfoList();
      if (active == null) return SilenceTargetSubscriptions.unavailable(SilenceTargetSubscriptions.Status.UNAVAILABLE);
      Set<Integer> deviceIds = new HashSet<>();
      for (SubscriptionInfo info : active) {
        if (info == null || info.getSubscriptionId() < 0) {
          return SilenceTargetSubscriptions.unavailable(SilenceTargetSubscriptions.Status.UNAVAILABLE);
        }
        deviceIds.add(info.getSubscriptionId());
      }
      SilenceTargetSubscriptions result = SilenceTargetSubscriptions.from(deviceIds,
          PreferenceManager.getDefaultSharedPreferences(context).getAll());
      SharedPreferences keys = context.getSharedPreferences(MasterSecretUtil.PREFERENCES_NAME, Context.MODE_PRIVATE);
      Set<Integer> occupied = new HashSet<>();
      for (int appId : result.getCandidates().values()) {
        // Presence alone blocks replacement; never read, decrypt, generate, or repair target keys.
        if (keys.contains(IdentityKeyUtil.getIdentityPublicKeyDjbPref(appId))
            || keys.contains(IdentityKeyUtil.getIdentityPrivateKeyDjbPref(appId))) occupied.add(appId);
      }
      Set<Integer> candidateIds = new HashSet<>(result.getCandidates().values());
      Set<Integer> files = SilenceTargetCryptoFiles.occupied(context.getFilesDir(), candidateIds);
      Set<Integer> database = SilenceTargetDatabaseConflicts.occupiedCurrent(context.getDatabasePath("messages.db"), candidateIds);
      return result.excluding(occupied, SilenceTargetSubscriptions.Conflict.IDENTITY_KEYS)
          .excluding(files, SilenceTargetSubscriptions.Conflict.CRYPTO_FILES)
          .excluding(database, SilenceTargetSubscriptions.Conflict.DATABASE_REFERENCES);
    } catch (SecurityException e) {
      return SilenceTargetSubscriptions.unavailable(SilenceTargetSubscriptions.Status.PERMISSION_REQUIRED);
    } catch (java.io.IOException | IllegalStateException e) {
      return SilenceTargetSubscriptions.unavailable(SilenceTargetSubscriptions.Status.UNAVAILABLE);
    }
  }
}
