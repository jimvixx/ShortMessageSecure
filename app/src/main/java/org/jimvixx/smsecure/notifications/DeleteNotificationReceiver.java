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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.database.DatabaseFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeleteNotificationReceiver extends BroadcastReceiver {

  public static final String DELETE_NOTIFICATION_ACTION =
          "org.jimvixx.smsecure.DELETE_NOTIFICATION";

  public static final String EXTRA_IDS = "message_ids";
  private static final ExecutorService EXECUTOR =
          Executors.newSingleThreadExecutor();

  @Override
  public void onReceive(@NonNull Context context, Intent intent) {
    if (!DELETE_NOTIFICATION_ACTION.equals(intent.getAction())) return;

    MessageNotifier.clearReminder(context);

    final long[] ids = intent.getLongArrayExtra(EXTRA_IDS);

    if (ids == null) return;

    final Context appContext = context.getApplicationContext();

    EXECUTOR.execute(() -> {
      for (long id : ids) {
        DatabaseFactory.getSmsDatabase(appContext)
                .markAsNotified(id);
      }
    });
  }
}
