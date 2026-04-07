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

package org.jimvixx.smsecure.notifications;

import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.util.SMSecurePreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class NotificationChannels {

  /**
   * Legacy fallback ids kept only for compatibility with old constructors.
   * New code should not depend on these ids.
   */
  public static final String MESSAGES_DEFAULT = "messages_default_legacy";
  public static final String FAILURES = "failures_legacy";

  public static final String MESSAGES_SILENT = "messages_silent";
  public static final String FAILURES_SILENT = "failures_silent";
  public static final String LOCKED_STATUS = "locked_status";
  public static final String OTHER = "other";
  private static final String MESSAGES_PREFIX = "messages_";
  private static final String FAILURES_PREFIX = "failures_";
  private static final String GROUP_MESSAGES = "group_messages";
  private static final String GROUP_ERRORS = "group_errors";
  private static final String GROUP_STATUS = "group_status";

  private static final String TAG = NotificationChannels.class.getSimpleName();

  private NotificationChannels() {
  }

  public static void create(@NonNull Context context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return;
    }

    createApi26(context);
  }

  /**
   * Returns the persisted global app ringtone.
   * <p>
   * Semantics:
   * - empty string / null in preferences => silent (Uri.EMPTY)
   * - valid string => parsed Uri
   * - invalid value => system default notification Uri
   */
  @NonNull
  public static Uri getPersistedGlobalNotificationRingtone(@NonNull Context context) {
    String globalRingtone = SMSecurePreferences.getNotificationRingtone(context);

    if (TextUtils.isEmpty(globalRingtone)) {
      return Uri.EMPTY;
    }

    try {
      return Uri.parse(globalRingtone);
    } catch (Exception e) {
      Log.w(TAG, "Unable to parse global notification ringtone: " + globalRingtone, e);
      return Settings.System.DEFAULT_NOTIFICATION_URI;
    }
  }

  /**
   * Resolves the final ringtone that should actually be played for a message notification.
   * <p>
   * threadRingtone semantics:
   * - null      => use app default ringtone
   * - Uri.EMPTY => silent
   * - otherwise => use that custom/system/default Uri directly
   */
  @NonNull
  public static Uri resolveEffectiveMessageRingtone(@NonNull Context context,
                                                    @Nullable Uri threadRingtone) {
    if (threadRingtone != null) {
      return threadRingtone;
    }

    return getPersistedGlobalNotificationRingtone(context);
  }

  public static void ensureMessagesChannel(@NonNull Context context, @NonNull Uri effectiveRingtone) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return;
    }

    ensureMessagesChannelApi26(context, effectiveRingtone);
  }

  public static void ensureFailuresChannel(@NonNull Context context, @NonNull Uri effectiveGlobalRingtone) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return;
    }

    ensureFailuresChannelApi26(context, effectiveGlobalRingtone);
  }

  @NonNull
  public static String getMessagesChannelIdForRingtone(@NonNull Uri effectiveRingtone) {
    if (Uri.EMPTY.equals(effectiveRingtone) || TextUtils.isEmpty(effectiveRingtone.toString())) {
      return MESSAGES_SILENT;
    }

    return MESSAGES_PREFIX + shortHash(effectiveRingtone.toString());
  }

  @NonNull
  public static String getFailuresChannelIdForRingtone(@NonNull Uri effectiveGlobalRingtone) {
    if (Uri.EMPTY.equals(effectiveGlobalRingtone) || TextUtils.isEmpty(effectiveGlobalRingtone.toString())) {
      return FAILURES_SILENT;
    }

    return FAILURES_PREFIX + shortHash(effectiveGlobalRingtone.toString());
  }

  @RequiresApi(26)
  private static void createApi26(@NonNull Context context) {
    NotificationManager notificationManager = context.getSystemService(NotificationManager.class);

    if (notificationManager == null) {
      return;
    }

    createChannelGroups(notificationManager, context);

    notificationManager.createNotificationChannels(List.of(
            buildLockedStatusChannel(context),
            buildOtherChannel(context)
    ));
  }

  @RequiresApi(26)
  private static void createChannelGroups(@NonNull NotificationManager notificationManager,
                                          @NonNull Context context) {
    notificationManager.createNotificationChannelGroups(Arrays.asList(
            new NotificationChannelGroup(
                    GROUP_MESSAGES,
                    context.getString(R.string.NotificationChannel_messages)
            ),
            new NotificationChannelGroup(
                    GROUP_ERRORS,
                    context.getString(R.string.NotificationChannel_failures)
            ),
            new NotificationChannelGroup(
                    GROUP_STATUS,
                    context.getString(R.string.NotificationChannel_other)
            )
    ));
  }

  @RequiresApi(26)
  private static void ensureMessagesChannelApi26(@NonNull Context context,
                                                 @NonNull Uri effectiveRingtone) {
    NotificationManager notificationManager = context.getSystemService(NotificationManager.class);

    if (notificationManager == null) {
      return;
    }

    createChannelGroups(notificationManager, context);

    String channelId = getMessagesChannelIdForRingtone(effectiveRingtone);
    NotificationChannel existing = notificationManager.getNotificationChannel(channelId);

    if (existing != null) {
      return;
    }

    NotificationChannel channel = buildMessagesChannel(
            channelId,
            effectiveRingtone,
            getMessagesChannelName(context, effectiveRingtone)
    );

    notificationManager.createNotificationChannel(channel);
  }

  @RequiresApi(26)
  private static void ensureFailuresChannelApi26(@NonNull Context context,
                                                 @NonNull Uri effectiveGlobalRingtone) {
    NotificationManager notificationManager = context.getSystemService(NotificationManager.class);

    if (notificationManager == null) {
      return;
    }

    createChannelGroups(notificationManager, context);

    String channelId = getFailuresChannelIdForRingtone(effectiveGlobalRingtone);
    NotificationChannel existing = notificationManager.getNotificationChannel(channelId);

    if (existing != null) {
      return;
    }

    NotificationChannel channel = buildFailureChannel(
            context,
            channelId,
            effectiveGlobalRingtone
    );

    notificationManager.createNotificationChannel(channel);
  }

  @RequiresApi(26)
  @NonNull
  private static NotificationChannel buildMessagesChannel(@NonNull String channelId,
                                                          @NonNull Uri effectiveRingtone,
                                                          @NonNull CharSequence channelName) {
    NotificationChannel channel = new NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_HIGH
    );

    channel.setGroup(GROUP_MESSAGES);

    AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    channel.setSound(toSoundUriOrNull(effectiveRingtone), audioAttributes);

    Log.d(TAG, "buildMessagesChannel id=" + channelId
            + ", name=" + channelName
            + ", soundUri=" + toSoundUriOrNull(effectiveRingtone));

    return channel;
  }

  @RequiresApi(26)
  @NonNull
  private static NotificationChannel buildFailureChannel(@NonNull Context context,
                                                         @NonNull String channelId,
                                                         @NonNull Uri effectiveGlobalRingtone) {
    NotificationChannel channel = new NotificationChannel(
            channelId,
            getFailuresChannelName(context, effectiveGlobalRingtone),
            NotificationManager.IMPORTANCE_HIGH
    );

    channel.setGroup(GROUP_ERRORS);

    AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    channel.setSound(toSoundUriOrNull(effectiveGlobalRingtone), audioAttributes);

    return channel;
  }

  @RequiresApi(26)
  @NonNull
  private static NotificationChannel buildLockedStatusChannel(@NonNull Context context) {
    NotificationChannel channel = new NotificationChannel(
            LOCKED_STATUS,
            context.getString(R.string.NotificationChannel_locked_status),
            NotificationManager.IMPORTANCE_LOW
    );

    channel.setGroup(GROUP_STATUS);
    return channel;
  }

  @RequiresApi(26)
  @NonNull
  private static NotificationChannel buildOtherChannel(@NonNull Context context) {
    NotificationChannel channel = new NotificationChannel(
            OTHER,
            context.getString(R.string.NotificationChannel_other),
            NotificationManager.IMPORTANCE_LOW
    );

    channel.setGroup(GROUP_STATUS);
    return channel;
  }

  @NonNull
  private static CharSequence getMessagesChannelName(@NonNull Context context,
                                                     @NonNull Uri effectiveRingtone) {
    String base = context.getString(R.string.NotificationChannel_messages);
    String title = getDisplayTitleForRingtone(context, effectiveRingtone);

    return base + " — " + title;
  }

  @NonNull
  private static CharSequence getFailuresChannelName(@NonNull Context context,
                                                     @NonNull Uri effectiveGlobalRingtone) {
    String base = context.getString(R.string.NotificationChannel_failures);
    String title = getDisplayTitleForRingtone(context, effectiveGlobalRingtone);

    return base + " — " + title;
  }

  @NonNull
  private static String getDisplayTitleForRingtone(@NonNull Context context,
                                                   @NonNull Uri effectiveRingtone) {
    if (Uri.EMPTY.equals(effectiveRingtone) || TextUtils.isEmpty(effectiveRingtone.toString())) {
      return context.getString(R.string.Silent);
    }

    try {
      Ringtone tone = RingtoneManager.getRingtone(context, effectiveRingtone);

      if (tone != null) {
        String title = tone.getTitle(context);
        if (!TextUtils.isEmpty(title)) {
          return title;
        }
      }
    } catch (Exception e) {
      Log.w(TAG, "Unable to resolve ringtone title for " + effectiveRingtone, e);
    }

    return context.getString(R.string.Custom) + " " + shortHash(effectiveRingtone.toString());
  }

  @Nullable
  private static Uri toSoundUriOrNull(@NonNull Uri effectiveRingtone) {
    if (Uri.EMPTY.equals(effectiveRingtone) || TextUtils.isEmpty(effectiveRingtone.toString())) {
      return null;
    }

    return effectiveRingtone;
  }

  @NonNull
  private static String shortHash(@NonNull String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));

      StringBuilder builder = new StringBuilder(16);
      for (int i = 0; i < 8 && i < bytes.length; i++) {
        builder.append(String.format(Locale.US, "%02x", bytes[i]));
      }
      return builder.toString();
    } catch (Exception e) {
      return Integer.toHexString(value.hashCode());
    }
  }
}