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

package org.jimvixx.smsecure.jobs;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.SmsCipher;
import org.jimvixx.smsecure.crypto.storage.SMSecureSignalProtocolStore;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.EncryptingSmsDatabase;
import org.jimvixx.smsecure.database.NoSuchMessageException;
import org.jimvixx.smsecure.database.model.SmsMessageRecord;
import org.jimvixx.smsecure.jobs.requirements.MasterSecretRequirement;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.notifications.MessageNotifier;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.service.SmsDeliveryListener;
import org.jimvixx.smsecure.sms.MultipartSmsMessageHandler;
import org.jimvixx.smsecure.sms.OutgoingTextMessage;
import org.jimvixx.smsecure.transport.UndeliverableMessageException;
import org.jimvixx.smsecure.util.NumberUtil;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.dualsim.DualSimUtil;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.UntrustedIdentityException;

import java.util.ArrayList;

public class SmsSendJob extends SendJob {

  private static final String TAG = SmsSendJob.class.getSimpleName();

  private final long messageId;

  public SmsSendJob(Context context, long messageId, String name) {
    super(context, constructParameters(context, name));
    this.messageId = messageId;
  }

  /**
   * Builds a stable unique requestCode for PendingIntents.
   * <p>
   * NOTE: requestCode must fit into int. We hash messageId down to 20 bits,
   * then add flags + part index.
   */
  private static int buildRequestCode(int messageId, boolean sent, int partIndex) {
    int id = (messageId * 2654435761L) == 0 ? messageId : (int) (messageId * 2654435761L); // cheap mix
    id = id & 0x000FFFFF; // keep 20 bits

    int base = sent ? 0x00100000 : 0x00200000; // separate sent vs delivered namespaces
    int part = (partIndex & 0x00000FFF);       // allow up to 4096 parts (way more than needed)
    return base | (id << 12) | part;
  }

  private static JobParameters constructParameters(Context context, String name) {
    JobParameters.Builder builder = JobParameters.newBuilder()
            .withPersistence()
            .withRequirement(new MasterSecretRequirement(context))
            .withRetryCount(15)
            .withGroupId(name);

    return builder.create();
  }

  @Override
  public void onAdded() {
  }

  @Override
  public void onSend(MasterSecret masterSecret) throws NoSuchMessageException {
    EncryptingSmsDatabase database = DatabaseFactory.getEncryptingSmsDatabase(context);
    SmsMessageRecord record = database.getMessage(masterSecret, messageId);

    try {
      Log.w(TAG, "Sending message: " + messageId);

      deliver(masterSecret, record);
    } catch (UndeliverableMessageException ude) {
      Log.w(TAG, ude);
      DatabaseFactory.getSmsDatabase(context).markAsSentFailed(record.getId());
      MessageNotifier.notifyMessageDeliveryFailed(context, record.getRecipients(), record.getThreadId());
    } catch (UntrustedIdentityException uid) {
      Log.w(TAG, uid);
      DatabaseFactory.getSmsDatabase(context).markAsNoSession(record.getId());
      MessageNotifier.notifyMessageDeliveryFailed(context, record.getRecipients(), record.getThreadId());
    }
  }

  @Override
  public boolean onShouldRetryThrowable(Exception throwable) {
    return false;
  }

  @Override
  public void onCanceled() {
    Log.w(TAG, "onCanceled()");
    long threadId = DatabaseFactory.getSmsDatabase(context).getThreadIdForMessage(messageId);
    Recipients recipients = DatabaseFactory.getThreadDatabase(context).getRecipientsForThreadId(threadId);

    DatabaseFactory.getSmsDatabase(context).markAsSentFailed(messageId);
    if (threadId != -1 && recipients != null) {
      MessageNotifier.notifyMessageDeliveryFailed(context, recipients, threadId);
    }
  }

