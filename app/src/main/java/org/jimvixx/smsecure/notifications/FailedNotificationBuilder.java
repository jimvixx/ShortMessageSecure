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

package org.jimvixx.smsecure.notifications;

import static org.jimvixx.smsecure.util.ResUtil.getDrawableRes;
import static org.jimvixx.smsecure.util.ThemeUtil.resolveThemeColor;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.preferences.widgets.NotificationPrivacyPreference;
import org.jimvixx.smsecure.util.NotificationIconUtil;

public class FailedNotificationBuilder extends AbstractNotificationBuilder {

  public FailedNotificationBuilder(Context context,
                                   NotificationPrivacyPreference privacy,
                                   Intent intent,
                                   String channelId) {
    super(context, privacy, channelId);

    setSmallIcon(R.drawable.ic_smsecure);
    setLargeIcon(NotificationIconUtil.getLargeIcon(
            context,
            getDrawableRes(context, R.attr.dialog_alert_icon),
            48,
            resolveThemeColor(context, R.attr.appColorCommonAlert)
    ));
    setContentTitle(context.getString(R.string.MessageNotifier_message_delivery_failed));
    setContentText(context.getString(R.string.MessageNotifier_failed_to_deliver_message));
    setTicker(context.getString(R.string.MessageNotifier_error_delivering_message));
    setContentIntent(PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE));
    setAutoCancel(true);

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      setAudibleAlarms(NotificationChannels.getPersistedGlobalNotificationRingtone(context));
    }
  }
}