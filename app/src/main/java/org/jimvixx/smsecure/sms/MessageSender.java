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

package org.jimvixx.smsecure.sms;

import android.content.Context;

import org.jimvixx.smsecure.ApplicationContext;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.EncryptingSmsDatabase;
import org.jimvixx.smsecure.database.model.MessageRecord;
import org.jimvixx.smsecure.jobs.SmsSendJob;
import org.jimvixx.smsecure.recipients.Recipients;
import org.whispersystems.jobqueue.JobManager;

public class MessageSender {

  @SuppressWarnings("unused")
  private static final String TAG = MessageSender.class.getSimpleName();

  public static long send(final Context context,
                          final MasterSecret masterSecret,
                          final OutgoingTextMessage message,
                          final long threadId,
                          final boolean forceSms) {
    EncryptingSmsDatabase database = DatabaseFactory.getEncryptingSmsDatabase(context);
    Recipients recipients = message.getRecipients();

    long allocatedThreadId;

    if (threadId == -1) {
      allocatedThreadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipients);
    } else {
      allocatedThreadId = threadId;
    }

    long messageId = database.insertMessageOutbox(masterSecret, allocatedThreadId, message, forceSms, System.currentTimeMillis());

    sendTextMessage(context, recipients, messageId);

    return allocatedThreadId;
  }

  public static void resend(Context context, MasterSecret masterSecret, MessageRecord messageRecord) {
    long messageId = messageRecord.getId();
    boolean isSecure = messageRecord.isSecure();
    long threadId = messageRecord.getThreadId();
    String body = messageRecord.getBody().getBody();
    int subscriptionId = messageRecord.getSubscriptionId();

    Recipients recipients = messageRecord.getRecipients();
    OutgoingTextMessage newMessage;

    if (isSecure) {
      newMessage = new OutgoingEncryptedMessage(recipients, body, subscriptionId);
    } else {
      newMessage = new OutgoingTextMessage(recipients, body, subscriptionId);
    }

    send(context, masterSecret, newMessage, threadId, true);
    DatabaseFactory.getSmsDatabase(context).deleteMessage(messageId);
  }

  private static void sendTextMessage(Context context, Recipients recipients, long messageId) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    jobManager.add(new SmsSendJob(context, messageId, recipients.getPrimaryRecipient().getName()));
  }
}
