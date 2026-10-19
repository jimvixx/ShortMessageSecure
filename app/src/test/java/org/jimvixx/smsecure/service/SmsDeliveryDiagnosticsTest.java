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
package org.jimvixx.smsecure.service;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import org.jimvixx.smsecure.ApplicationContext;
import org.jimvixx.smsecure.jobs.SmsSentJob;
import org.jimvixx.smsecure.logging.Log;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.whispersystems.jobqueue.JobManager;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SmsDeliveryDiagnosticsTest {
  @Test public void unreadableDiagnosticExtrasStillQueueSentResult() {
    Context context = mock(Context.class);
    Intent intent = mock(Intent.class);
    ApplicationContext application = mock(ApplicationContext.class);
    JobManager jobs = mock(JobManager.class);
    SmsDeliveryListener receiver = mock(SmsDeliveryListener.class, CALLS_REAL_METHODS);
    when(intent.getAction()).thenReturn(SmsDeliveryListener.SENT_SMS_ACTION);
    when(intent.getLongExtra("message_id", -1)).thenReturn(42L);
    when(intent.getIntExtra("parts_total", 1)).thenReturn(1);
    when(intent.getExtras()).thenThrow(new IllegalArgumentException("private callback value"));
    when(application.getJobManager()).thenReturn(jobs);
    doReturn(Activity.RESULT_CANCELED).when(receiver).getResultCode();
    try (MockedStatic<ApplicationContext> app = mockStatic(ApplicationContext.class);
         MockedStatic<Log> logs = mockStatic(Log.class)) {
      app.when(() -> ApplicationContext.getInstance(context)).thenReturn(application);
      logs.when(() -> Log.w(anyString(), anyString())).thenThrow(new IllegalStateException("sink failure"));
      receiver.onReceive(context, intent);
      verify(jobs).add(any(SmsSentJob.class));
    }
  }
}
