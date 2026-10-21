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

package org.jimvixx.smsecure.jobs;

import android.app.Activity;
import android.content.Context;
import android.telephony.SmsManager;
import org.jimvixx.smsecure.ApplicationContext;
import org.jimvixx.smsecure.crypto.storage.SMSecureSessionStore;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.EncryptingSmsDatabase;
import org.jimvixx.smsecure.database.SmsDatabase;
import org.jimvixx.smsecure.database.model.SmsMessageRecord;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.notifications.MessageNotifier;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.service.SmsDeliveryListener;
import org.jimvixx.smsecure.service.SmsSendAttemptTracker;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.whispersystems.jobqueue.JobManager;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SmsRecoveryQueueTest {
  @Test public void lateNoServiceCallbackCannotRefreshTheOldSendGeneration() throws Exception {
    assertOldRetryBlocked(SmsManager.RESULT_ERROR_NO_SERVICE);
  }

  @Test public void lateRadioOffCallbackCannotRefreshTheOldSendGeneration() throws Exception {
    assertOldRetryBlocked(SmsManager.RESULT_ERROR_RADIO_OFF);
  }

  private void assertOldRetryBlocked(int result) throws Exception {
    try (Fixture fixture = new Fixture()) {
      SmsSentJob callback = new SmsSentJob(fixture.context, 42, SmsDeliveryListener.SENT_SMS_ACTION, result, 0);
      callback.onRun(null);
      ArgumentCaptor<SmsSendJob> sent = ArgumentCaptor.forClass(SmsSendJob.class);
      verify(fixture.queue).add(sent.capture());
      assertEquals(0, sent.getValue().mappingGeneration);
      SmsSendJob restarted = (SmsSendJob) StableJobCodec.decode(fixture.context, StableJobCodec.encode(sent.getValue()));
      restarted.onSend(null);
      verify(fixture.smsDatabase).markAsSentFailed(42);
      fixture.managers.verifyNoInteractions();
      verify(fixture.record, never()).getBody();
    }
  }

  @Test public void lateSuccessfulEndSessionDoesNotDeleteSessionsAfterRecovery() {
    try (Fixture fixture = new Fixture();
         MockedConstruction<SMSecureSessionStore> sessions = mockConstruction(SMSecureSessionStore.class)) {
      when(fixture.record.isEndSession()).thenReturn(true);
      new SmsSentJob(fixture.context, 42, SmsDeliveryListener.SENT_SMS_ACTION, Activity.RESULT_OK, 0).onRun(null);
      verify(fixture.database).markAsSent(42, false);
      assertTrue(sessions.constructed().isEmpty());
      verifyNoInteractions(fixture.queue);
    }
  }

  @Test public void singlePartRilRetrySchedulesOneDelayedSend() {
    try (Fixture fixture = new Fixture()) {
      fixture.attempts.when(() -> SmsSendAttemptTracker.claimRilRetry(fixture.context, 42))
              .thenReturn(true);
      long now = System.currentTimeMillis();
      SmsSentJob callback = new SmsSentJob(fixture.context, 42, SmsDeliveryListener.SENT_SMS_ACTION,
              SmsDeliveryListener.RESULT_RIL_SMS_SEND_FAIL_RETRY, 7, true);

      callback.onRun(null);

      ArgumentCaptor<SmsSendJob> sent = ArgumentCaptor.forClass(SmsSendJob.class);
      verify(fixture.queue).add(sent.capture());
      assertEquals(7, sent.getValue().mappingGeneration);
      assertTrue(sent.getValue().retryNotBeforeMillis >= now + 4_900L);
      assertEquals(2, sent.getValue().getRequirements().size());
      verify(fixture.smsDatabase, never()).markAsSentFailed(42);
    }
  }

  @Test public void exhaustedRilRetryMarksMessageFailed() {
    try (Fixture fixture = new Fixture()) {
      fixture.attempts.when(() -> SmsSendAttemptTracker.claimRilRetry(fixture.context, 42))
              .thenReturn(false);

      new SmsSentJob(fixture.context, 42, SmsDeliveryListener.SENT_SMS_ACTION,
              SmsDeliveryListener.RESULT_RIL_SMS_SEND_FAIL_RETRY, 0, true).onRun(null);

      verify(fixture.database).markAsSentFailed(42);
      verifyNoInteractions(fixture.queue);
    }
  }

  @Test public void multipartRilFailureDoesNotRetryEntireMessage() {
    try (Fixture fixture = new Fixture()) {
      new SmsSentJob(fixture.context, 42, SmsDeliveryListener.SENT_SMS_ACTION,
              SmsDeliveryListener.RESULT_RIL_SMS_SEND_FAIL_RETRY, 0, false).onRun(null);

      verify(fixture.database).markAsSentFailed(42);
      verifyNoInteractions(fixture.queue);
      fixture.attempts.verify(() -> SmsSendAttemptTracker.claimRilRetry(fixture.context, 42), never());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final Context context = mock(Context.class);
    final EncryptingSmsDatabase database = mock(EncryptingSmsDatabase.class);
    final SmsDatabase smsDatabase = mock(SmsDatabase.class);
    final SmsMessageRecord record = mock(SmsMessageRecord.class);
    final JobManager queue = mock(JobManager.class);
    final MockedStatic<DatabaseFactory> databases = mockStatic(DatabaseFactory.class);
    final MockedStatic<SMSecurePreferences> preferences = mockStatic(SMSecurePreferences.class);
    final MockedStatic<SubscriptionManagerCompat> mappings = mockStatic(SubscriptionManagerCompat.class);
    final MockedStatic<ApplicationContext> applications = mockStatic(ApplicationContext.class);
    final MockedStatic<Log> logs = mockStatic(Log.class);
    final MockedStatic<MessageNotifier> notifications = mockStatic(MessageNotifier.class);
    final MockedStatic<SmsManager> managers = mockStatic(SmsManager.class);
    final MockedStatic<SmsSendAttemptTracker> attempts = mockStatic(SmsSendAttemptTracker.class);

    Fixture() {
      try {
        when(database.getMessage(null, 42)).thenReturn(record);
      } catch (org.jimvixx.smsecure.database.NoSuchMessageException impossible) {
        throw new AssertionError(impossible);
      }
      when(record.getId()).thenReturn(42L);
      Recipient recipient = mock(Recipient.class);
      when(recipient.getNumber()).thenReturn("synthetic-recipient");
      when(record.getIndividualRecipient()).thenReturn(recipient);
      databases.when(() -> DatabaseFactory.getEncryptingSmsDatabase(context)).thenReturn(database);
      databases.when(() -> DatabaseFactory.getSmsDatabase(context)).thenReturn(smsDatabase);
      preferences.when(() -> SMSecurePreferences.getSubscriptionRecoveryGeneration(context)).thenReturn(1L);
      SubscriptionManagerCompat mapping = mock(SubscriptionManagerCompat.class);
      mappings.when(() -> SubscriptionManagerCompat.from(context)).thenReturn(mapping);
      ApplicationContext application = mock(ApplicationContext.class);
      when(application.getJobManager()).thenReturn(queue);
      applications.when(() -> ApplicationContext.getInstance(context)).thenReturn(application);
    }

    @Override public void close() {
      managers.close();
      attempts.close();
      notifications.close();
      logs.close();
      applications.close();
      mappings.close();
      preferences.close();
      databases.close();
    }
  }
}
