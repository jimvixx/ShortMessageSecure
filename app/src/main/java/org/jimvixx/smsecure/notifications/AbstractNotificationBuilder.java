/*
 * Copyright (C) 2015 Open Whisper Systems
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

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.preferences.widgets.NotificationPrivacyPreference;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.Util;

public abstract class AbstractNotificationBuilder extends NotificationCompat.Builder {

  private static final int MAX_DISPLAY_LENGTH = 500;
  private static final String TAG = AbstractNotificationBuilder.class.getSimpleName();

  protected final Context context;
  protected final NotificationPrivacyPreference privacy;

  public AbstractNotificationBuilder(@NonNull Context context,
                                     @NonNull NotificationPrivacyPreference privacy,
                                     @NonNull String channelId) {
    super(context, channelId);

    this.context = context;
    this.privacy = privacy;

    Log.d(TAG, "Builder created with channelId = " + channelId);
  }

  public AbstractNotificationBuilder(@NonNull Context context,
                                     @NonNull NotificationPrivacyPreference privacy) {
    super(context, NotificationChannels.OTHER);
    this.context = context;
    this.privacy = privacy;
  }

  protected @NonNull CharSequence getStyledMessage(@NonNull Recipient recipient,
                                                   @Nullable CharSequence message) {
    SpannableStringBuilder builder = new SpannableStringBuilder();
    builder.append(Util.getBoldedString(recipient.toShortString()));
    builder.append(": ");
    builder.append(message == null ? "" : message);
    return builder;
  }

  /**
   * Sound only.
   * Vibration is intentionally not controlled by the app anymore.
   */
  public void setAudibleAlarms(@Nullable Uri ringtone) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Log.d(TAG, "Skipping builder sound setup on Android O+; notification channel controls it.");
      return;
    }

    Uri effectiveRingtone = ringtone;

    if (effectiveRingtone == null) {
      String defaultRingtoneName = SMSecurePreferences.getNotificationRingtone(context);

      if (TextUtils.isEmpty(defaultRingtoneName)) {
        return;
      }

      try {
        effectiveRingtone = Uri.parse(defaultRingtoneName);
      } catch (Exception e) {
        Log.w(TAG, "Unable to parse ringtone uri: " + defaultRingtoneName, e);
        return;
      }
    }

    if (!TextUtils.isEmpty(effectiveRingtone.toString())) {
      setSound(effectiveRingtone);
    }
  }

  /**
   * Intentionally no-op.
   * LED/light behavior is left to the system / channel settings.
   */
  public void setVisualAlarms() {
    Log.d(TAG, "Skipping builder visual alarms; handled by system/channel settings.");
  }

  public void setTicker(@NonNull Recipient recipient, @Nullable CharSequence message) {
    if (privacy.isDisplayMessage()) {
      setTicker(getStyledMessage(recipient, message));
    } else if (privacy.isDisplayContact()) {
      setTicker(getStyledMessage(recipient,
              context.getString(R.string.AbstractNotificationBuilder_new_message)));
    } else {
      setTicker(context.getString(R.string.AbstractNotificationBuilder_new_message));
    }
  }

  protected @NonNull CharSequence trimToDisplayLength(@Nullable CharSequence text) {
    CharSequence safeText = text == null ? "" : text;
    return safeText.length() <= MAX_DISPLAY_LENGTH
            ? safeText
            : safeText.subSequence(0, MAX_DISPLAY_LENGTH);
  }
}