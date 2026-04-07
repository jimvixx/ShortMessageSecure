/*
 * Copyright (C) 2015 Whisper Systems
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

import android.content.Context;
import org.jimvixx.smsecure.logging.Log;

import org.jimvixx.smsecure.crypto.AsymmetricMasterCipher;
import org.jimvixx.smsecure.crypto.AsymmetricMasterSecret;
import org.jimvixx.smsecure.crypto.KeyExchangeInitiator;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.MasterSecretUtil;
import org.jimvixx.smsecure.crypto.SecurityEvent;
import org.jimvixx.smsecure.crypto.SmsCipher;
import org.jimvixx.smsecure.crypto.storage.SMSecureSignalProtocolStore;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.EncryptingSmsDatabase;
import org.jimvixx.smsecure.database.NoSuchMessageException;
import org.jimvixx.smsecure.database.model.SmsMessageRecord;
import org.jimvixx.smsecure.jobs.requirements.MasterSecretRequirement;
import org.jimvixx.smsecure.notifications.MessageNotifier;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.sms.IncomingEncryptedMessage;
import org.jimvixx.smsecure.sms.IncomingEndSessionMessage;
import org.jimvixx.smsecure.sms.IncomingKeyExchangeMessage;
import org.jimvixx.smsecure.sms.IncomingPreKeyBundleMessage;
import org.jimvixx.smsecure.sms.IncomingTextMessage;
import org.jimvixx.smsecure.sms.IncomingXmppExchangeMessage;
import org.jimvixx.smsecure.sms.MessageSender;
import org.jimvixx.smsecure.sms.OutgoingKeyExchangeMessage;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.StaleKeyExchangeException;
import org.whispersystems.libsignal.UntrustedIdentityException;

import java.io.IOException;

public class SmsDecryptJob extends MasterSecretJob {

  private static final String TAG = SmsDecryptJob.class.getSimpleName();

  private final long    messageId;
  private final boolean manualOverride;
  private final Boolean isReceivedWhenLocked;

  public SmsDecryptJob(Context context, long messageId, boolean manualOverride, boolean isReceivedWhenLocked) {
    super(context, JobParameters.newBuilder()
            .withPersistence()
            .withRequirement(new MasterSecretRequirement(context))
            .create());

    this.messageId            = messageId;
    this.manualOverride       = manualOverride;
    this.isReceivedWhenLocked = isReceivedWhenLocked;

    Log.w(TAG, "manualOverride: " + manualOverride);
    Log.w(TAG, "isReceivedWhenLocked: " + isReceivedWhenLocked);
  }

  public SmsDecryptJob(Context context, long messageId) {
    this(context, messageId, false, false);
  }

  public SmsDecryptJob(Context context, long messageId, boolean isReceivedWhenLocked) {
    this(context, messageId, false, isReceivedWhenLocked);
  }

  @Override
  public void onAdded() {}

  @Override
  public void onRun(MasterSecret masterSecret) throws NoSuchMessageException {
    EncryptingSmsDatabase database = DatabaseFactory.getEncryptingSmsDatabase(context);

    try {
      SmsMessageRecord    record    = database.getMessage(masterSecret, messageId);
      IncomingTextMessage message   = createIncomingTextMessage(masterSecret, record);
      long                messageId = record.getId();
      long                threadId  = record.getThreadId();

      if      (message.isSecureMessage()) handleSecureMessage(masterSecret, messageId, threadId, message);
      else if (message.isPreKeyBundle())  handlePreKeySignalMessage(masterSecret, messageId, threadId, (IncomingPreKeyBundleMessage) message);
      else if (message.isKeyExchange())   handleKeyExchangeMessage(masterSecret, messageId, threadId, (IncomingKeyExchangeMessage) message);
      else if (message.isEndSession())    handleSecureMessage(masterSecret, messageId, threadId, message);
      else if (message.isXmppExchange())  handleXmppExchangeMessage(masterSecret, messageId, threadId, (IncomingXmppExchangeMessage) message);
      else                                database.updateMessageBody(masterSecret, messageId, message.getMessageBody());

      if (!isReceivedWhenLocked) {
        MessageNotifier.updateNotification(context, masterSecret, threadId);
      } else {
        MessageNotifier.updateNotification(context, masterSecret);
      }
    } catch (LegacyMessageException e) {
      Log.w(TAG, e);
      database.markAsLegacyVersion(messageId);
    } catch (InvalidMessageException e) {
      Log.w(TAG, e);
      database.markAsDecryptFailed(messageId);
    } catch (DuplicateMessageException e) {
      Log.w(TAG, e);
      database.markAsDecryptDuplicate(messageId);
    } catch (NoSessionException | UntrustedIdentityException e) {
      Log.w(TAG, e);
      database.markAsNoSession(messageId);
    }
  }

  @Override
  public boolean onShouldRetryThrowable(Exception exception) {
    return false;
  }

  @Override
  public void onCanceled() {
    // TODO
  }

  private void handleSecureMessage(MasterSecret masterSecret, long messageId, long threadId,
                                   IncomingTextMessage message)
          throws NoSessionException, DuplicateMessageException,
          InvalidMessageException, LegacyMessageException,
          UntrustedIdentityException
  {
    EncryptingSmsDatabase database  = DatabaseFactory.getEncryptingSmsDatabase(context);
    SmsCipher             cipher    = new SmsCipher(new SMSecureSignalProtocolStore(context, masterSecret, message.getSubscriptionId()));
    IncomingTextMessage   plaintext = cipher.decrypt(context, message);

    database.updateMessageBody(masterSecret, messageId, plaintext.getMessageBody());

    if (message.isEndSession()) SecurityEvent.broadcastSecurityUpdateEvent(context, threadId);
  }

  private void handlePreKeySignalMessage(MasterSecret masterSecret, long messageId, long threadId,
                                         IncomingPreKeyBundleMessage message)
          throws NoSessionException, DuplicateMessageException,
          InvalidMessageException, LegacyMessageException
  {
    EncryptingSmsDatabase database = DatabaseFactory.getEncryptingSmsDatabase(context);

    try {
      SmsCipher                smsCipher = new SmsCipher(new SMSecureSignalProtocolStore(context, masterSecret, message.getSubscriptionId()));
      IncomingEncryptedMessage plaintext = smsCipher.decrypt(context, message);

      database.updateBundleMessageBody(masterSecret, messageId, plaintext.getMessageBody());

      SecurityEvent.broadcastSecurityUpdateEvent(context, threadId);
    } catch (InvalidVersionException e) {
      Log.w(TAG, e);
      database.markAsInvalidVersionKeyExchange(messageId);
    } catch (UntrustedIdentityException e) {
      Log.w(TAG, e);
    }
  }

  private void handleKeyExchangeMessage(MasterSecret masterSecret, long messageId, long threadId,
                                        IncomingKeyExchangeMessage message)
  {
    EncryptingSmsDatabase database = DatabaseFactory.getEncryptingSmsDatabase(context);

    try {
      SmsCipher                  cipher   = new SmsCipher(new SMSecureSignalProtocolStore(context, masterSecret, message.getSubscriptionId()));
      OutgoingKeyExchangeMessage response = cipher.process(context, message);

      if (shouldSend()) {
        database.markAsProcessedKeyExchange(messageId);
        SecurityEvent.broadcastSecurityUpdateEvent(context, threadId);

        if (response != null) {
          MessageSender.send(context, masterSecret, response, threadId, true);
        }
      }
    } catch (InvalidVersionException e) {
      Log.w(TAG, e);
      database.markAsInvalidVersionKeyExchange(messageId);
    } catch (InvalidMessageException e) {
      Log.w(TAG, e);
      database.markAsCorruptKeyExchange(messageId);
    } catch (LegacyMessageException e) {
      Log.w(TAG, e);
      database.markAsLegacyVersion(messageId);
      if (shouldSend()) {
        Log.w(TAG, "Legacy message found, sending updated key exchange message...");
        Recipients recipients = RecipientFactory.getRecipientsFromString(context, message.getSender(), false);
        KeyExchangeInitiator.initiate(context, masterSecret, recipients, false, message.getSubscriptionId());
        database.markAsProcessedKeyExchange(messageId);
      }
    } catch (StaleKeyExchangeException e) {
      Log.w(TAG, e);
      database.markAsStaleKeyExchange(messageId);
    } catch (UntrustedIdentityException e) {
      Log.w(TAG, e);
    } catch (IllegalArgumentException e) {
      /*
       * Defensive catch: libsignal builders can throw runtime IllegalArgumentException
       * (e.g., "Null values!") if local session/pending state is corrupt.
       * Never crash the JobConsumer thread.
       */
      Log.w(TAG, "KeyExchange processing failed due to corrupt local state.", e);
      database.markAsCorruptKeyExchange(messageId);
    }
  }

  private boolean shouldSend() {
    return (SMSecurePreferences.isAutoRespondKeyExchangeEnabled(context) || manualOverride);
  }

  private void handleXmppExchangeMessage(MasterSecret masterSecret, long messageId, long threadId,
                                         IncomingXmppExchangeMessage message)
          throws NoSessionException, DuplicateMessageException, InvalidMessageException, LegacyMessageException
  {
    EncryptingSmsDatabase database = DatabaseFactory.getEncryptingSmsDatabase(context);
    database.markAsXmppExchange(messageId);
  }

  private String getAsymmetricDecryptedBody(MasterSecret masterSecret, String body, int subscriptionId)
          throws InvalidMessageException
  {
    try {
      AsymmetricMasterSecret asymmetricMasterSecret = MasterSecretUtil.getAsymmetricMasterSecret(context, masterSecret);
      AsymmetricMasterCipher asymmetricMasterCipher = new AsymmetricMasterCipher(asymmetricMasterSecret);

      return asymmetricMasterCipher.decryptBody(body);
    } catch (IOException e) {
      throw new InvalidMessageException(e);
    }
  }

  private IncomingTextMessage createIncomingTextMessage(MasterSecret masterSecret, SmsMessageRecord record)
          throws InvalidMessageException
  {
    String plaintextBody = record.getBody().getBody();

    if (record.isAsymmetricEncryption()) {
      plaintextBody = getAsymmetricDecryptedBody(masterSecret, record.getBody().getBody(), record.getSubscriptionId());
    }

    IncomingTextMessage message = new IncomingTextMessage(record.getRecipients().getPrimaryRecipient().getNumber(),
            record.getRecipientDeviceId(),
            record.getDateSent(),
            plaintextBody,
            record.getSubscriptionId());

    if (record.isEndSession()) {
      return new IncomingEndSessionMessage(message);
    } else if (record.isBundleKeyExchange()) {
      return new IncomingPreKeyBundleMessage(message, message.getMessageBody());
    } else if (record.isKeyExchange()) {
      return new IncomingKeyExchangeMessage(message, message.getMessageBody());
    } else if (record.isXmppExchange()) {
      return new IncomingXmppExchangeMessage(message, message.getMessageBody());
    } else if (record.isSecure()) {
      return new IncomingEncryptedMessage(message, message.getMessageBody());
    }

    return message;
  }
}
