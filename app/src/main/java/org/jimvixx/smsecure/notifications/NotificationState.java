/*
 * Copyright (C) 2014 Open Whisper Systems
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

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.ConversationActivity;
import org.jimvixx.smsecure.ConversationPopupActivity;
import org.jimvixx.smsecure.database.RecipientPreferenceDatabase.VibrateState;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.recipients.Recipients;

import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;

public class NotificationState {

  private final LinkedList<NotificationItem> notifications = new LinkedList<>();
  private final LinkedHashSet<Long> threads = new LinkedHashSet<>();

  private int notificationCount = 0;

  public NotificationState() {
  }

  public NotificationState(@NonNull List<NotificationItem> items) {
    for (NotificationItem item : items) {
      addNotification(item);
    }
  }

  /**
   * Use for PendingIntents that should never be modified (most cases).
   */
  private static int immutableUpdateCurrentFlags() {
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    flags |= PendingIntent.FLAG_IMMUTABLE;
    return flags;
  }

  /**
   * Use only when the PendingIntent must be mutable (e.g., RemoteInput inline replies).
   */
  private static int mutableUpdateCurrentFlags() {
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      flags |= PendingIntent.FLAG_MUTABLE;
    } else {
      // Pre-S doesn't require specifying mutability, but adding IMMUTABLE is safe.
      flags |= PendingIntent.FLAG_IMMUTABLE;
    }
    return flags;
  }

  public void addNotification(NotificationItem item) {
    notifications.addFirst(item);

    threads.remove(item.getThreadId());

    threads.add(item.getThreadId());
    notificationCount++;
  }

  public @Nullable Uri getRingtone() {
    if (!notifications.isEmpty()) {
      Recipients recipients = notifications.getFirst().getRecipients();
      return recipients.getRingtone();
    }
    return null;
  }

  public VibrateState getVibrate() {
    if (!notifications.isEmpty()) {
      Recipients recipients = notifications.getFirst().getRecipients();
      return recipients.getVibrate();
    }
    return VibrateState.DEFAULT;
  }

  public boolean hasMultipleThreads() {
    return threads.size() > 1;
  }

  public LinkedHashSet<Long> getThreads() {
    return threads;
  }

  public int getThreadCount() {
    return threads.size();
  }

  public int getMessageCount() {
    return notificationCount;
  }

  // ---- PendingIntent flags helpers ----

  public List<NotificationItem> getNotifications() {
    return notifications;
  }

  public List<NotificationItem> getNotificationsForThread(long threadId) {
    LinkedList<NotificationItem> list = new LinkedList<>();

    for (NotificationItem item : notifications) {
      if (item.getThreadId() == threadId) list.addFirst(item);
    }

    return list;
  }

  // ---- PendingIntents ----

  public PendingIntent getMarkAsReadIntent(Context context, int notificationId) {
    long[] threadArray = new long[threads.size()];
    int index = 0;

    for (long thread : threads) {
      Log.w("NotificationState", "Added thread: " + thread);
      threadArray[index++] = thread;
    }

    Intent intent = new Intent(MarkReadReceiver.CLEAR_ACTION);
    intent.setClass(context, MarkReadReceiver.class);
    intent.setData(Uri.parse("custom://" + System.currentTimeMillis()));
    intent.putExtra(MarkReadReceiver.THREAD_IDS_EXTRA, threadArray);
    intent.putExtra(MarkReadReceiver.NOTIFICATION_ID_EXTRA, notificationId);

    return PendingIntent.getBroadcast(context, 0, intent, immutableUpdateCurrentFlags());
  }

  /**
   * RemoteInput reply (inline reply). Must be MUTABLE on S+.
   */
  public PendingIntent getRemoteReplyIntent(Context context, Recipients recipients) {
    if (threads.size() != 1) {
      throw new AssertionError("We only support replies to single thread notifications!");
    }

    Intent intent = new Intent(RemoteReplyReceiver.REPLY_ACTION);
    intent.setClass(context, RemoteReplyReceiver.class);
    intent.setData(Uri.parse("custom://" + System.currentTimeMillis()));
    intent.putExtra(RemoteReplyReceiver.RECIPIENT_IDS_EXTRA, recipients.getIds());
    intent.setPackage(context.getPackageName());

    return PendingIntent.getBroadcast(context, 0, intent, mutableUpdateCurrentFlags());
  }

  /**
   * Android Auto reply uses RemoteInput as well. Must be MUTABLE on S+.
   */
  public PendingIntent getAndroidAutoReplyIntent(Context context, Recipients recipients) {
    if (threads.size() != 1) {
      throw new AssertionError("We only support replies to single thread notifications!");
    }

    Intent intent = new Intent(AndroidAutoReplyReceiver.REPLY_ACTION);
    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
    intent.setClass(context, AndroidAutoReplyReceiver.class);
    intent.setData(Uri.parse("custom://" + System.currentTimeMillis()));
    intent.putExtra(AndroidAutoReplyReceiver.RECIPIENT_IDS_EXTRA, recipients.getIds());
    intent.putExtra(AndroidAutoReplyReceiver.THREAD_ID_EXTRA, (long) threads.toArray()[0]);
    intent.setPackage(context.getPackageName());

    return PendingIntent.getBroadcast(context, 0, intent, mutableUpdateCurrentFlags());
  }

  public PendingIntent getQuickReplyIntent(Context context, Recipients recipients) {
    if (threads.size() != 1) {
      throw new AssertionError("We only support replies to single thread notifications! " + threads.size());
    }

    Intent intent = new Intent(context, ConversationPopupActivity.class);
    intent.putExtra(ConversationActivity.RECIPIENTS_EXTRA, recipients.getIds());
    intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, (long) threads.toArray()[0]);
    intent.setData(Uri.parse("custom://" + System.currentTimeMillis()));

    return PendingIntent.getActivity(context, 0, intent, immutableUpdateCurrentFlags());
  }

  public PendingIntent getDeleteIntent(Context context) {
    int index = 0;
    long[] ids = new long[notifications.size()];

    for (NotificationItem notificationItem : notifications) {
      ids[index++] = notificationItem.getId();
    }

    Intent intent = new Intent(context, DeleteNotificationReceiver.class);
    intent.setAction(DeleteNotificationReceiver.DELETE_NOTIFICATION_ACTION);
    intent.putExtra(DeleteNotificationReceiver.EXTRA_IDS, ids);
    intent.setData(Uri.parse("custom://" + System.currentTimeMillis()));

    return PendingIntent.getBroadcast(context, 0, intent, immutableUpdateCurrentFlags());
  }
}
