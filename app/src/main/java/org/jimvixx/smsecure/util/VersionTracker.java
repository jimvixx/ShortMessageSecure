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

package org.jimvixx.smsecure.util;

import android.content.Context;
import android.content.pm.PackageInfo;

import androidx.core.content.pm.PackageInfoCompat;

import org.jimvixx.smsecure.logging.Log;

import java.io.IOException;

public class VersionTracker {
  private static final String TAG = VersionTracker.class.getSimpleName();

  public static int getLastSeenVersion(Context context) {
    return SMSecurePreferences.getLastVersionCode(context);
  }

  public static void updateLastSeenVersion(Context context) {
    int currentVersionCode = Util.getCurrentApkReleaseVersion(context);

    updateLastSeenVersion(currentVersionCode, new VersionCodeStore() {
      @Override
      public void persist(int versionCode) throws IOException {
        SMSecurePreferences.setLastVersionCode(context, versionCode);
      }

      @Override
      public int getLastSeenVersion() {
        return SMSecurePreferences.getLastVersionCode(context);
      }
    });
  }

  static void updateLastSeenVersion(int currentVersionCode, VersionCodeStore store) {
    try {
      store.persist(currentVersionCode);
      return;
    } catch (IOException ioe) {
      if (isLastSeenVersionPersisted(store, currentVersionCode)) {
        Log.w(TAG, "Version code persisted despite a failed commit", ioe);
        return;
      }

      Log.w(TAG, "Failed to persist version code; retrying once", ioe);
    }

    try {
      store.persist(currentVersionCode);
    } catch (IOException ioe) {
      if (isLastSeenVersionPersisted(store, currentVersionCode)) {
        Log.w(TAG, "Version code persisted despite a failed retry", ioe);
      } else {
        Log.e(TAG, "Could not persist version code; will retry on a future launch", ioe);
      }
    }
  }

  private static boolean isLastSeenVersionPersisted(VersionCodeStore store, int versionCode) {
    return store.getLastSeenVersion() >= versionCode;
  }

  interface VersionCodeStore {
    void persist(int versionCode) throws IOException;

    int getLastSeenVersion();
  }

  public static boolean isDbUpdated(Context context) {
    try {
      PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      if (packageInfo == null) return true;

      return SMSecurePreferences.getLastVersionCode(context)
              >= PackageInfoCompat.getLongVersionCode(packageInfo);
    } catch (Exception e) {
      Log.w(TAG, e);
      return true;
    }
  }
}
