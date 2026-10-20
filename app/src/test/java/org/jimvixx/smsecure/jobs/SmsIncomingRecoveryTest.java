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

import android.content.Context;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.SmsCipher;
import org.jimvixx.smsecure.crypto.storage.SMSecureSignalProtocolStore;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.EncryptingSmsDatabase;
import org.jimvixx.smsecure.database.model.DisplayRecord;
import org.jimvixx.smsecure.database.model.SmsMessageRecord;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.notifications.MessageNotifier;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.dualsim.SubscriptionInfoCompat;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;
import org.jimvixx.smsecure.sms.IncomingTextMessage;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.whispersystems.libsignal.util.guava.Optional;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class SmsIncomingRecoveryTest {

  @Test public void remappedPhysicalSimCannotUseThePreviousAppIdentity() throws Exception {
    Fixture fixture = new Fixture();
    SubscriptionInfoCompat current = mock(SubscriptionInfoCompat.class);
    when(current.getSubscriptionId()).thenReturn(8);
    when(fixture.manager.getActiveSubscriptionInfoFromDeviceSubscriptionId(11))
            .thenReturn(Optional.of(current));

    try (MockedConstruction<SMSecureSignalProtocolStore> stores =
                 mockConstruction(SMSecureSignalProtocolStore.class)) {
      new SmsDecryptJob(fixture.context, 42, false, false, 11, 0)
              .onRun(fixture.masterSecret);

      verify(fixture.database).markAsNoSession(42);
      assertTrue(stores.constructed().isEmpty());
    } finally {
      fixture.close();
    }
  }

  @Test public void legacyIncomingJobCannotCrossARecoveryGeneration() throws Exception {
    Fixture fixture = new Fixture();

    try (fixture; MockedConstruction<SMSecureSignalProtocolStore> stores =
            mockConstruction(SMSecureSignalProtocolStore.class)) {
      fixture.preferences.when(() ->
              SMSecurePreferences.getSubscriptionRecoveryGeneration(fixture.context)).thenReturn(1L);
      new SmsDecryptJob(fixture.context, 42, false, false, null, 0)
              .onRun(fixture.masterSecret);

      verify(fixture.database).markAsNoSession(42);
      assertTrue(stores.constructed().isEmpty());
    }
  }

  @Test public void unchangedPhysicalSimCanStillDecrypt() throws Exception {
    Fixture fixture = new Fixture();
    SubscriptionInfoCompat current = mock(SubscriptionInfoCompat.class);
    IncomingTextMessage plaintext = mock(IncomingTextMessage.class);
    when(current.getSubscriptionId()).thenReturn(7);
    when(plaintext.getMessageBody()).thenReturn("decrypted");
    when(fixture.manager.getActiveSubscriptionInfoFromDeviceSubscriptionId(11))
            .thenReturn(Optional.of(current));

    try (MockedConstruction<SMSecureSignalProtocolStore> stores =
                 mockConstruction(SMSecureSignalProtocolStore.class);
         MockedConstruction<SmsCipher> ciphers = mockConstruction(SmsCipher.class,
                 (cipher, ignored) -> when(cipher.decrypt(any(Context.class), any(IncomingTextMessage.class)))
                         .thenReturn(plaintext))) {
      new SmsDecryptJob(fixture.context, 42, false, false, 11, 0)
              .onRun(fixture.masterSecret);

      verify(fixture.database).updateMessageBody(fixture.masterSecret, 42, "decrypted");
      verify(fixture.database, never()).markAsNoSession(42);
      assertFalse(stores.constructed().isEmpty());
      assertFalse(ciphers.constructed().isEmpty());
    } finally {
      fixture.close();
    }
  }

  @Test public void ordinarySmsDoesNotDependOnSecureSimIdentity() throws Exception {
    Fixture fixture = new Fixture();

    try (fixture) {
      when(fixture.record.isSecure()).thenReturn(false);
      new SmsDecryptJob(fixture.context, 42, false, false, 11, 0)
              .onRun(fixture.masterSecret);

      verify(fixture.database).updateMessageBody(
              fixture.masterSecret, 42, "synthetic-ciphertext");
      verify(fixture.database, never()).markAsNoSession(42);
      verifyNoInteractions(fixture.manager);
    }
  }

  @Test public void unresolvedIncomingRouteRetriesOnlyTheReceiveJob() {
    SmsReceiveJob job = new SmsReceiveJob(mock(Context.class), new Object[0], 7, 11, 0);

    assertTrue(job.onShouldRetry(new SmsReceiveJob.UnresolvedSubscriptionException()));
    assertFalse(job.onShouldRetry(new IllegalStateException()));
  }

  private static final class Fixture implements AutoCloseable {
    final Context context = mock(Context.class);
    final MasterSecret masterSecret = mock(MasterSecret.class);
    final EncryptingSmsDatabase database = mock(EncryptingSmsDatabase.class);
    final SmsMessageRecord record = mock(SmsMessageRecord.class);
    final SubscriptionManagerCompat manager = mock(SubscriptionManagerCompat.class);
    final MockedStatic<DatabaseFactory> databases = mockStatic(DatabaseFactory.class);
    final MockedStatic<SubscriptionManagerCompat> managers = mockStatic(SubscriptionManagerCompat.class);
    final MockedStatic<SMSecurePreferences> preferences = mockStatic(SMSecurePreferences.class);
    final MockedStatic<Log> logs = mockStatic(Log.class);
    final MockedStatic<MessageNotifier> notifications = mockStatic(MessageNotifier.class);

    Fixture() throws Exception {
      Recipient recipient = mock(Recipient.class);
      Recipients recipients = mock(Recipients.class);
      when(recipient.getNumber()).thenReturn("synthetic-sender");
      when(recipients.getPrimaryRecipient()).thenReturn(recipient);
      when(record.getBody()).thenReturn(new DisplayRecord.Body("synthetic-ciphertext", true));
      when(record.getRecipients()).thenReturn(recipients);
      when(record.getId()).thenReturn(42L);
      when(record.getThreadId()).thenReturn(9L);
      when(record.getSubscriptionId()).thenReturn(7);
      when(record.isSecure()).thenReturn(true);
      when(database.getMessage(masterSecret, 42)).thenReturn(record);
      databases.when(() -> DatabaseFactory.getEncryptingSmsDatabase(context)).thenReturn(database);
      managers.when(() -> SubscriptionManagerCompat.from(context)).thenReturn(manager);
      preferences.when(() -> SMSecurePreferences.getSubscriptionRecoveryGeneration(context)).thenReturn(0L);
    }

    @Override public void close() {
      notifications.close();
      logs.close();
      preferences.close();
      managers.close();
      databases.close();
    }
  }
}
