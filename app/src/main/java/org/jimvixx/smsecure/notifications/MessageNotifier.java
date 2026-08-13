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

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.jimvixx.smsecure.ConversationActivity;
import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.MessageDatabase;
import org.jimvixx.smsecure.database.SmsDatabase;
import org.jimvixx.smsecure.database.ThreadDatabase;
import org.jimvixx.smsecure.database.model.MessageRecord;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.providers.BadgeWidgetProvider;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.service.KeyCachingService;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.ServiceUtil;
import org.jimvixx.smsecure.util.SpanUtil;

import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.TimeUnit;

/**
 * Handles posting system notifications for new messages.
 */
public class MessageNotifier {

  public static final String EXTRA_REMOTE_REPLY = "extra_remote_reply";
  public static final int MNF_SOUND = 0x1;
  public static final int MNF_LIGHTS = 0x2;
  public static final int MNF_LIGHTS_KEEP = 0x4;
  public static final int MNF_DEFAULTS = MNF_SOUND | MNF_LIGHTS;

  private static final String TAG = MessageNotifier.class.getSimpleName();
  private static final int SUMMARY_NOTIFICATION_ID = 1338;
  private static final String NOTIFICATION_GROUP = "messages";

  private volatile static long visibleThread = -1;

  public static void setVisibleThread(long threadId) {
    visibleThread = threadId;
  }

  public static boolean notificationsRequested(int flags) {
    int mask = MNF_SOUND | MNF_LIGHTS | MNF_LIGHTS_KEEP;
    return ((flags & mask) != 0);
  }

  public static boolean newNotificationRequested(int flags) {
    int mask = MNF_SOUND | MNF_LIGHTS;
    return ((flags & mask) != 0);
  }

  public static void sendDeliveryToast(final Context context, final String recipientName) {
    new Thread() {
      @Override
      public void run() {
        Looper.prepare();
        Toast.makeText(context.getApplicationContext(),
                context.getString(R.string.MessageNotifier_message_received, recipientName),
                Toast.LENGTH_LONG).show();
        Looper.loop();
      }
    }.start();
  }

  public static void notifyMessageDeliveryFailed(Context context, Recipients recipients, long threadId) {
    if (visibleThread == threadId) {
      sendInThreadNotification(context, recipients);
      return;
    }

    Intent intent = new Intent(context, ConversationActivity.class);
    intent.putExtra(ConversationActivity.RECIPIENTS_EXTRA, recipients.getIds());
    intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, threadId);
    intent.setData(Uri.parse("custom://" + System.currentTimeMillis()));

