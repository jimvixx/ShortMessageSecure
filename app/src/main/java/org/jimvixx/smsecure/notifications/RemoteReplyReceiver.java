/*
 * Copyright (C) 2016 Open Whisper Systems
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
 * Get the response text from the Wearable Device and sends an message as a reply
 */
public class RemoteReplyReceiver extends MasterSecretBroadcastReceiver {

  public static final String TAG = RemoteReplyReceiver.class.getSimpleName();
  public static final String REPLY_ACTION = "org.jimvixx.smsecure.notifications.WEAR_REPLY";
  public static final String RECIPIENT_IDS_EXTRA = "recipient_ids";

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

    final CharSequence responseText = remoteInput.getCharSequence(MessageNotifier.EXTRA_REMOTE_REPLY);

    if (TextUtil.isEmpty(responseText)) return;

    if (masterSecret == null) return;

    final Context appContext = context.getApplicationContext();
    final MasterSecret ms = masterSecret;
    final String text = responseText.toString();

    EXECUTOR.execute(() -> {
      long threadId;

      Optional<RecipientsPreferences> preferences =
              DatabaseFactory.getRecipientPreferenceDatabase(appContext).getRecipientsPreferences(recipientIds);

      int subscriptionId =
              preferences.isPresent() ? preferences.get().getDefaultSubscriptionId().or(-1) : -1;

      Recipients recipients = RecipientFactory.getRecipientsForIds(appContext, recipientIds, false);

      boolean secure =
              SessionUtil.hasSession(appContext, ms, recipients.getPrimaryRecipient().getNumber(), subscriptionId);

      OutgoingTextMessage reply =
              secure ? new OutgoingEncryptedMessage(recipients, text, subscriptionId)
                      : new OutgoingTextMessage(recipients, text, subscriptionId);

      threadId = MessageSender.send(appContext, ms, reply, -1, false);

      DatabaseFactory.getThreadDatabase(appContext).setRead(threadId);
      DatabaseFactory.getThreadDatabase(appContext).setLastSeen(threadId);

      MessageNotifier.updateNotification(appContext, ms);
    });
  }
}
