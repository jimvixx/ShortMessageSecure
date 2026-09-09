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

package org.jimvixx.smsecure.notifications;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.SmsDatabase;
import org.jimvixx.smsecure.logging.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeleteMessageReceiver extends MasterSecretBroadcastReceiver {

  public static final String DELETE_MESSAGE_ACTION =
          "org.jimvixx.smsecure.notifications.DELETE_MESSAGE";
  public static final String MESSAGE_ID_EXTRA = "message_id";

  private static final String TAG = DeleteMessageReceiver.class.getSimpleName();
  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

  @Override
  protected void onReceive(@NonNull Context context,
                           @NonNull Intent intent,
                           @Nullable MasterSecret masterSecret) {
    if (!DELETE_MESSAGE_ACTION.equals(intent.getAction())) return;

    long messageId = intent.getLongExtra(MESSAGE_ID_EXTRA, -1);
    if (messageId < 0) return;

    if (masterSecret == null) {
      Log.w(TAG, "Ignoring delete action while SMSecure is locked.");
      return;
    }

    Context appContext = context.getApplicationContext();
    PendingResult pendingResult = goAsync();

    EXECUTOR.execute(() -> {
      try {
        SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(appContext);
        if (smsDatabase.getThreadIdForMessage(messageId) != -1) {
          smsDatabase.deleteMessage(messageId);
        }
        MessageNotifier.updateNotification(appContext, masterSecret);
      } finally {
        pendingResult.finish();
      }
    });
  }
}
