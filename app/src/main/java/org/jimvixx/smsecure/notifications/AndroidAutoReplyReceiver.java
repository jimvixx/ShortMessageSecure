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

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.RemoteInput;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.SessionUtil;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.RecipientPreferenceDatabase.RecipientsPreferences;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.sms.MessageSender;
import org.jimvixx.smsecure.sms.OutgoingEncryptedMessage;
import org.jimvixx.smsecure.sms.OutgoingTextMessage;
import org.jimvixx.smsecure.util.TextUtil;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Get the response text from the Android Auto and sends an message as a reply
 */
public class AndroidAutoReplyReceiver extends MasterSecretBroadcastReceiver {

  public static final String TAG = AndroidAutoReplyReceiver.class.getSimpleName();
  public static final String REPLY_ACTION = "org.jimvixx.smsecure.notifications.ANDROID_AUTO_REPLY";
  public static final String RECIPIENT_IDS_EXTRA = "car_recipient_ids";
  public static final String VOICE_REPLY_KEY = "car_voice_reply_key";
  public static final String THREAD_ID_EXTRA = "car_reply_thread_id";

  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

  @Override
  protected void onReceive(@NonNull Context context,
                           @NonNull Intent intent,
                           @Nullable MasterSecret masterSecret) {
    if (!REPLY_ACTION.equals(intent.getAction())) return;

    Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);
    if (remoteInput == null) return;

    final long[] recipientIds = intent.getLongArrayExtra(RECIPIENT_IDS_EXTRA);
    if (recipientIds == null || recipientIds.length == 0) return;

    final long threadId = intent.getLongExtra(THREAD_ID_EXTRA, -1);
    final CharSequence responseText = getMessageText(intent);

    if (TextUtil.isEmpty(responseText)) return;

    final Context appContext = context.getApplicationContext();
    final MasterSecret ms = masterSecret;

    if (ms == null) {
      Log.w(TAG, "Missing masterSecret; cannot send Android Auto reply.");
      return;
    }

    final Recipients recipients = RecipientFactory.getRecipientsForIds(appContext, recipientIds, false);

    EXECUTOR.execute(() -> {
      long replyThreadId;

      Optional<RecipientsPreferences> preferences =
              DatabaseFactory.getRecipientPreferenceDatabase(appContext).getRecipientsPreferences(recipientIds);

      int subscriptionId =
              preferences.isPresent() ? preferences.get().getDefaultSubscriptionId().or(-1) : -1;

      Log.i(TAG, "Sending text message");

      boolean secure =
              SessionUtil.hasSession(appContext, ms, recipients.getPrimaryRecipient().getNumber(), subscriptionId);

      OutgoingTextMessage reply =
              secure ? new OutgoingEncryptedMessage(recipients, responseText.toString(), subscriptionId)
                      : new OutgoingTextMessage(recipients, responseText.toString(), subscriptionId);

      replyThreadId = MessageSender.send(appContext, ms, reply, threadId, false);

      DatabaseFactory.getThreadDatabase(appContext).setRead(replyThreadId);
      MessageNotifier.updateNotification(appContext, ms);
    });
  }

  @Nullable
  private CharSequence getMessageText(@NonNull Intent intent) {
    Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);
    if (remoteInput != null) {
      return remoteInput.getCharSequence(VOICE_REPLY_KEY);
    }
    return null;
  }
}
