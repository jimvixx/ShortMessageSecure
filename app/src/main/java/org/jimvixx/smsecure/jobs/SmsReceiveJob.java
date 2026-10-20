package org.jimvixx.smsecure.jobs;

import android.content.Context;
import android.telephony.SmsMessage;
import android.util.Pair;

import org.jimvixx.smsecure.ApplicationContext;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.MasterSecretUtil;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.EncryptingSmsDatabase;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.notifications.MessageNotifier;
import org.jimvixx.smsecure.protocol.WirePrefix;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.service.KeyCachingService;
import org.jimvixx.smsecure.sms.IncomingTextMessage;
import org.jimvixx.smsecure.sms.MultipartSmsMessageHandler;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.dualsim.DualSimUtil;
import org.jimvixx.smsecure.util.dualsim.SubscriptionInfoCompat;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.Serial;
import java.util.LinkedList;
import java.util.List;

public class SmsReceiveJob extends ContextJob {

  @Serial
  private static final long serialVersionUID = 1L;

  private static final String TAG = SmsReceiveJob.class.getSimpleName();
  private static final int INVALID_SUBSCRIPTION_ID = -1;

  private static final MultipartSmsMessageHandler multipartMessageHandler = new MultipartSmsMessageHandler();

  final Object[] pdus;
  final int subscriptionId;
  final Integer deviceSubscriptionId;
  final long mappingGeneration;
  final String format;

  public SmsReceiveJob(Context context, Object[] pdus, int subscriptionId) {
    this(context, pdus, subscriptionId, null);
  }

  public SmsReceiveJob(Context context, Object[] pdus, int subscriptionId, String format) {
    this(context, pdus,
            DualSimUtil.getSubscriptionIdFromDeviceSubscriptionId(context, subscriptionId),
            subscriptionId,
            SMSecurePreferences.getSubscriptionRecoveryGeneration(context),
            format);
  }

  SmsReceiveJob(Context context, Object[] pdus, int subscriptionId, boolean restored) {
    this(context, pdus, subscriptionId, null, 0, null);
  }

  SmsReceiveJob(Context context, Object[] pdus, int subscriptionId,
                Integer deviceSubscriptionId, long mappingGeneration) {
    this(context, pdus, subscriptionId, deviceSubscriptionId, mappingGeneration, null);
  }

  SmsReceiveJob(Context context, Object[] pdus, int subscriptionId,
                Integer deviceSubscriptionId, long mappingGeneration, String format) {
    super(context, JobParameters.newBuilder()
            .withPersistence()
            .withWakeLock(true)
            .create());

    this.pdus = pdus;
    this.subscriptionId = subscriptionId;
    this.deviceSubscriptionId = deviceSubscriptionId;
    this.mappingGeneration = mappingGeneration;
    this.format = format;
  }

  @Override
  public void onAdded() {
  }

  @Override
  public void onRun() throws Exception {
    MasterSecret masterSecret = KeyCachingService.getMasterSecret(context);
    int currentSubscriptionId = resolveCurrentSubscriptionId();
    Optional<IncomingTextMessage> rawMessage = assembleRawMessageFragments(pdus, currentSubscriptionId, masterSecret);

    if (!rawMessage.isPresent()) return;
    if (isBlocked(rawMessage.get())) {
      Log.w(TAG, "*** Received blocked SMS, ignoring...");
      return;
    }
    if (WirePrefix.isPrefixedMessage(rawMessage.get().getMessageBody())
            && currentSubscriptionId == INVALID_SUBSCRIPTION_ID) {
      throw new UnresolvedSubscriptionException();
    }

    Optional<IncomingTextMessage> message = processWireMessage(rawMessage.get());

    if (message.isPresent()) {
      Pair<Long, Long> messageAndThreadId = storeMessage(message.get());

      IncomingTextMessage incomingTextMessage = message.get();
      if (incomingTextMessage.isReceivedWhenLocked() ||
              (!incomingTextMessage.isSecureMessage() &&
                      !incomingTextMessage.isKeyExchange() &&
                      !incomingTextMessage.isXmppExchange())) {
        MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
      }

      if (incomingTextMessage.getSender() != null
              && incomingTextMessage.getSubscriptionId() != INVALID_SUBSCRIPTION_ID) {
        recordDefaultSubscriptionIfCurrent(incomingTextMessage);
      }
    }
  }

  @Override
  public void onCanceled() {

  }

  @Override
  public boolean onShouldRetry(Exception exception) {
    return exception instanceof UnresolvedSubscriptionException;
  }

