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

import android.app.Activity;
import android.content.Context;
import android.telephony.SmsManager;
import org.jimvixx.smsecure.logging.Log;

import org.jimvixx.smsecure.ApplicationContext;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.SecurityEvent;
import org.jimvixx.smsecure.crypto.storage.SMSecureSessionStore;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.EncryptingSmsDatabase;
import org.jimvixx.smsecure.database.NoSuchMessageException;
import org.jimvixx.smsecure.database.model.SmsMessageRecord;
import org.jimvixx.smsecure.jobs.requirements.MasterSecretRequirement;
import org.jimvixx.smsecure.notifications.MessageNotifier;
import org.jimvixx.smsecure.service.SmsDeliveryListener;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.libsignal.state.SessionStore;

public class SmsSentJob extends MasterSecretJob {

  private static final String TAG = SmsSentJob.class.getSimpleName();

  /**
   * If a "DELIVERED" callback arrives too quickly after send time, it is often a false positive
   * (device/vendor/modem fires it without a real delivery receipt from the network).
   *
   * Tune this value based on your field tests.
   */
  private static final long MIN_DELIVERY_DELAY_MS = 1800L;

  /**
   * Safety bound: ignore absurdly late delivery callbacks (clock issues, stale intents, etc.).
   * This does NOT need to be strict, it's just to avoid nonsense.
   */
  private static final long MAX_DELIVERY_DELAY_MS = 7L * 24L * 60L * 60L * 1000L; // 7 days

  private final long   messageId;
  private final String action;
  private final int    result;

  public SmsSentJob(Context context, long messageId, String action, int result) {
    super(context, JobParameters.newBuilder()
            .withPersistence()
            .withRequirement(new MasterSecretRequirement(context))
            .create());

    this.messageId = messageId;
    this.action    = action;
    this.result    = result;
  }

  @Override
  public void onAdded() { }

  @Override
  public void onRun(MasterSecret masterSecret) {
    Log.w(TAG, "Got SMS callback: " + action + " , " + result);

    switch (action) {
      case SmsDeliveryListener.SENT_SMS_ACTION:
        handleSentResult(masterSecret, messageId, result);
        break;
      case SmsDeliveryListener.DELIVERED_SMS_ACTION:
        handleDeliveredResult(masterSecret, messageId, result);
        break;
      default:
        Log.w(TAG, "Unknown action in job: " + action);
    }
  }

  @Override
  public boolean onShouldRetryThrowable(Exception throwable) {
    return false;
  }

  @Override
  public void onCanceled() { }

  private void handleDeliveredResult(MasterSecret masterSecret, long messageId, int result) {
    try {
      EncryptingSmsDatabase database = DatabaseFactory.getEncryptingSmsDatabase(context);
      SmsMessageRecord record = database.getMessage(masterSecret, messageId);

      final long now    = System.currentTimeMillis();
      final long sentAt = record.getDateSent();
      final long delta  = now - sentAt;

      Log.w(TAG, "DELIVERED handle: msgId=" + messageId +
              " result=" + result +
              " sentAt=" + sentAt +
              " now=" + now +
              " deltaMs=" + delta +
              " deliveredAt(old)=" + record.getDateDeliveryReceived() +
              " isDelivered(old)=" + record.isDelivered());

      // If it's already marked delivered, ignore duplicates (some devices fire multiple times).
      if (record.isDelivered()) {
        Log.w(TAG, "DELIVERED ignored: already delivered. msgId=" + messageId);
        return;
      }

      if (result != Activity.RESULT_OK) {
        Log.w(TAG, "DELIVERED ignored: result not OK. msgId=" + messageId + " result=" + result);
        return;
      }

      // Time-based heuristic: ignore "delivery" callbacks that arrive unrealistically fast.
      if (delta < MIN_DELIVERY_DELAY_MS) {
        Log.w(TAG, "DELIVERED ignored: too fast (likely false positive). msgId=" + messageId +
                " deltaMs=" + delta + " < " + MIN_DELIVERY_DELAY_MS);
        return;
      }

      // Safety bound for extreme cases.
      if (delta > MAX_DELIVERY_DELAY_MS) {
        Log.w(TAG, "DELIVERED ignored: too late (likely stale/clock issue). msgId=" + messageId +
                " deltaMs=" + delta + " > " + MAX_DELIVERY_DELAY_MS);
        return;
      }

      // At this point we accept "delivered" without PDU, based only on the time heuristic.
      String recipientName = (record.getIndividualRecipient().getName() == null
              ? record.getIndividualRecipient().getNumber()
              : record.getIndividualRecipient().getName());

      if (SMSecurePreferences.isSmsDeliveryReportsToastEnabled(context)) {
        MessageNotifier.sendDeliveryToast(context, recipientName);
      }

      database.markAsDelivered(messageId);

      SmsMessageRecord after = database.getMessage(masterSecret, messageId);
      Log.w(TAG, "DELIVERED accepted: deliveredAt=" + after.getDateDeliveryReceived() +
              " isDelivered=" + after.isDelivered() +
              " deltaMs=" + delta);

    } catch (NoSuchMessageException e) {
      Log.w(TAG, "DELIVERED: no such message: " + messageId, e);
    } catch (Exception e) {
      Log.w(TAG, "DELIVERED: unexpected error for msgId=" + messageId, e);
    }
  }

  private void handleSentResult(MasterSecret masterSecret, long messageId, int result) {
    try {
      EncryptingSmsDatabase database = DatabaseFactory.getEncryptingSmsDatabase(context);
      SmsMessageRecord      record   = database.getMessage(masterSecret, messageId);

      switch (result) {
        case Activity.RESULT_OK:
          database.markAsSent(messageId, record.isSecure());

          if (record.isEndSession()) {
            Log.w(TAG, "Ending session...");
            SessionStore sessionStore = new SMSecureSessionStore(context, masterSecret, record.getSubscriptionId());
            sessionStore.deleteAllSessions(record.getIndividualRecipient().getNumber());
            SecurityEvent.broadcastSecurityUpdateEvent(context, record.getThreadId());
          }
          break;

        case SmsManager.RESULT_ERROR_NO_SERVICE:
        case SmsManager.RESULT_ERROR_RADIO_OFF:
          Log.w(TAG, "Service connectivity problem, requeuing...");
          ApplicationContext.getInstance(context)
                  .getJobManager()
                  .add(new SmsSendJob(context, messageId, record.getIndividualRecipient().getNumber()));
          break;

        default:
          database.markAsSentFailed(messageId);
          MessageNotifier.notifyMessageDeliveryFailed(context, record.getRecipients(), record.getThreadId());
      }
    } catch (NoSuchMessageException e) {
      Log.w(TAG, "SENT: no such message: " + messageId, e);
    }
  }
}