    Uri effectiveGlobalRingtone = NotificationChannels.getPersistedGlobalNotificationRingtone(context);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannels.ensureFailuresChannel(context, effectiveGlobalRingtone);
    }

    String channelId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? NotificationChannels.getFailuresChannelIdForRingtone(effectiveGlobalRingtone)
            : NotificationChannels.FAILURES;

    FailedNotificationBuilder builder = new FailedNotificationBuilder(
            context,
            SMSecurePreferences.getNotificationPrivacy(context),
            intent,
            channelId
    );

    ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
            .notify((int) threadId, builder.build());
  }

  private static void cancelActiveNotifications(@NonNull Context context) {
    NotificationManager notifications = ServiceUtil.getNotificationManager(context);
    notifications.cancel(SUMMARY_NOTIFICATION_ID);

    try {
      StatusBarNotification[] activeNotifications = notifications.getActiveNotifications();

      for (StatusBarNotification activeNotification : activeNotifications) {
        notifications.cancel(activeNotification.getId());
      }
    } catch (Throwable e) {
      Log.w(TAG, e);
      notifications.cancelAll();
    }
  }

  private static void cancelOrphanedNotifications(@NonNull Context context,
                                                  @NonNull NotificationState notificationState) {
    try {
      NotificationManager notifications = ServiceUtil.getNotificationManager(context);
      StatusBarNotification[] activeNotifications = notifications.getActiveNotifications();

      for (StatusBarNotification notification : activeNotifications) {
        boolean validNotification = false;

        if (notification.getId() != SUMMARY_NOTIFICATION_ID) {
          for (NotificationItem item : notificationState.getNotifications()) {
            if (notification.getId() == (SUMMARY_NOTIFICATION_ID + item.getThreadId())) {
              validNotification = true;
              break;
            }
          }

          if (!validNotification) {
            notifications.cancel(notification.getId());
          }
        }
      }
    } catch (Throwable e) {
      Log.w(TAG, e);
    }
  }

  private static void updateNotificationWithFlags(Context context, MasterSecret masterSecret) {
    if (!SMSecurePreferences.isNotificationsEnabled(context)) {
      return;
    }

    updateNotification(context, masterSecret, MessageNotifier.MNF_LIGHTS_KEEP, 0);
  }

  public static void updateNotification(Context context, MasterSecret masterSecret) {
    updateNotificationWithFlags(context, masterSecret);
  }

  public static void updateNotification(Context context, MasterSecret masterSecret, long threadId) {
    boolean isVisible = visibleThread == threadId;

    ThreadDatabase threads = DatabaseFactory.getThreadDatabase(context);
    Recipients recipients = DatabaseFactory.getThreadDatabase(context)
            .getRecipientsForThreadId(threadId);

    if (isVisible) {
      threads.setRead(threadId);
    }

    if (!SMSecurePreferences.isNotificationsEnabled(context) ||
            (recipients != null && recipients.isMuted())) {
      return;
    }

    if (isVisible) {
      sendInThreadNotification(context, threads.getRecipientsForThreadId(threadId));
    } else {
      updateNotification(context, masterSecret, MNF_DEFAULTS, 0);
    }
  }

  private static void updateNotification(Context context,
                                         MasterSecret masterSecret,
                                         int flags,
                                         int reminderCount) {
    try (Cursor telcoCursor = DatabaseFactory.getMessageDatabase(context).getUnread()) {
      if ((telcoCursor == null || telcoCursor.isAfterLast())) {
        cancelActiveNotifications(context);
        updateBadge(context, 0);
        clearReminder(context);
        return;
      }

      NotificationState notificationState = constructNotificationState(context, masterSecret, telcoCursor);

      if (notificationState.hasMultipleThreads()) {
        for (long threadId : notificationState.getThreads()) {
          sendSingleThreadNotification(
                  context,
                  masterSecret,
                  new NotificationState(notificationState.getNotificationsForThread(threadId)),
                  0,
                  true
          );
        }

        sendMultipleThreadNotification(context, notificationState, flags);
      } else {
        sendSingleThreadNotification(context, masterSecret, notificationState, flags, false);
      }

      cancelOrphanedNotifications(context, notificationState);
      updateBadge(context, notificationState.getMessageCount());

      if (newNotificationRequested(flags)) {
        scheduleReminder(context, reminderCount);
      }
    }
  }

  private static void triggerNotificationAlarms(@NonNull AbstractNotificationBuilder builder,
                                                @NonNull Uri effectiveRingtone,
                                                int flags) {
    if ((flags & MNF_SOUND) == MNF_SOUND) {
      builder.setAudibleAlarms(effectiveRingtone);
    }

    if ((flags & MNF_LIGHTS) == MNF_LIGHTS || (flags & MNF_LIGHTS_KEEP) == MNF_LIGHTS_KEEP) {
      builder.setVisualAlarms();
    }
  }

  private static boolean canPostNotifications(Context context) {
    if (Build.VERSION.SDK_INT < 33) return true;
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED;
  }

  @SuppressLint("MissingPermission")
  private static void notifySafely(Context context,
                                   int notificationId,
                                   android.app.Notification notification) {
    if (!canPostNotifications(context)) {
      return;
    }

    try {
      NotificationManagerCompat.from(context).notify(notificationId, notification);
    } catch (SecurityException ignored) {
    }
  }

  @NonNull
  private static String resolveMessageChannelId(@NonNull Uri effectiveRingtone) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return NotificationChannels.MESSAGES_DEFAULT;
    }

    return NotificationChannels.getMessagesChannelIdForRingtone(effectiveRingtone);
  }

  private static void ensureMessageChannelIfNeeded(@NonNull Context context,
                                                   @NonNull Uri effectiveRingtone) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannels.ensureMessagesChannel(context, effectiveRingtone);
    }
  }

  private static void sendSingleThreadNotification(Context context,
                                                   MasterSecret masterSecret,
                                                   NotificationState notificationState,
                                                   int flags,
                                                   boolean bundled) {
    if (notificationState.getNotifications().isEmpty()) {
      if (!bundled) cancelActiveNotifications(context);
      return;
    }

    Uri effectiveRingtone = NotificationChannels.resolveEffectiveMessageRingtone(
            context,
            notificationState.getRingtone()
    );

    ensureMessageChannelIfNeeded(context, effectiveRingtone);

    SingleRecipientNotificationBuilder builder =
            new SingleRecipientNotificationBuilder(
                    context,
                    SMSecurePreferences.getNotificationPrivacy(context),
                    resolveMessageChannelId(effectiveRingtone)
            );

    List<NotificationItem> notifications = notificationState.getNotifications();
    Recipients recipients = notifications.get(0).getRecipients();
    int notificationId = (int) (SUMMARY_NOTIFICATION_ID + (bundled ? notifications.get(0).getThreadId() : 0));

    builder.setThread(notifications.get(0).getRecipients());
    builder.setMessageCount(notificationState.getMessageCount());
    builder.setPrimaryMessageBody(recipients,
            notifications.get(0).getIndividualRecipient(),
            notifications.get(0).getText());
    builder.setContentIntent(notifications.get(0).getPendingIntent(context));
    builder.setGroup(NOTIFICATION_GROUP);
    builder.setDeleteIntent(notificationState.getDeleteIntent(context));

    long timestamp = notifications.get(0).getTimestamp();
    if (timestamp != 0) {
      builder.setWhen(timestamp);
    }

    builder.addActions(masterSecret,
            notificationState.getMarkAsReadIntent(context, notificationId),
            notificationState.getRemoteReplyIntent(context, notifications.get(0).getRecipients()));

    builder.addAndroidAutoAction(notifications.get(0).getTimestamp());

    ListIterator<NotificationItem> iterator = notifications.listIterator(notifications.size());

    while (iterator.hasPrevious()) {
      NotificationItem item = iterator.previous();
      builder.addMessageBody(
              item.getRecipients(),
              item.getIndividualRecipient(),
              item.getText(),
              item.getTimestamp());
    }

    if (notificationsRequested(flags)) {
      triggerNotificationAlarms(builder, effectiveRingtone, flags);
      builder.setTicker(notifications.get(0).getIndividualRecipient(), notifications.get(0).getText());
    }

    notifySafely(context, notificationId, builder.build());
  }

  private static void sendMultipleThreadNotification(Context context,
                                                     NotificationState notificationState,
                                                     int flags) {
    if (notificationState.getNotifications().isEmpty()) {
      return;
    }

    Uri effectiveRingtone = NotificationChannels.resolveEffectiveMessageRingtone(
            context,
            notificationState.getRingtone()
    );

    ensureMessageChannelIfNeeded(context, effectiveRingtone);

    MultipleRecipientNotificationBuilder builder =
            new MultipleRecipientNotificationBuilder(
                    context,
                    SMSecurePreferences.getNotificationPrivacy(context),
                    resolveMessageChannelId(effectiveRingtone)
            );

    List<NotificationItem> notifications = notificationState.getNotifications();

    builder.setMessageCount(notificationState.getMessageCount(), notificationState.getThreadCount());
    builder.setMostRecentSender(notifications.get(0).getIndividualRecipient());
    builder.setGroup(NOTIFICATION_GROUP);
    builder.setDeleteIntent(notificationState.getDeleteIntent(context));

    long timestamp = notifications.get(0).getTimestamp();
    if (timestamp != 0) {
      builder.setWhen(timestamp);
    }

    builder.addActions(notificationState.getMarkAsReadIntent(context, SUMMARY_NOTIFICATION_ID));

    ListIterator<NotificationItem> iterator = notifications.listIterator(0);

    while (iterator.hasNext()) {
      NotificationItem item = iterator.next();
      builder.addMessageBody(item.getIndividualRecipient(), item.getText());
    }

    if (notificationsRequested(flags)) {
      triggerNotificationAlarms(builder, effectiveRingtone, flags);
      builder.setTicker(notifications.get(0).getIndividualRecipient(), notifications.get(0).getText());
    }

    notifySafely(context, SUMMARY_NOTIFICATION_ID, builder.build());
  }

  private static void sendInThreadNotification(Context context, Recipients recipients) {
    if (!SMSecurePreferences.isInThreadNotifications(context) ||
            ServiceUtil.getAudioManager(context).getRingerMode() != AudioManager.RINGER_MODE_NORMAL) {
      return;
    }

    Uri effectiveRingtone = NotificationChannels.resolveEffectiveMessageRingtone(
            context,
            recipients != null ? recipients.getRingtone() : null
    );

    if (effectiveRingtone == null || effectiveRingtone.equals(Uri.EMPTY)
            || effectiveRingtone.toString().isEmpty()) {
      Log.d(TAG, "ringtone uri is empty");
      return;
    }

    Ringtone ringtone = RingtoneManager.getRingtone(context, effectiveRingtone);

    if (ringtone == null) {
      Log.w(TAG, "ringtone is null");
      return;
    }

    ringtone.setAudioAttributes(new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .build());

    ringtone.play();
  }

  private static NotificationState constructNotificationState(Context context,
                                                              MasterSecret masterSecret,
                                                              Cursor cursor) {
    NotificationState notificationState = new NotificationState();
    MessageRecord record;
    MessageDatabase.Reader reader;

    if (masterSecret == null) {
      reader = DatabaseFactory.getMessageDatabase(context).readerFor(cursor);
    } else {
      reader = DatabaseFactory.getMessageDatabase(context).readerFor(cursor, masterSecret);
    }

    while ((record = reader.getNext()) != null) {
      long id = record.getId();
      Recipient recipient = record.getIndividualRecipient();
      Recipients recipients = record.getRecipients();
      long threadId = record.getThreadId();
      CharSequence body = record.getDisplayBody();
      Recipients threadRecipients = null;
      long timestamp = record.getTimestamp();

      if (threadId != -1) {
        threadRecipients = DatabaseFactory.getThreadDatabase(context).getRecipientsForThreadId(threadId);
      }

      if (SmsDatabase.Types.isDecryptInProgressType(record.getType()) || !record.getBody().isPlaintext()) {
        body = SpanUtil.italic(context.getString(R.string.MessageNotifier_encrypted_message));
      }

      if (threadRecipients == null || !threadRecipients.isMuted()) {
        notificationState.addNotification(
                new NotificationItem(id, recipient, recipients, threadRecipients, threadId, body, timestamp)
        );
      }
    }

    reader.close();
    return notificationState;
  }

  private static void scheduleReminder(Context context, int count) {
    if (count >= SMSecurePreferences.getRepeatAlertsCount(context)) {
      return;
    }

    AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    Intent alarmIntent = new Intent(ReminderReceiver.REMINDER_ACTION);
    alarmIntent.putExtra("reminder_count", count);

    PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            alarmIntent,
            PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
    );

    long timeout = TimeUnit.MINUTES.toMillis(2);
    alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + timeout, pendingIntent);
  }

  public static void clearReminder(Context context) {
    Intent alarmIntent = new Intent(ReminderReceiver.REMINDER_ACTION);
    PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            alarmIntent,
            PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
    );

    AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    alarmManager.cancel(pendingIntent);
  }

  private static void updateBadge(Context context, int count) {
    BadgeWidgetProvider.updateBadge(context, count);
  }

  public static class ReminderReceiver extends BroadcastReceiver {

    public static final String REMINDER_ACTION =
            "org.jimvixx.smsecure.MessageNotifier.REMINDER_ACTION";

    @Override
    public void onReceive(final Context context, final Intent intent) {
      new Thread(() -> {
        MasterSecret masterSecret = KeyCachingService.getMasterSecret(context);
        int reminderCount = intent.getIntExtra("reminder_count", 0);
        MessageNotifier.updateNotification(context, masterSecret, MNF_DEFAULTS, reminderCount + 1);
      }).start();
    }
  }
}