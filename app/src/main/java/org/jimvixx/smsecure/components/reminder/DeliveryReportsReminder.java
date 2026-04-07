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
import android.view.View.OnClickListener;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.util.SMSecurePreferences;

public class DeliveryReportsReminder extends Reminder {

  public DeliveryReportsReminder(final Context context) {
    super(context.getString(R.string.reminder_header_delivery_reports_title),
            context.getString(R.string.reminder_header_delivery_reports_text),
            context.getString(R.string.Enable));

    final OnClickListener okListener = v -> {
      SMSecurePreferences.setSmsDeliveryReportsEnabled(context);
      SMSecurePreferences.setPromptedDeliveryReportsReminder(context);
    };
    final OnClickListener dismissListener = v -> SMSecurePreferences.setPromptedDeliveryReportsReminder(context);
    setOkListener(okListener);
    setDismissListener(dismissListener);
  }

  public static boolean isEligible(Context context) {
    return !SMSecurePreferences.isSmsDeliveryReportsEnabled(context) && !SMSecurePreferences.hasPromptedDeliveryReportsReminder(context);
  }
}
