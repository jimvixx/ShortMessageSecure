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
import android.os.Build;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;

import androidx.annotation.RequiresApi;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.SmsCipher;
import org.jimvixx.smsecure.crypto.storage.SMSecureSignalProtocolStore;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.EncryptingSmsDatabase;
import org.jimvixx.smsecure.database.NoSuchMessageException;
import org.jimvixx.smsecure.database.model.SmsMessageRecord;
import org.jimvixx.smsecure.jobs.requirements.MasterSecretRequirement;
import org.jimvixx.smsecure.jobs.requirements.ServiceRequirement;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.logging.SmsSendDiagnostics;
import org.jimvixx.smsecure.notifications.MessageNotifier;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.service.SmsDeliveryListener;
import org.jimvixx.smsecure.service.SmsSendAttemptTracker;
import org.jimvixx.smsecure.sms.MultipartSmsMessageHandler;
import org.jimvixx.smsecure.sms.OutgoingTextMessage;
import org.jimvixx.smsecure.transport.UndeliverableMessageException;
import org.jimvixx.smsecure.util.NumberUtil;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.dualsim.DualSimUtil;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.UntrustedIdentityException;

import java.io.Serial;
import java.util.ArrayList;
import java.util.UUID;

public class SmsSendJob extends SendJob {

  // Preserve compatibility with legacy Java-serialized jobs when adding recovery state.
  @Serial
  private static final long serialVersionUID = -1264261020918178580L;

  private static final String TAG = SmsSendJob.class.getSimpleName();

  final long messageId;
  final String sendAttemptId;
  final long mappingGeneration;
  final long retryNotBeforeMillis;

  public SmsSendJob(Context context, long messageId, String name) {
    this(context, messageId, name, SMSecurePreferences.getSubscriptionRecoveryGeneration(context));
  }

  public SmsSendJob(Context context, long messageId, String name, long mappingGeneration) {
    this(context, messageId, name, UUID.randomUUID().toString(), mappingGeneration, 0);
  }

  public SmsSendJob(Context context, long messageId, String name, long mappingGeneration,
                    long retryNotBeforeMillis) {
    this(context, messageId, name, UUID.randomUUID().toString(), mappingGeneration, retryNotBeforeMillis);
  }

  SmsSendJob(Context context, long messageId, String name, String sendAttemptId) {
    this(context, messageId, name, sendAttemptId, 0);
  }

  SmsSendJob(Context context, long messageId, String name, String sendAttemptId, long mappingGeneration) {
    this(context, messageId, name, sendAttemptId, mappingGeneration, 0);
  }

  SmsSendJob(Context context, long messageId, String name, String sendAttemptId,
             long mappingGeneration, long retryNotBeforeMillis) {
    super(context, constructParameters(context, name, retryNotBeforeMillis));
    this.messageId = messageId;
    this.sendAttemptId = sendAttemptId;
    this.mappingGeneration = mappingGeneration;
    this.retryNotBeforeMillis = retryNotBeforeMillis;
  }

