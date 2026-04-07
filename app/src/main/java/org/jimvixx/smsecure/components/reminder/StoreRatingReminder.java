/*
 * Copyright (C) 2011 Whisper Systems
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

package org.jimvixx.smsecure.components.reminder;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import org.jimvixx.smsecure.logging.Log;
import android.view.View.OnClickListener;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.util.SMSecurePreferences;

import java.util.concurrent.TimeUnit;

public class StoreRatingReminder extends Reminder {

  private static final String TAG = StoreRatingReminder.class.getSimpleName();

  private static final int DAYS_SINCE_INSTALL_THRESHOLD = 7;

  public StoreRatingReminder(final Context context) {
    super(context.getString(R.string.reminder_header_rate_title),
          context.getString(R.string.reminder_header_rate_text),
          context.getString(R.string.reminder_header_rate_button));

    final OnClickListener okListener = v -> {
      SMSecurePreferences.setRatingEnabled(context, false);
      Uri uri = Uri.parse("market://details?id=" + context.getPackageName());
      context.startActivity(new Intent(Intent.ACTION_VIEW, uri));
    };
    final OnClickListener dismissListener = v -> SMSecurePreferences.setRatingEnabled(context, false);
    setOkListener(okListener);
    setDismissListener(dismissListener);
  }

  public static boolean isEligible(Context context) {

    if (!SMSecurePreferences.isRatingEnabled(context))
      return false;

    // App needs to be installed via Play/Amazon store to show the rating dialog
    String installer = context.getPackageManager().getInstallerPackageName(context.getPackageName());
    if (installer == null || !(installer.equals("com.android.vending") || installer.equals("com.amazon.venezia"))){
      SMSecurePreferences.setRatingEnabled(context, false);
      return false;
    }

    long daysSinceInstall = getDaysSinceInstalled(context);
    long laterTimestamp   = SMSecurePreferences.getRatingLaterTimestamp(context);

    return daysSinceInstall >= DAYS_SINCE_INSTALL_THRESHOLD &&
            System.currentTimeMillis() >= laterTimestamp;
  }

  private static long getDaysSinceInstalled(Context context) {
    try {
      long installTimestamp = context.getPackageManager()
                                     .getPackageInfo(context.getPackageName(), 0)
                                     .firstInstallTime;

      return TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - installTimestamp);
    } catch (PackageManager.NameNotFoundException e) {
      Log.w(TAG, e);
      return 0;
    }
  }
}
