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
import android.content.Intent;
import android.telephony.SmsMessage;
import org.jimvixx.smsecure.logging.Log;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SmsDeliveryReportTest {
  @Test public void absentUnknownMalformedAndNonReportPdusDoNotConfirmDelivery() {
    Intent intent = mock(Intent.class);
    byte[] pdu = new byte[] {0};
    try (MockedStatic<Log> logs = mockStatic(Log.class);
         MockedStatic<SmsMessage> parser = mockStatic(SmsMessage.class)) {
      when(intent.getStringExtra("format")).thenReturn("3gpp");
      assertNull(SmsDeliveryListener.logDeliveryReport(intent, Activity.RESULT_OK));
      parser.verifyNoInteractions();
      when(intent.getByteArrayExtra("pdu")).thenReturn(pdu);
      when(intent.getStringExtra("format")).thenReturn("unknown");
      assertNull(SmsDeliveryListener.logDeliveryReport(intent, Activity.RESULT_OK));
      parser.verifyNoInteractions();
      when(intent.getStringExtra("format")).thenReturn("3gpp");
      assertNull(SmsDeliveryListener.logDeliveryReport(intent, Activity.RESULT_OK));
      parser.when(() -> SmsMessage.createFromPdu(pdu, "3gpp"))
              .thenThrow(new IllegalArgumentException("synthetic"));
      assertNull(SmsDeliveryListener.logDeliveryReport(intent, Activity.RESULT_OK));
      SmsMessage report = mock(SmsMessage.class);
      parser.when(() -> SmsMessage.createFromPdu(pdu, "3gpp")).thenReturn(report);
      assertNull(SmsDeliveryListener.logDeliveryReport(intent, Activity.RESULT_OK));
      when(report.isStatusReportMessage()).thenReturn(true);
      assertNull(SmsDeliveryListener.logDeliveryReport(intent, Activity.RESULT_CANCELED));
    }
  }

  @Test public void gsmAndCdmaStatusesUseTheirOwnEncoding() {
    Intent intent = mock(Intent.class);
    SmsMessage report = mock(SmsMessage.class);
    byte[] pdu = new byte[] {0};
    when(intent.getByteArrayExtra("pdu")).thenReturn(pdu);
    when(report.isStatusReportMessage()).thenReturn(true);
    try (MockedStatic<Log> logs = mockStatic(Log.class);
         MockedStatic<SmsMessage> parser = mockStatic(SmsMessage.class)) {
      for (String format : new String[] {"3gpp", "3gpp2"}) {
        when(intent.getStringExtra("format")).thenReturn(format);
        parser.when(() -> SmsMessage.createFromPdu(pdu, format)).thenReturn(report);
        int[][] cases = "3gpp".equals(format)
                ? new int[][] {{0, 0}, {69, 69}, {-1, -1}, {128, -1}}
                : new int[][] {{2 << 16, 0}, {0, 32}, {2 << 24, 32}, {3 << 24, 64}};
        for (int[] sample : cases) {
          when(report.getStatus()).thenReturn(sample[0]);
          Integer actual = SmsDeliveryListener.logDeliveryReport(intent, Activity.RESULT_OK);
          if (sample[1] == -1) assertNull(actual);
          else assertEquals(Integer.valueOf(sample[1]), actual);
        }
      }
    }
  }
}