  private boolean isBlocked(IncomingTextMessage message) {
    if (message.getSender() != null) {
      Recipients recipients = RecipientFactory.getRecipientsFromString(context, message.getSender(), false);
      return recipients.isBlocked();
    }

    return false;
  }

  private Pair<Long, Long> storeMessage(IncomingTextMessage message) {
    EncryptingSmsDatabase database = DatabaseFactory.getEncryptingSmsDatabase(context);
    MasterSecret masterSecret = KeyCachingService.getMasterSecret(context);

    Pair<Long, Long> messageAndThreadId;

    if (message.isSecureMessage()) {
      messageAndThreadId = database.insertMessageInbox((MasterSecret) null, message);
    } else if (masterSecret == null) {
      messageAndThreadId = database.insertMessageInbox(MasterSecretUtil.getAsymmetricMasterSecret(context, null), message);
    } else {
      messageAndThreadId = database.insertMessageInbox(masterSecret, message);
    }

    if (masterSecret == null || message.isSecureMessage() || message.isKeyExchange() || message.isEndSession() || message.isXmppExchange()) {
      ApplicationContext.getInstance(context)
              .getJobManager()
              .add(new SmsDecryptJob(context, messageAndThreadId.first, false,
                      masterSecret == null, deviceSubscriptionId, mappingGeneration));
    }

    return messageAndThreadId;
  }

  private int resolveCurrentSubscriptionId() {
    SubscriptionManagerCompat manager = SubscriptionManagerCompat.from(context);
    synchronized (manager) {
      if (deviceSubscriptionId == null) {
        if (mappingGeneration != SMSecurePreferences.getSubscriptionRecoveryGeneration(context)) {
          return INVALID_SUBSCRIPTION_ID;
        }
        return manager.getActiveSubscriptionInfo(subscriptionId).isPresent()
                ? subscriptionId : INVALID_SUBSCRIPTION_ID;
      }
      Optional<SubscriptionInfoCompat> current =
              manager.getActiveSubscriptionInfoFromDeviceSubscriptionId(deviceSubscriptionId);
      return current.isPresent() ? current.get().getSubscriptionId() : INVALID_SUBSCRIPTION_ID;
    }
  }

  private void recordDefaultSubscriptionIfCurrent(IncomingTextMessage message) {
    SubscriptionManagerCompat manager = SubscriptionManagerCompat.from(context);
    synchronized (manager) {
      int currentSubscriptionId;
      if (deviceSubscriptionId != null) {
        Optional<SubscriptionInfoCompat> current =
                manager.getActiveSubscriptionInfoFromDeviceSubscriptionId(deviceSubscriptionId);
        currentSubscriptionId = current.isPresent()
                ? current.get().getSubscriptionId() : INVALID_SUBSCRIPTION_ID;
      } else {
        boolean currentGeneration = mappingGeneration
                == SMSecurePreferences.getSubscriptionRecoveryGeneration(context);
        currentSubscriptionId = currentGeneration
                && manager.getActiveSubscriptionInfo(message.getSubscriptionId()).isPresent()
                ? message.getSubscriptionId() : INVALID_SUBSCRIPTION_ID;
      }
      if (currentSubscriptionId != message.getSubscriptionId()) return;

      Recipients recipients = RecipientFactory.getRecipientsFromString(context, message.getSender(), false);
      DatabaseFactory.getRecipientPreferenceDatabase(context)
              .setDefaultSubscriptionId(recipients, currentSubscriptionId);
    }
  }

  private Optional<IncomingTextMessage> assembleRawMessageFragments(Object[] pdus, int subscriptionId,
                                                                    MasterSecret masterSecret) {
    List<IncomingTextMessage> messages = new LinkedList<>();

    for (Object pdu : pdus) {
      SmsMessage msg = createSmsMessage((byte[]) pdu);
      if (msg != null) {
        messages.add(new IncomingTextMessage(msg, subscriptionId, masterSecret == null));
      }
    }

    if (messages.isEmpty()) {
      return Optional.absent();
    }

    return Optional.of(new IncomingTextMessage(messages));
  }

  private Optional<IncomingTextMessage> processWireMessage(IncomingTextMessage message) {
    if (WirePrefix.isPrefixedMessage(message.getMessageBody())) {
      return Optional.fromNullable(multipartMessageHandler.processPotentialMultipartMessage(message));
    } else {
      return Optional.of(message);
    }
  }

  @SuppressWarnings("deprecation")
  SmsMessage createSmsMessage(byte[] pdu) {
    return format != null ? SmsMessage.createFromPdu(pdu, format) : SmsMessage.createFromPdu(pdu);
  }

  static final class UnresolvedSubscriptionException extends Exception {
  }
}
