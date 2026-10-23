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

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telephony.SmsManager;

import org.jimvixx.smsecure.crypto.SmsCipher;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.EncryptingSmsDatabase;
import org.jimvixx.smsecure.database.SmsDatabase;
import org.jimvixx.smsecure.database.model.DisplayRecord;
import org.jimvixx.smsecure.database.model.SmsMessageRecord;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.logging.SmsSendDiagnostics;
import org.jimvixx.smsecure.notifications.MessageNotifier;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.service.SmsSendAttemptTracker;
import org.jimvixx.smsecure.util.NumberUtil;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.dualsim.SubscriptionInfoCompat;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the send job with synthetic subscriptions; Android SMS calls are mocked. */
@SuppressWarnings("deprecation")
public class SmsSendRoutingTest {
  @Test public void modernPlatformUsesContextSmsManagerAndExplicitSubscription() {
    Context context = mock(Context.class);
    SmsManager defaultManager = mock(SmsManager.class);
    SmsManager selectedManager = mock(SmsManager.class);
    when(context.getSystemService(SmsManager.class)).thenReturn(defaultManager);
    when(defaultManager.createForSubscriptionId(22)).thenReturn(selectedManager);

    assertSame(defaultManager, SmsSendJob.getModernSmsManagerFor(context, -1));
    assertSame(selectedManager, SmsSendJob.getModernSmsManagerFor(context, 22));
    verify(defaultManager).createForSubscriptionId(22);
  }

  @Test public void ambiguousSelectionFailsBeforeEncryptionOrAnySmsManagerCall() throws Exception {
    assertBlocked(7, true, subscription(7, 11), subscription(7, 22));
  }

  @Test public void persistedConflictBlocksEvenWhenOnlyOneSimRemains() throws Exception {
    SubscriptionInfoCompat remaining = subscription(7, 22);
    when(remaining.hasMappingConflict()).thenReturn(true);
    assertBlocked(7, true, remaining);
  }

  @Test public void ambiguousSelectionFailsInEitherListOrder() throws Exception {
    assertBlocked(7, false, subscription(7, 22), subscription(7, 11));
  }

  @Test public void disappearedSelectionDoesNotFallBackToRemainingSim() throws Exception {
    assertBlocked(8, true, subscription(7, 11));
  }

  @Test public void temporarilyEmptySubscriptionListFailsSelectedSim() throws Exception {
    assertBlocked(7, false);
  }

  @Test public void invalidResolvedDeviceIdDoesNotFallBack() throws Exception {
    assertBlocked(7, false, subscription(7, -1));
  }

  @Test public void uniqueSelectionSendsThroughSelectedManager() throws Exception {
    assertSent(8, 22, subscription(7, 11), subscription(8, 22));
  }

  @Test public void ambiguityOfOtherSimDoesNotBlockUniqueSelection() throws Exception {
    assertSent(8, 33, subscription(7, 11), subscription(7, 22), subscription(8, 33));
  }

  @Test public void legacyUnspecifiedSelectionKeepsSystemDefaultBehavior() throws Exception {
    assertSent(-1, -1);
  }

  @Test public void queuedSendFromBeforeRecoveryRequiresManualRetryEvenWithValidRoute() throws Exception {
    try (Fixture fixture = new Fixture(7, subscription(7, 22))) {
      fixture.preferences.when(() -> SMSecurePreferences.getSubscriptionRecoveryGeneration(fixture.context)).thenReturn(1L);
      fixture.job.onSend(null);
      verify(fixture.database).markAsSentFailed(42L);
      fixture.managers.verifyNoInteractions();
      fixture.attempts.verifyNoInteractions();
      fixture.deliveries.verifyNoInteractions();
      verify(fixture.message, never()).getBody();
    }
  }

  private void assertBlocked(int appId, boolean secure, SubscriptionInfoCompat... active) throws Exception {
    try (Fixture fixture = new Fixture(appId, active);
         MockedConstruction<SmsCipher> ciphers = mockConstruction(SmsCipher.class)) {
      when(fixture.message.isSecure()).thenReturn(secure);

      fixture.job.onSend(null);

      verify(fixture.database).markAsSentFailed(42L);
      verifyNoMoreInteractions(fixture.database);
      fixture.notifications.verify(() -> MessageNotifier.notifyMessageDeliveryFailed(
              fixture.context, fixture.recipients, 99L));
      fixture.diagnostics.verify(() -> SmsSendDiagnostics.logRouteBlocked(fixture.context, fixture.message));
      fixture.diagnostics.verifyNoMoreInteractions();
      fixture.managers.verifyNoInteractions();
      fixture.attempts.verifyNoInteractions();
      fixture.deliveries.verifyNoInteractions();
      verify(fixture.message, never()).getBody();
      verify(fixture.message, never()).getIndividualRecipient();
      assertTrue(ciphers.constructed().isEmpty());
    }
  }

  @Test public void newUserSendAfterRecoveryCanUseTheConfirmedRoute() throws Exception {
    assertSentWithGeneration(7, 22, 3, subscription(7, 22));
  }

  private void assertSent(int appId, int expectedDeviceId, SubscriptionInfoCompat... active) throws Exception {
    assertSentWithGeneration(appId, expectedDeviceId, 0, active);
  }