  private void deliver(MasterSecret masterSecret, SmsMessageRecord message)
          throws UndeliverableMessageException, UntrustedIdentityException {
    String recipient = message.getIndividualRecipient().getNumber();
    ArrayList<String> messages;

    // See issue #1516 for bug report, and discussion on commits related to #4833 for problems
    // related to the original fix to #1516. This still may not be a correct fix if networks allow
    // SMS sending to alphanumeric recipients other than email addresses, but should also
    // help to fix issue #3099.
    if (!NumberUtil.isValidEmail(recipient)) {
      recipient = PhoneNumberUtils.stripSeparators(PhoneNumberUtils.convertKeypadLettersToDigits(recipient));
    }

    if (!NumberUtil.isValidSmsOrEmail(recipient)) {
      throw new UndeliverableMessageException("Not a valid SMS destination! " + recipient);
    }

    if (message.isSecure() || message.isKeyExchange() || message.isEndSession()) {
      MultipartSmsMessageHandler multipartMessageHandler = new MultipartSmsMessageHandler();
      OutgoingTextMessage transportMessage = OutgoingTextMessage.from(message);

      if (!message.isKeyExchange()) {
        transportMessage = getAsymmetricEncrypt(masterSecret, transportMessage);
      }

      messages = SmsManager.getDefault().divideMessage(multipartMessageHandler.getEncodedMessage(transportMessage));
    } else {
      messages = SmsManager.getDefault().divideMessage(message.getBody().getBody());
    }

    ArrayList<PendingIntent> sentIntents = constructSentIntents(message.getId(), message.getType(), messages, message.isSecure());
    ArrayList<PendingIntent> deliveredIntents = constructDeliveredIntents(message.getId(), message.getType(), messages);

    int deviceSubscriptionId = DualSimUtil.getSubscriptionIdFromAppSubscriptionId(context, message.getSubscriptionId());

    // NOTE 11/04/14 -- There's apparently a bug where for some unknown recipients
    // and messages, this will throw an NPE.  We have no idea why, so we're just
    // catching it and marking the message as a failure.  That way at least it doesn't
    // repeatedly crash every time you start the app.
    try {
      getSmsManagerFor(deviceSubscriptionId).sendMultipartTextMessage(recipient, null, messages, sentIntents, deliveredIntents);
    } catch (NullPointerException npe) {
      Log.w(TAG, npe);
      Log.w(TAG, "Recipient: " + recipient);
      Log.w(TAG, "Message Parts: " + messages.size());
      throw new UndeliverableMessageException(npe);
    } catch (IllegalArgumentException | SecurityException iae) {
      Log.w(TAG, iae);
      throw new UndeliverableMessageException(iae);
    }
  }

  private OutgoingTextMessage getAsymmetricEncrypt(MasterSecret masterSecret,
                                                   OutgoingTextMessage message)
          throws UndeliverableMessageException, UntrustedIdentityException {
    try {
      return new SmsCipher(new SMSecureSignalProtocolStore(context, masterSecret, message.getSubscriptionId())).encrypt(message);
    } catch (NoSessionException e) {
      throw new UndeliverableMessageException(e);
    }
  }

  private ArrayList<PendingIntent> constructSentIntents(long messageId, long type,
                                                        ArrayList<String> messages, boolean secure) {
    ArrayList<PendingIntent> sentIntents = new ArrayList<>(messages.size());

    // Unique per message + part index to prevent PendingIntent collision.
    for (int i = 0; i < messages.size(); i++) {
      Intent intent = constructSentIntent(context, messageId, type, secure);
      intent.putExtra("part_index", i);
      intent.putExtra("parts_total", messages.size());

      sentIntents.add(PendingIntent.getBroadcast(
              context,
              buildRequestCode((int) messageId, /*sent=*/true, i),
              intent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
      ));
    }

    return sentIntents;
  }

  private ArrayList<PendingIntent> constructDeliveredIntents(long messageId, long type, ArrayList<String> messages) {
    if (!SMSecurePreferences.isSmsDeliveryReportsEnabled(context)) {
      return null;
    }

    ArrayList<PendingIntent> deliveredIntents = new ArrayList<>(messages.size());

    // Unique per message + part index to prevent PendingIntent collision.
    for (int i = 0; i < messages.size(); i++) {
      Intent intent = constructDeliveredIntent(context, messageId, type);
      intent.putExtra("part_index", i);
      intent.putExtra("parts_total", messages.size());

      deliveredIntents.add(PendingIntent.getBroadcast(
              context,
              buildRequestCode((int) messageId, /*sent=*/false, i),
              intent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
      ));
    }

    return deliveredIntents;
  }

  private Intent constructSentIntent(Context context, long messageId, long type, boolean secure) {
    Intent pending = new Intent(SmsDeliveryListener.SENT_SMS_ACTION,
            Uri.parse("custom://" + messageId + System.currentTimeMillis()),
            context, SmsDeliveryListener.class);

    pending.putExtra("type", type);
    pending.putExtra("message_id", messageId);
    pending.putExtra("secure", secure);

    return pending;
  }

  private Intent constructDeliveredIntent(Context context, long messageId, long type) {
    Intent pending = new Intent(SmsDeliveryListener.DELIVERED_SMS_ACTION,
            Uri.parse("custom://" + messageId + System.currentTimeMillis()),
            context, SmsDeliveryListener.class);
    pending.putExtra("type", type);
    pending.putExtra("message_id", messageId);

    return pending;
  }

  private SmsManager getSmsManagerFor(int subscriptionId) {
    Log.w(TAG, "getSmsManagerFor(" + subscriptionId + ")");

    if (subscriptionId != -1) {
      return SmsManager.getSmsManagerForSubscriptionId(subscriptionId);
    } else {
      return SmsManager.getDefault();
    }
  }
}
