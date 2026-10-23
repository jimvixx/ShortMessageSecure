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

package org.jimvixx.smsecure.logging;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.jimvixx.smsecure.database.model.SmsMessageRecord;
import org.jimvixx.smsecure.util.Util;
import org.jimvixx.smsecure.util.dualsim.SubscriptionInfoCompat;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SmsSendDiagnosticsTest {
  @Test public void absentCallbackFieldsStayDistinctFromZeroAndFalse() {
    Intent intent = mock(Intent.class);
    assertEquals("errorCode=absent noDefault=absent format=absent ims=absent",
            SmsSendDiagnostics.callbackFields(intent));

    Bundle extras = mock(Bundle.class);
    when(intent.getExtras()).thenReturn(extras);
    field(extras, "errorCode", 0);
    field(extras, "noDefault", false);
    field(extras, "format", "3gpp");
    field(extras, "ims", false);
    assertEquals("errorCode=0 noDefault=false format=3gpp ims=false",
            SmsSendDiagnostics.callbackFields(intent));
    field(extras, "ims", true);
    field(extras, "format", "3gpp2");
    assertEquals("errorCode=0 noDefault=false format=3gpp2 ims=true",
            SmsSendDiagnostics.callbackFields(intent));
  }

  @Test public void malformedCallbackFieldsAndPrivateValuesAreNotStringified() {
    Intent intent = mock(Intent.class);
    Bundle extras = mock(Bundle.class);
    when(intent.getExtras()).thenReturn(extras);
    Object privateValue = new Object() {
      @NonNull
      @Override public String toString() { throw new AssertionError("Private value read"); }
    };
    field(extras, "errorCode", privateValue);
    field(extras, "noDefault", "private boolean value");
    field(extras, "format", "private format value");
    field(extras, "ims", 1);
    assertEquals("errorCode=malformed noDefault=malformed format=unknown ims=malformed",
            SmsSendDiagnostics.callbackFields(intent));
    field(extras, "format", privateValue);
    field(extras, "ims", null);
    assertEquals("errorCode=malformed noDefault=malformed format=unknown ims=malformed",
            SmsSendDiagnostics.callbackFields(intent));
    verify(extras, never()).keySet();
    verify(extras, never()).containsKey("body");
    verify(extras, never()).containsKey("address");
    verify(extras, never()).containsKey("pdu");
  }

  @Test public void sentFailureIncludesAllowlistedRadioPathFields() {
    Intent intent = mock(Intent.class);
    Bundle extras = mock(Bundle.class);
    when(intent.getExtras()).thenReturn(extras);
    field(extras, "errorCode", 38);
    field(extras, "format", "3gpp");
    field(extras, "ims", true);
    try (MockedStatic<Log> logs = mockStatic(Log.class)) {
      SmsSendDiagnostics.logSentResult(intent, 42, 1, 0, 1);
      logs.verify(() -> Log.e("SmsSendDiagnostics", "SMS sent result: message=42 result=1 part=0/1 "
              + "errorCode=38 noDefault=absent format=3gpp ims=true"));
    }
  }

  @Test public void diagnosticReadAndSinkFailuresNeverEscape() {
    Intent intent = mock(Intent.class);
    when(intent.getExtras()).thenThrow(new IllegalArgumentException("private failure detail"));
    try (MockedStatic<Log> logs = mockStatic(Log.class)) {
      SmsSendDiagnostics.logSentResult(intent, 42, 1, 0, 1);
      logs.verify(() -> Log.w("SmsSendDiagnostics", "SMS diagnostics unavailable: IllegalArgumentException"));
      logs.when(() -> Log.w(anyString(), anyString())).thenThrow(new IllegalStateException("private sink detail"));
      SmsSendDiagnostics.logSentResult(intent, 42, 1, 0, 1);
      logs.when(() -> Log.i(anyString(), anyString())).thenThrow(new IllegalStateException("private sink detail"));
      SmsSendDiagnostics.logQueued(42, 1, SmsSendDiagnostics.SelectionSource.MANUAL);
    }
  }

  @Test public void mappingsDetectAmbiguityWithoutAccessingSimIdentities() {
    SubscriptionInfoCompat first = subscription(7, 11, 1);
    SubscriptionInfoCompat second = subscription(7, 22, 2);
    assertEquals("activeCount=2 duplicateAppSub=true appSubMatches=2 mappingConflicts=0 "
                    + "mappings=[{appSub=7 deviceSub=11 slot=1},{appSub=7 deviceSub=22 slot=2}]",
            SmsSendDiagnostics.describeSubscriptions(Arrays.asList(first, second), 7));
    for (SubscriptionInfoCompat item : Arrays.asList(first, second)) {
      verify(item).getSubscriptionId();
      verify(item).hasMappingConflict();
      verify(item).getDeviceSubscriptionId();
      verify(item).getIccSlot();
      verifyNoMoreInteractions(item);
    }
  }

  @Test public void mappingsDistinguishEmptyMissingAndUniqueSelections() {
    assertEquals("activeCount=0 duplicateAppSub=false appSubMatches=0 mappingConflicts=0 mappings=[]",
            SmsSendDiagnostics.describeSubscriptions(Collections.emptyList(), -1));
    assertTrue(SmsSendDiagnostics.describeSubscriptions(
            Arrays.asList(subscription(7, 11, 1), subscription(8, 22, 2)), 7)
            .contains("duplicateAppSub=false appSubMatches=1"));
    assertTrue(SmsSendDiagnostics.describeSubscriptions(
            Collections.singletonList(subscription(7, 11, 1)), 9).contains("appSubMatches=0"));
  }

  @Test public void defaultPackageSeparatesMismatchFromUnavailable() {
    Context context = mock(Context.class);
    when(context.getPackageName()).thenReturn("org.jimvixx.smsecure");
    try (MockedStatic<Telephony.Sms> telephony = mockStatic(Telephony.Sms.class)) {
      assertEquals("unavailable", SmsSendDiagnostics.defaultSmsPackageState(context));
      telephony.when(() -> Telephony.Sms.getDefaultSmsPackage(context)).thenReturn("other.private.package");
      assertEquals("mismatch", SmsSendDiagnostics.defaultSmsPackageState(context));
      telephony.when(() -> Telephony.Sms.getDefaultSmsPackage(context)).thenReturn("org.jimvixx.smsecure");
      assertEquals("matches", SmsSendDiagnostics.defaultSmsPackageState(context));
      telephony.when(() -> Telephony.Sms.getDefaultSmsPackage(context))
              .thenThrow(new SecurityException("private failure detail"));
      assertEquals("unavailable", SmsSendDiagnostics.defaultSmsPackageState(context));
    }
  }

  @Test public void sendUsesOnlyCachedMappingsAndReportsActualManagerSubscription() {
    Context context = mock(Context.class);
    SmsMessageRecord message = mock(SmsMessageRecord.class);
    SmsManager smsManager = mock(SmsManager.class);
    SubscriptionManagerCompat subscriptions = mock(SubscriptionManagerCompat.class);
    when(message.getId()).thenReturn(42L);
    when(message.getSubscriptionId()).thenReturn(7);
    when(smsManager.getSubscriptionId()).thenReturn(11);
    SubscriptionInfoCompat activeSubscription = subscription(7, 11, 1);
    when(subscriptions.getLastKnownActiveSubscriptionInfoList())
            .thenReturn(Collections.singletonList(activeSubscription));
    try (MockedStatic<Log> logs = mockStatic(Log.class);
         MockedStatic<SubscriptionManagerCompat> mapping = mockStatic(SubscriptionManagerCompat.class);
         MockedStatic<SmsManager> managers = mockStatic(SmsManager.class);
         MockedStatic<Telephony.Sms> telephony = mockStatic(Telephony.Sms.class);
         MockedStatic<Util> defaults = mockStatic(Util.class);
         MockedStatic<ContextCompat> permissions = mockStatic(ContextCompat.class)) {
      mapping.when(() -> SubscriptionManagerCompat.from(context)).thenReturn(subscriptions);
      managers.when(SmsManager::getDefaultSmsSubscriptionId).thenReturn(22);
      defaults.when(() -> Util.getDefaultSmsAppStatus(context)).thenReturn(true);
      permissions.when(() -> ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS))
              .thenReturn(PackageManager.PERMISSION_GRANTED);
      SmsSendDiagnostics.logSend(context, message, 11, smsManager, 1, true);
      logs.verify(() -> Log.i(eq("SmsSendDiagnostics"), argThat(line ->
              line.contains("appSub=7 deviceSub=11 managerDeviceSub=11 defaultDeviceSub=22")
              && line.contains("defaultSmsRole=true defaultSmsPackage=unavailable")
              && line.contains("sendPermission=true deliveryReports=true")
              && line.contains("activeCount=1 duplicateAppSub=false appSubMatches=1"))));
      verify(subscriptions).getLastKnownActiveSubscriptionInfoList();
      verifyNoMoreInteractions(subscriptions);
      verify(message, never()).getBody();
      verify(message, never()).getIndividualRecipient();
      verify(smsManager).getSubscriptionId();
      verifyNoMoreInteractions(smsManager);
    }
  }

  @Test public void sendDiagnosticReadAndSinkFailuresLeaveRoutingUntouched() {
    Context context = mock(Context.class);
    SmsMessageRecord message = mock(SmsMessageRecord.class);
    SmsManager smsManager = mock(SmsManager.class);
    SubscriptionManagerCompat subscriptions = mock(SubscriptionManagerCompat.class);
    when(message.getId()).thenReturn(42L);
    when(message.getSubscriptionId()).thenReturn(7);
    when(smsManager.getSubscriptionId()).thenThrow(new SecurityException("private manager detail"));
    SubscriptionInfoCompat activeSubscription = subscription(7, 11, 1);
    when(subscriptions.getLastKnownActiveSubscriptionInfoList())
            .thenReturn(Collections.singletonList(activeSubscription));
    try (MockedStatic<Log> logs = mockStatic(Log.class);
         MockedStatic<SubscriptionManagerCompat> mapping = mockStatic(SubscriptionManagerCompat.class);
         MockedStatic<SmsManager> managers = mockStatic(SmsManager.class);
         MockedStatic<Telephony.Sms> telephony = mockStatic(Telephony.Sms.class);
         MockedStatic<Util> defaults = mockStatic(Util.class);
         MockedStatic<ContextCompat> permissions = mockStatic(ContextCompat.class)) {
      mapping.when(() -> SubscriptionManagerCompat.from(context)).thenReturn(subscriptions);
      managers.when(SmsManager::getDefaultSmsSubscriptionId).thenReturn(22);
      logs.when(() -> Log.i(anyString(), anyString())).thenThrow(new IllegalStateException("private sink detail"));
      logs.when(() -> Log.w(anyString(), anyString())).thenThrow(new IllegalStateException("private fallback detail"));

      SmsSendDiagnostics.logSend(context, message, 11, smsManager, 1, false);

      logs.verify(() -> Log.i(eq("SmsSendDiagnostics"), argThat(line ->
              line.contains("deviceSub=11 managerDeviceSub=unavailable defaultDeviceSub=22")
              && line.contains("activeCount=1 duplicateAppSub=false appSubMatches=1")
              && !line.contains("private"))));
      logs.verify(() -> Log.w("SmsSendDiagnostics", "SMS diagnostics unavailable: IllegalStateException"));
      verify(subscriptions).getLastKnownActiveSubscriptionInfoList();
      verifyNoMoreInteractions(subscriptions);
      verify(smsManager).getSubscriptionId();
      verifyNoMoreInteractions(smsManager);
      managers.verify(SmsManager::getDefaultSmsSubscriptionId);
      managers.verifyNoMoreInteractions();
      mapping.verify(() -> SubscriptionManagerCompat.from(context));
      mapping.verifyNoMoreInteractions();
      verify(message, never()).getBody();
      verify(message, never()).getIndividualRecipient();
    }
  }

  @Test public void queuedSelectionSourceUsesEnumAndStableMessageId() {
    try (MockedStatic<Log> logs = mockStatic(Log.class)) {
      SmsSendDiagnostics.logQueued(42, 7, SmsSendDiagnostics.SelectionSource.RECIPIENT_PREFERENCE);
      logs.verify(() -> Log.i("SmsSendDiagnostics", "SMS queued: message=42 uiAppSub=7 selectionSource=RECIPIENT_PREFERENCE"));
      SmsSendDiagnostics.logQueued(43, -1, null);
      logs.verify(() -> Log.i("SmsSendDiagnostics", "SMS queued: message=43 uiAppSub=-1 selectionSource=UNKNOWN"));
    }
  }

  @Test public void blockedRouteReportsCachedMatchCountWithoutPrivateDataOrRefresh() {
    Context context = mock(Context.class);
    SmsMessageRecord message = mock(SmsMessageRecord.class);
    when(message.getId()).thenReturn(42L);
    when(message.getSubscriptionId()).thenReturn(7);
    SubscriptionInfoCompat first = subscription(7, 11, 1);
    SubscriptionInfoCompat second = subscription(7, 22, 2);
    SubscriptionManagerCompat subscriptions = mock(SubscriptionManagerCompat.class);
    when(subscriptions.getLastKnownActiveSubscriptionInfoList()).thenReturn(Arrays.asList(first, second));
    try (MockedStatic<Log> logs = mockStatic(Log.class);
         MockedStatic<SubscriptionManagerCompat> mapping = mockStatic(SubscriptionManagerCompat.class)) {
      mapping.when(() -> SubscriptionManagerCompat.from(context)).thenReturn(subscriptions);
      SmsSendDiagnostics.logRouteBlocked(context, message);
      logs.verify(() -> Log.w("SmsSendDiagnostics", "SMS route blocked: message=42 appSub=7 "
              + "reason=unresolved_subscription mappingSource=lastKnownSnapshot "
              + "activeCount=2 duplicateAppSub=true appSubMatches=2 mappingConflicts=0 "
              + "mappings=[{appSub=7 deviceSub=11 slot=1},{appSub=7 deviceSub=22 slot=2}]"));
      verify(subscriptions).getLastKnownActiveSubscriptionInfoList();
      verifyNoMoreInteractions(subscriptions);
      verify(message, never()).getBody();
      verify(message, never()).getIndividualRecipient();
      for (SubscriptionInfoCompat item : Arrays.asList(first, second)) {
        verify(item).getSubscriptionId();
        verify(item).hasMappingConflict();
        verify(item).getDeviceSubscriptionId();
        verify(item).getIccSlot();
        verifyNoMoreInteractions(item);
      }
    }
  }

  @Test public void blockedRouteDiagnosticFailuresNeverEscape() {
    Context context = mock(Context.class);
    SmsMessageRecord message = mock(SmsMessageRecord.class);
    SubscriptionManagerCompat subscriptions = mock(SubscriptionManagerCompat.class);
    when(subscriptions.getLastKnownActiveSubscriptionInfoList()).thenReturn(Collections.emptyList());
    try (MockedStatic<Log> logs = mockStatic(Log.class);
         MockedStatic<SubscriptionManagerCompat> mapping = mockStatic(SubscriptionManagerCompat.class)) {
      mapping.when(() -> SubscriptionManagerCompat.from(context)).thenReturn(subscriptions);
      logs.when(() -> Log.w(anyString(), anyString())).thenThrow(new IllegalStateException("private sink detail"));
      SmsSendDiagnostics.logRouteBlocked(context, message);
      when(subscriptions.getLastKnownActiveSubscriptionInfoList())
              .thenThrow(new SecurityException("private mapping detail"));
      SmsSendDiagnostics.logRouteBlocked(context, message);
      logs.verify(() -> Log.w("SmsSendDiagnostics", "SMS diagnostics unavailable: SecurityException"));
    }
  }

  @Test public void mappingsReportPersistedConflictEvenWithOneActiveMatch() {
    SubscriptionInfoCompat only = subscription(7, 22, 2);
    when(only.hasMappingConflict()).thenReturn(true);
    assertTrue(SmsSendDiagnostics.describeSubscriptions(Collections.singletonList(only), 7)
            .contains("duplicateAppSub=false appSubMatches=1 mappingConflicts=1"));
  }

  private static SubscriptionInfoCompat subscription(int app, int device, int slot) {
    SubscriptionInfoCompat result = mock(SubscriptionInfoCompat.class);
    when(result.getSubscriptionId()).thenReturn(app);
    when(result.getDeviceSubscriptionId()).thenReturn(device);
    when(result.getIccSlot()).thenReturn(slot);
    return result;
  }

  private static void field(Bundle extras, String name, Object value) {
    when(extras.containsKey(name)).thenReturn(true);
    if ("errorCode".equals(name)) {
      when(extras.getInt(eq(name), anyInt())).thenAnswer(invocation ->
              value instanceof Integer ? value : invocation.getArgument(1));
    } else if ("format".equals(name)) {
      when(extras.getString(name)).thenReturn(value instanceof String ? (String) value : null);
    } else {
      when(extras.getBoolean(eq(name), anyBoolean())).thenAnswer(invocation ->
              value instanceof Boolean ? value : invocation.getArgument(1));
    }
  }
}