  private void assertSentWithGeneration(int appId, int expectedDeviceId, long generation,
                                        SubscriptionInfoCompat... active) throws Exception {
    try (Fixture fixture = new Fixture(appId, active);
         MockedStatic<NumberUtil> numbers = mockStatic(NumberUtil.class);
         MockedStatic<Uri> uris = mockStatic(Uri.class);
         MockedStatic<PendingIntent> pendingIntents = mockStatic(PendingIntent.class);
         MockedConstruction<Intent> intents = mockConstruction(Intent.class)) {
      Recipient recipient = mock(Recipient.class);
      when(recipient.getNumber()).thenReturn("synthetic-address");
      when(fixture.message.getIndividualRecipient()).thenReturn(recipient);
      when(fixture.message.getBody()).thenReturn(new DisplayRecord.Body("synthetic body", true));
      numbers.when(() -> NumberUtil.isValidEmail("synthetic-address")).thenReturn(true);
      numbers.when(() -> NumberUtil.isValidSmsOrEmail("synthetic-address")).thenReturn(true);
      SmsManager defaultManager = mock(SmsManager.class);
      SmsManager selectedManager = expectedDeviceId == -1 ? defaultManager : mock(SmsManager.class);
      ArrayList<String> parts = new ArrayList<>(Collections.singletonList("synthetic body"));
      fixture.managers.when(SmsManager::getDefault).thenReturn(defaultManager);
      fixture.managers.when(() -> SmsManager.getSmsManagerForSubscriptionId(expectedDeviceId))
              .thenReturn(selectedManager);
      when(defaultManager.divideMessage("synthetic body")).thenReturn(parts);

      fixture.preferences.when(() -> SMSecurePreferences.getSubscriptionRecoveryGeneration(fixture.context)).thenReturn(generation);
      SmsSendJob actualJob = generation == 0 ? fixture.job : new SmsSendJob(fixture.context, 0, "group");
      actualJob.onSend(null);

      verify(intents.constructed().get(0)).putExtra("mapping_generation", generation);
      verify(selectedManager).sendMultipartTextMessage(eq("synthetic-address"), isNull(),
              eq(parts), any(), isNull());
      if (expectedDeviceId != -1) {
        verify(defaultManager, never()).sendMultipartTextMessage(any(), any(), any(), any(), any());
        fixture.managers.verify(() -> SmsManager.getSmsManagerForSubscriptionId(expectedDeviceId));
      } else {
        fixture.managers.verify(() -> SmsManager.getSmsManagerForSubscriptionId(anyInt()), never());
      }
      fixture.diagnostics.verify(() -> SmsSendDiagnostics.logRouteBlocked(any(), any()), never());
      verifyNoInteractions(fixture.database);
      fixture.notifications.verifyNoInteractions();
      fixture.attempts.verify(() -> SmsSendAttemptTracker.startAttempt(fixture.context, 42L, actualJob.sendAttemptId, 1));
    }
  }

  private static SubscriptionInfoCompat subscription(int appId, int deviceId) {
    SubscriptionInfoCompat subscription = mock(SubscriptionInfoCompat.class);
    when(subscription.getSubscriptionId()).thenReturn(appId);
    when(subscription.getDeviceSubscriptionId()).thenReturn(deviceId);
    return subscription;
  }

  private static class Fixture implements AutoCloseable {
    final Context context = mock(Context.class);
    final SmsMessageRecord message = mock(SmsMessageRecord.class);
    final Recipients recipients = mock(Recipients.class);
    final SmsDatabase database = mock(SmsDatabase.class);
    final SmsSendJob job = mock(SmsSendJob.class, CALLS_REAL_METHODS);
    final MockedStatic<SMSecurePreferences> preferences = mockStatic(SMSecurePreferences.class);
    final MockedStatic<DatabaseFactory> databases = mockStatic(DatabaseFactory.class);
    final MockedStatic<SubscriptionManagerCompat> subscriptions = mockStatic(SubscriptionManagerCompat.class);
    final MockedStatic<SmsManager> managers = mockStatic(SmsManager.class);
    final MockedStatic<Log> logs = mockStatic(Log.class);
    final MockedStatic<SmsSendDiagnostics> diagnostics = mockStatic(SmsSendDiagnostics.class);
    final MockedStatic<MessageNotifier> notifications = mockStatic(MessageNotifier.class);
    final MockedStatic<SmsSendAttemptTracker> attempts = mockStatic(SmsSendAttemptTracker.class);
    final MockedStatic<SmsDeliveryTracker> deliveries = mockStatic(SmsDeliveryTracker.class);

    Fixture(int appId, SubscriptionInfoCompat... active) throws Exception {
      job.setContext(context);
      when(message.getId()).thenReturn(42L);
      when(message.getThreadId()).thenReturn(99L);
      when(message.getSubscriptionId()).thenReturn(appId);
      when(message.getRecipients()).thenReturn(recipients);
      EncryptingSmsDatabase encryptedDatabase = mock(EncryptingSmsDatabase.class);
      databases.when(() -> DatabaseFactory.getEncryptingSmsDatabase(context)).thenReturn(encryptedDatabase);
      databases.when(() -> DatabaseFactory.getSmsDatabase(context)).thenReturn(database);
      when(encryptedDatabase.getMessage(null, 0L)).thenReturn(message);
      SubscriptionManagerCompat manager = mock(SubscriptionManagerCompat.class, CALLS_REAL_METHODS);
      List<SubscriptionInfoCompat> list = Arrays.asList(active);
      doReturn(list).when(manager).getActiveSubscriptionInfoList();
      subscriptions.when(() -> SubscriptionManagerCompat.from(context)).thenReturn(manager);
    }

    @Override public void close() {
      deliveries.close();
      attempts.close();
      notifications.close();
      diagnostics.close();
      logs.close();
      managers.close();
      subscriptions.close();
      databases.close();
      preferences.close();
    }
  }
}
