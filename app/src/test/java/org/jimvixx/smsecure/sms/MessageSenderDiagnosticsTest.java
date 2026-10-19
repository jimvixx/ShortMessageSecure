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
package org.jimvixx.smsecure.sms;

import android.content.Context;

import org.jimvixx.smsecure.ApplicationContext;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.EncryptingSmsDatabase;
import org.jimvixx.smsecure.jobs.SmsSendJob;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.logging.SmsSendDiagnostics.SelectionSource;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.Recipients;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.whispersystems.jobqueue.JobManager;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MessageSenderDiagnosticsTest {
  @Test public void unavailableDiagnosticSinkDoesNotPreventQueueingOrChangeMessage() {
    Context context = mock(Context.class);
    MasterSecret masterSecret = mock(MasterSecret.class);
    OutgoingTextMessage message = mock(OutgoingTextMessage.class);
    Recipients recipients = mock(Recipients.class);
    Recipient recipient = mock(Recipient.class);
    EncryptingSmsDatabase database = mock(EncryptingSmsDatabase.class);
    ApplicationContext application = mock(ApplicationContext.class);
    JobManager queue = mock(JobManager.class);
    when(message.getRecipients()).thenReturn(recipients);
    when(message.getSubscriptionId()).thenReturn(20);
    when(recipients.getPrimaryRecipient()).thenReturn(recipient);
    when(database.insertMessageOutbox(eq(masterSecret), eq(7L), same(message), eq(true), anyLong()))
            .thenReturn(42L);
    when(application.getJobManager()).thenReturn(queue);

    try (MockedStatic<DatabaseFactory> databases = mockStatic(DatabaseFactory.class);
         MockedStatic<ApplicationContext> applications = mockStatic(ApplicationContext.class);
         MockedStatic<Log> logs = mockStatic(Log.class);
         MockedConstruction<SmsSendJob> jobs = mockConstruction(SmsSendJob.class)) {
      databases.when(() -> DatabaseFactory.getEncryptingSmsDatabase(context)).thenReturn(database);
      applications.when(() -> ApplicationContext.getInstance(context)).thenReturn(application);
      logs.when(() -> Log.i(anyString(), anyString())).thenThrow(new IllegalStateException("synthetic"));
      logs.when(() -> Log.w(anyString(), anyString())).thenThrow(new IllegalStateException("synthetic"));

      assertEquals(7L, MessageSender.send(context, masterSecret, message, 7L, true,
                                        SelectionSource.MANUAL));
      assertEquals(1, jobs.constructed().size());
      verify(queue).add(jobs.constructed().get(0));
      verify(database).insertMessageOutbox(eq(masterSecret), eq(7L), same(message), eq(true), anyLong());
      logs.verify(() -> Log.i("SmsSendDiagnostics",
              "SMS queued: message=42 uiAppSub=20 selectionSource=MANUAL"));
    }
  }
}
