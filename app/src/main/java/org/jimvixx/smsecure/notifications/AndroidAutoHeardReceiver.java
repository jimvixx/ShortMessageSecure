/*
 * Copyright (C) 2011 Open Whisper Systems
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
import android.content.Intent;
import org.jimvixx.smsecure.logging.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// Marks an Android Auto as read after the driver have listened to it
public class AndroidAutoHeardReceiver extends MasterSecretBroadcastReceiver {

  public static final String TAG                   = AndroidAutoHeardReceiver.class.getSimpleName();
  public static final String HEARD_ACTION          = "org.jimvixx.smsecure.notifications.ANDROID_AUTO_HEARD";
  public static final String THREAD_IDS_EXTRA      = "car_heard_thread_ids";
  public static final String NOTIFICATION_ID_EXTRA = "car_notification_id";

  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

  @Override
  protected void onReceive(@NonNull Context context,
                           @NonNull Intent intent,
                           @Nullable MasterSecret masterSecret)
  {
    if (!HEARD_ACTION.equals(intent.getAction())) return;

    final long[] threadIds = intent.getLongArrayExtra(THREAD_IDS_EXTRA);
    if (threadIds == null || threadIds.length == 0) return;

    int notificationId = intent.getIntExtra(NOTIFICATION_ID_EXTRA, -1);
    if (notificationId != -1) {
      NotificationManagerCompat.from(context).cancel(notificationId);
    }

    final Context appContext = context.getApplicationContext();
    final MasterSecret ms = masterSecret;

    EXECUTOR.execute(() -> {
      for (long threadId : threadIds) {
        Log.i(TAG, "Marking message as read: " + threadId);
        DatabaseFactory.getThreadDatabase(appContext).setRead(threadId);
      }

      if (ms != null) {
        MessageNotifier.updateNotification(appContext, ms);
      }
    });
  }
}