  /**
   * Builds a stable unique requestCode for PendingIntents.
   *
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

  private static JobParameters constructParameters(Context context, String name, long retryNotBeforeMillis) {
    JobParameters.Builder builder = JobParameters.newBuilder()
            .withPersistence()
            .withRequirement(new MasterSecretRequirement(context))
            .withRequirement(new ServiceRequirement(context, retryNotBeforeMillis))
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

      // Recovery cannot change the mapping between validation and handing the SMS to Android.
      synchronized (SubscriptionManagerCompat.from(context)) {
        deliver(masterSecret, record);
      }
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
    if (mappingGeneration != SMSecurePreferences.getSubscriptionRecoveryGeneration(context)) {
      throw new UndeliverableMessageException("SIM recovery requires manual retry of queued SMS");
    }
    int deviceSubscriptionId = DualSimUtil.getSubscriptionIdFromAppSubscriptionId(context, message.getSubscriptionId());
    // Validate the selected SIM before encryption can advance a session or attempts are recorded.
    // Only the legacy unspecified app ID may use the system default manager.
    if (message.getSubscriptionId() != -1 && deviceSubscriptionId < 0) {
      SmsSendDiagnostics.logRouteBlocked(context, message);
      throw new UndeliverableMessageException("Selected SMS subscription cannot be resolved safely");
    }

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

      messages = getDefaultSmsManager().divideMessage(multipartMessageHandler.getEncodedMessage(transportMessage));
    } else {
      messages = getDefaultSmsManager().divideMessage(message.getBody().getBody());
    }

    ArrayList<PendingIntent> sentIntents = constructSentIntents(message.getId(), message.getType(), messages, message.isSecure());
    ArrayList<PendingIntent> deliveredIntents = constructDeliveredIntents(message.getId(), message.getType(), messages);

    // NOTE 11/04/14 -- There's apparently a bug where for some unknown recipients
    // and messages, this will throw an NPE.  We have no idea why, so we're just
    // catching it and marking the message as a failure.  That way at least it doesn't
    // repeatedly crash every time you start the app.
    SmsSendAttemptTracker.startAttempt(context, message.getId(), sendAttemptId, messages.size());
    if (deliveredIntents != null) {
      SmsDeliveryTracker.start(context, message.getId(), sendAttemptId, message.getDateSent(), messages.size());
    }

    try {
      SmsManager smsManager = getSmsManagerFor(deviceSubscriptionId);
      SmsSendDiagnostics.logSend(context, message, deviceSubscriptionId, smsManager,
              messages.size(), deliveredIntents != null);
      smsManager.sendMultipartTextMessage(recipient, null, messages, sentIntents, deliveredIntents);
    } catch (NullPointerException npe) {
      SmsSendAttemptTracker.cancelAttempt(context, message.getId(), sendAttemptId);
      Log.w(TAG, npe);
      Log.w(TAG, "Message Parts: " + messages.size());
      throw new UndeliverableMessageException(npe);
    } catch (IllegalArgumentException | SecurityException iae) {
      SmsSendAttemptTracker.cancelAttempt(context, message.getId(), sendAttemptId);
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
      intent.putExtra("send_attempt_id", sendAttemptId);
      intent.putExtra("mapping_generation", mappingGeneration);

      sentIntents.add(PendingIntent.getBroadcast(
              context,
              buildRequestCode((int) messageId, /*sent=*/true, i),
              intent,
              // Telephony fills in errorCode/noDefault; keep the receiver explicit.
              callbackPendingIntentFlags(android.os.Build.VERSION.SDK_INT)
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
              // Telephony supplies the status-report PDU through fill-in extras.
              // The intent has an explicit receiver and a unique attempt URI.
              callbackPendingIntentFlags(android.os.Build.VERSION.SDK_INT)
      ));
    }

    return deliveredIntents;
  }

  static int callbackPendingIntentFlags(int sdkInt) {
    return PendingIntent.FLAG_UPDATE_CURRENT |
            (sdkInt >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
  }

  private Intent constructSentIntent(Context context, long messageId, long type, boolean secure) {
    Intent pending = new Intent(SmsDeliveryListener.SENT_SMS_ACTION,
            Uri.parse("custom://sent/" + messageId + "/" + sendAttemptId),
            context, SmsDeliveryListener.class);

    pending.putExtra("type", type);
    pending.putExtra("message_id", messageId);
    pending.putExtra("secure", secure);

    return pending;
  }

  private Intent constructDeliveredIntent(Context context, long messageId, long type) {
    Intent pending = new Intent(SmsDeliveryListener.DELIVERED_SMS_ACTION,
            Uri.parse("custom://delivered/" + messageId + "/" + sendAttemptId),
            context, SmsDeliveryListener.class);
    pending.putExtra("type", type);
    pending.putExtra("message_id", messageId);

    pending.putExtra("delivery_attempt", sendAttemptId);

    return pending;
  }

  private SmsManager getSmsManagerFor(int subscriptionId) {
    Log.w(TAG, "getSmsManagerFor(" + subscriptionId + ")");
    return getSmsManagerFor(context, subscriptionId);
  }

  private SmsManager getDefaultSmsManager() {
    return getSmsManagerFor(context, -1);
  }

  static SmsManager getSmsManagerFor(Context context, int subscriptionId) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      return getModernSmsManagerFor(context, subscriptionId);
    }
    return getLegacySmsManagerFor(subscriptionId);
  }

  @RequiresApi(Build.VERSION_CODES.S)
  static SmsManager getModernSmsManagerFor(Context context, int subscriptionId) {
    SmsManager manager = context.getSystemService(SmsManager.class);
    if (manager == null) throw new IllegalStateException("SmsManager unavailable");
    return subscriptionId == -1 ? manager : manager.createForSubscriptionId(subscriptionId);
  }

  @SuppressWarnings("deprecation")
  private static SmsManager getLegacySmsManagerFor(int subscriptionId) {
    return subscriptionId == -1
            ? SmsManager.getDefault()
            : SmsManager.getSmsManagerForSubscriptionId(subscriptionId);
  }
}
