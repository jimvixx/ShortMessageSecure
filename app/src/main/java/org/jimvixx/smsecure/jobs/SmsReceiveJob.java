package org.jimvixx.smsecure.jobs;

import android.content.Context;
import android.telephony.SmsMessage;
import org.jimvixx.smsecure.logging.Log;
import android.util.Pair;

import org.jimvixx.smsecure.ApplicationContext;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.MasterSecretUtil;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.EncryptingSmsDatabase;
import org.jimvixx.smsecure.notifications.MessageNotifier;
import org.jimvixx.smsecure.protocol.WirePrefix;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.service.KeyCachingService;
import org.jimvixx.smsecure.sms.IncomingTextMessage;
import org.jimvixx.smsecure.sms.MultipartSmsMessageHandler;
import org.jimvixx.smsecure.util.dualsim.DualSimUtil;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.Serial;
import java.util.LinkedList;
import java.util.List;

public class SmsReceiveJob extends ContextJob {

  @Serial
  private static final long serialVersionUID = 1L;

  private static final String TAG = SmsReceiveJob.class.getSimpleName();

  private static final MultipartSmsMessageHandler multipartMessageHandler = new MultipartSmsMessageHandler();

  private final Object[] pdus;
  private final int      subscriptionId;

  public SmsReceiveJob(Context context, Object[] pdus, int subscriptionId) {
    super(context, JobParameters.newBuilder()
                                .withPersistence()
                                .withWakeLock(true)
                                .create());

    Log.w(TAG, "subscriptionId: " + subscriptionId);
    Log.w(TAG, "Found app subscription ID: " + DualSimUtil.getSubscriptionIdFromDeviceSubscriptionId(context, subscriptionId));

    this.pdus           = pdus;
    this.subscriptionId = DualSimUtil.getSubscriptionIdFromDeviceSubscriptionId(context, subscriptionId);
  }

  @Override
  public void onAdded() {}

  @Override
  public void onRun() {
    MasterSecret masterSecret = KeyCachingService.getMasterSecret(context);
    Optional<IncomingTextMessage> message = assembleMessageFragments(pdus, subscriptionId, masterSecret);

    if (message.isPresent() && !isBlocked(message.get())) {
      Pair<Long, Long> messageAndThreadId = storeMessage(message.get());

      IncomingTextMessage incomingTextMessage = message.get();
      if (incomingTextMessage.isReceivedWhenLocked() ||
         (!incomingTextMessage.isSecureMessage()     &&
          !incomingTextMessage.isKeyExchange()       &&
          !incomingTextMessage.isXmppExchange()))
      {
        MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
      }

      if (incomingTextMessage.getSender() != null) {
        Recipients recipients = RecipientFactory.getRecipientsFromString(context, incomingTextMessage.getSender(), false);
        DatabaseFactory.getRecipientPreferenceDatabase(context)
                       .setDefaultSubscriptionId(recipients, incomingTextMessage.getSubscriptionId());
      }
    } else if (message.isPresent()) {
      Log.w(TAG, "*** Received blocked SMS, ignoring...");
    }
  }

  @Override
  public void onCanceled() {

  }

  @Override
  public boolean onShouldRetry(Exception exception) {
    return false;
  }

  private boolean isBlocked(IncomingTextMessage message) {
    if (message.getSender() != null) {
      Recipients recipients = RecipientFactory.getRecipientsFromString(context, message.getSender(), false);
      return recipients.isBlocked();
    }

    return false;
  }

  private Pair<Long, Long> storeMessage(IncomingTextMessage message) {
    EncryptingSmsDatabase database     = DatabaseFactory.getEncryptingSmsDatabase(context);
    MasterSecret          masterSecret = KeyCachingService.getMasterSecret(context);

    Pair<Long, Long> messageAndThreadId;

    if (message.isSecureMessage()) {
      messageAndThreadId = database.insertMessageInbox((MasterSecret)null, message);
    } else if (masterSecret == null) {
      messageAndThreadId = database.insertMessageInbox(MasterSecretUtil.getAsymmetricMasterSecret(context, null), message);
    } else {
      messageAndThreadId = database.insertMessageInbox(masterSecret, message);
    }

    if (masterSecret == null || message.isSecureMessage() || message.isKeyExchange() || message.isEndSession() || message.isXmppExchange()) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new SmsDecryptJob(context, messageAndThreadId.first, masterSecret == null));
    }

    return messageAndThreadId;
  }

  private Optional<IncomingTextMessage> assembleMessageFragments(Object[] pdus, int subscriptionId, MasterSecret masterSecret) {
    List<IncomingTextMessage> messages = new LinkedList<>();

    for (Object pdu : pdus) {
      SmsMessage msg = SmsMessage.createFromPdu((byte[]) pdu);
      if (msg != null){
        messages.add(new IncomingTextMessage(msg, subscriptionId, masterSecret == null));
      }
    }

    if (messages.isEmpty()) {
      return Optional.absent();
    }

    IncomingTextMessage message = new IncomingTextMessage(messages);

    if (WirePrefix.isPrefixedMessage(message.getMessageBody())) {
      return Optional.fromNullable(multipartMessageHandler.processPotentialMultipartMessage(message));
    } else {
      return Optional.of(message);
    }
  }
}
