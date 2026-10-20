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

package org.jimvixx.smsecure.util.dualsim;

import android.Manifest;
import android.content.Context;
import android.telephony.SmsManager;
import org.jimvixx.smsecure.TransportOption;
import org.jimvixx.smsecure.TransportOptions;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.RecipientPreferenceDatabase;
import org.jimvixx.smsecure.permissions.Permissions;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.sms.OutgoingTextMessage;
import org.jimvixx.smsecure.util.ThemeUtil;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.telephony.SubscriptionInfo;
import android.net.Uri;
import androidx.preference.PreferenceManager;
import org.jimvixx.smsecure.util.TelephonyUtil;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;

import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uses persisted synthetic mappings and the production manager; never accesses a device. */
@SuppressWarnings("deprecation")
public class SubscriptionMappingDiagnosticsTest {

  @Test public void subscriptionNumberKeepsDefaultSmsRoleCompatibleAccessor() {
    Context modernContext = mock(Context.class);
    SubscriptionInfo info = mock(SubscriptionInfo.class);
    when(info.getNumber()).thenReturn("synthetic-number");
    when(info.getMccString()).thenReturn("310");
    when(info.getMncString()).thenReturn("026");

    assertEquals("synthetic-number", SubscriptionManagerCompat.getSubscriptionNumber(modernContext, info));
    assertEquals(310, SubscriptionManagerCompat.getModernMcc(info));
    assertEquals(26, SubscriptionManagerCompat.getModernMnc(info));
    verifyNoInteractions(modernContext);
  }

  @Test public void unavailableSubscriptionNumberIsEmpty() {
    SubscriptionInfo info = mock(SubscriptionInfo.class);
    when(info.getNumber()).thenReturn(null);

    assertEquals("", SubscriptionManagerCompat.getSubscriptionNumber(context, info));
  }

  private final Context context = mock(Context.class);
  private MockedStatic<PreferenceManager> preferenceManager;
  private MockedStatic<DatabaseFactory> databaseFactory;
  private MockedStatic<Uri> uris;
  private MockedStatic<TextUtils> textUtils;
  private MockedStatic<TelephonyUtil> telephony;
  private SharedPreferences.Editor editor;
  private RecipientPreferenceDatabase recipientPreferences;
  private Map<String, Object> stored;
  private SubscriptionManagerCompat manager;
  private List<SubscriptionInfo> active = Collections.emptyList();

  @Before
  public void setUp() throws Exception {
    when(context.getApplicationContext()).thenReturn(context);

    SharedPreferences preferences = mock(SharedPreferences.class);
    editor = mock(SharedPreferences.Editor.class);
    stored = new HashMap<>();
    Map<String, Object> pending = new HashMap<>();

    when(preferences.edit()).thenReturn(editor);
    when(preferences.getAll()).thenAnswer(ignored -> new HashMap<>(stored));
    when(preferences.getLong(anyString(), anyLong())).thenAnswer(invocation -> storedValue(
            invocation.getArgument(0), invocation.getArgument(1), Long.class));
    when(editor.putLong(anyString(), anyLong())).thenAnswer(invocation -> {
      pending.put(invocation.getArgument(0), invocation.getArgument(1));
      return editor;
    });
    when(editor.remove(anyString())).thenAnswer(invocation -> {
      pending.put(invocation.getArgument(0), null);
      return editor;
    });
    when(preferences.getBoolean(anyString(), anyBoolean())).thenAnswer(invocation -> storedValue(
            invocation.getArgument(0), invocation.getArgument(1), Boolean.class));
    when(preferences.getInt(anyString(), anyInt())).thenAnswer(invocation -> storedValue(
            invocation.getArgument(0), invocation.getArgument(1), Integer.class));
    when(preferences.getString(anyString(), nullable(String.class))).thenAnswer(invocation -> storedValue(
            invocation.getArgument(0), invocation.getArgument(1), String.class));
    when(editor.putBoolean(anyString(), anyBoolean())).thenAnswer(invocation -> {
      pending.put(invocation.getArgument(0), invocation.getArgument(1));
      return editor;
    });
    when(editor.putInt(anyString(), anyInt())).thenAnswer(invocation -> {
      pending.put(invocation.getArgument(0), invocation.getArgument(1));
      return editor;
    });
    when(editor.putString(anyString(), nullable(String.class))).thenAnswer(invocation -> {
      pending.put(invocation.getArgument(0), invocation.getArgument(1));
      return editor;
    });
    doAnswer(invocation -> {
      pending.forEach((key, value) -> { if (value == null) stored.remove(key); else stored.put(key, value); });
      pending.clear();
      return null;
    }).when(editor).apply();

    preferenceManager = mockStatic(PreferenceManager.class);
    preferenceManager.when(() -> PreferenceManager.getDefaultSharedPreferences(context))
            .thenReturn(preferences);
    uris = mockStatic(Uri.class);
    uris.when(() -> Uri.parse(anyString())).thenReturn(mock(Uri.class));
    recipientPreferences = mock(RecipientPreferenceDatabase.class);
    databaseFactory = mockStatic(DatabaseFactory.class);
    databaseFactory.when(() -> DatabaseFactory.getRecipientPreferenceDatabase(context))
            .thenReturn(recipientPreferences);
    textUtils = mockStatic(TextUtils.class);
    textUtils.when(() -> TextUtils.isEmpty(nullable(CharSequence.class))).thenAnswer(invocation -> {
      CharSequence value = invocation.getArgument(0);
      return value == null || value.length() == 0;
    });
    telephony = mockStatic(TelephonyUtil.class);
    telephony.when(() -> TelephonyUtil.getActiveSubscriptionInfoListSafe(context)).thenAnswer(invocation -> active);
    manager = newManager();
  }

  @After
  public void tearDown() {
    if (telephony != null) telephony.close();
    if (textUtils != null) textUtils.close();
    if (databaseFactory != null) databaseFactory.close();
    if (uris != null) uris.close();
    if (preferenceManager != null) preferenceManager.close();
  }

  @Test public void missingIdentitiesKeepDifferentDeviceSubscriptionsDistinct() {
    active = Arrays.asList(phone(11, null, ""), phone(22, "", null));
    List<SubscriptionInfoCompat> list = manager.getActiveSubscriptionInfoList();
    assertNotEquals(list.get(0).getSubscriptionId(), list.get(1).getSubscriptionId());
    assertRoute(list.get(0).getSubscriptionId(), 11);
    assertRoute(list.get(1).getSubscriptionId(), 22);
  }

  @Test public void identicalNumbersDoNotMergeDifferentIccIds() {
    active = Arrays.asList(phone(11, "number-A", "icc-A"), phone(22, "number-A", "icc-B"));
    assertDistinctAndStable();
  }

  @Test public void identicalIccIdsDoNotMergeNewDeviceSubscriptions() {
    active = Arrays.asList(phone(11, null, "icc-A"), phone(22, "", "icc-A"));
    assertDistinctAndStable();
  }

  @Test public void changedNumbersCannotOverrideExistingDeviceBindings() {
    active = Arrays.asList(phone(11, "number-A", "icc-A"), phone(22, "number-B", "icc-B"));
    manager.getActiveSubscriptionInfoList();
    int first = appId(11);
    int second = appId(22);
    active = Arrays.asList(phone(11, "number-A", "icc-A"), phone(22, "number-A", "icc-B"));
    assertRoute(second, 22);
    assertRoute(first, 11);
    assertEquals(second, appId(22));
    active = Collections.singletonList(phone(22, "number-A", "icc-B"));
    assertRoute(second, 22);
    assertEquals("icc-A", SMSecurePreferences.getIccIdForSubscriptionId(context, first));
  }

  @Test public void reversingTheListPreservesBindings() {
    active = Arrays.asList(phone(11, "number-A", "icc-A"), phone(22, "number-A", "icc-B"));
    manager.getActiveSubscriptionInfoList();
    int first = appId(11);
    int second = appId(22);
    Collections.reverse(active);
    assertRoute(first, 11);
    assertRoute(second, 22);
  }

  @Test public void newFirstEntryCannotTakeBindingOfLaterActiveDevice() {
    seed(22, 7, "old-number", "icc-A");
    active = Arrays.asList(phone(11, "old-number", "icc-A"), phone(22, null, null));
    manager.getActiveSubscriptionInfoList();
    assertEquals(7, appId(22));
    assertNotEquals(7, appId(11));
    assertRoute(7, 22);
  }

  @Test public void uniqueHistoricalIccRestoresInternalIdAfterDeviceIdChanges() {
    seed(11, 7, "old-number", "icc-A");
    stored.put("unrelated-key-and-session-marker", "unchanged");
    active = Collections.singletonList(phone(33, "new-number", "icc-A"));
    assertRoute(7, 33);
    assertEquals(7, appId(33));
    assertEquals(7, SMSecurePreferences.getLastAppSubscriptionId(context));
    assertEquals("unchanged", stored.get("unrelated-key-and-session-marker"));
  }

  @Test public void historicalNumberAloneDoesNotRestoreIdentity() {
    seed(11, 7, "number-A", "icc-A");
    active = Collections.singletonList(phone(33, "number-A", null));
    manager.getActiveSubscriptionInfoList();
    assertNotEquals(7, appId(33));
    assertFalse(manager.getActiveSubscriptionInfo(7).isPresent());
    assertEquals("icc-A", SMSecurePreferences.getIccIdForSubscriptionId(context, 7));
  }

  @Test public void differentIccWithSameHistoricalNumberGetsIndependentId() {
    seed(11, 7, "number-A", "icc-A");
    active = Collections.singletonList(phone(33, "number-A", "icc-B"));
    manager.getActiveSubscriptionInfoList();
    assertNotEquals(7, appId(33));
    assertEquals("icc-A", SMSecurePreferences.getIccIdForSubscriptionId(context, 7));
  }

  @Test public void duplicateHistoricalIccDoesNotChooseFirstStoredId() {
    seed(11, 7, "number-A", "icc-A");
    seed(22, 8, "number-B", "icc-A");
    active = Collections.singletonList(phone(33, null, "icc-A"));
    manager.getActiveSubscriptionInfoList();
    assertEquals(9, appId(33));
    assertEquals(7, appId(11));
    assertEquals(8, appId(22));
  }

  @Test public void duplicateActiveIccDoesNotAssignHistoricalIdToFirstEntry() {
    seed(11, 7, "number-A", "icc-A");
    active = Arrays.asList(phone(22, null, "icc-A"), phone(33, null, "icc-A"));
    manager.getActiveSubscriptionInfoList();
    assertNotEquals(7, appId(22));
    assertNotEquals(7, appId(33));
    assertNotEquals(appId(22), appId(33));
  }

  @Test public void existingSharedIdIsPreservedAndBlockedWithoutOverwritingEvidence() throws Exception {
    seed(11, 7, "saved-number", "saved-icc");
    SMSecurePreferences.setAppSubscriptionId(context, 22, 7);
    active = Arrays.asList(phone(11, "number-A", "icc-A"), phone(22, "number-B", "icc-B"));
    List<SubscriptionInfoCompat> list = manager.getActiveSubscriptionInfoList();
    assertEquals(7, list.get(0).getSubscriptionId());
    assertEquals(7, list.get(1).getSubscriptionId());
    assertTrue(list.get(0).hasMappingConflict());
    assertTrue(list.get(1).hasMappingConflict());
    assertFalse(manager.getActiveSubscriptionInfo(7).isPresent());
    assertFalse(manager.getActiveSubscriptionInfoFromDeviceSubscriptionId(11).isPresent());
    assertFalse(manager.getActiveSubscriptionInfoFromDeviceSubscriptionId(22).isPresent());
    assertEquals("saved-number", SMSecurePreferences.getNumberForSubscriptionId(context, 7));
    assertEquals("saved-icc", SMSecurePreferences.getIccIdForSubscriptionId(context, 7));
    active = Collections.singletonList(phone(22, null, null));
    manager = newManager();
    assertFalse(manager.getActiveSubscriptionInfo(7).isPresent());
    assertTrue(manager.getLastKnownActiveSubscriptionInfoList().get(0).hasMappingConflict());
    assertEquals(7, SMSecurePreferences.getLastAppSubscriptionId(context));
  }

  @Test public void existingSharedIdWithoutIdentifiersIsAlsoPersistentlyBlocked() {
    SMSecurePreferences.setAppSubscriptionId(context, 11, 7);
    SMSecurePreferences.setAppSubscriptionId(context, 22, 7);
    active = Arrays.asList(phone(11, null, null), phone(22, null, null));
    assertFalse(manager.getActiveSubscriptionInfo(7).isPresent());
    active = Collections.singletonList(phone(11, null, null));
    assertFalse(manager.getActiveSubscriptionInfo(7).isPresent());
    assertEquals(7, appId(11));
    assertEquals(7, appId(22));
  }

  @Test public void contradictoryIccKeepsEvidenceAndConflictSurvivesMissingIcc() {
    seed(11, 7, "saved-number", "saved-icc");
    active = Collections.singletonList(phone(11, "new-number", "new-icc"));
    assertFalse(manager.getActiveSubscriptionInfo(7).isPresent());
    assertEquals(7, appId(11));
    assertEquals("saved-number", SMSecurePreferences.getNumberForSubscriptionId(context, 7));
    assertEquals("saved-icc", SMSecurePreferences.getIccIdForSubscriptionId(context, 7));
    active = Collections.singletonList(phone(11, null, null));
    assertFalse(manager.getActiveSubscriptionInfo(7).isPresent());
  }

  @Test public void conflictedHistoricalIdCannotBeAutomaticallyReused() {
    seed(11, 7, "number-A", "icc-A");
    active = Collections.singletonList(phone(11, "number-B", "icc-B"));
    manager.getActiveSubscriptionInfoList();
    active = Collections.singletonList(phone(33, "number-A", "icc-A"));
    manager.getActiveSubscriptionInfoList();
    assertNotEquals(7, appId(33));
    assertTrue(SMSecurePreferences.hasSubscriptionMappingConflict(context, 7));
  }

  @Test public void disappearingIdentityFieldsDoNotEraseEvidenceOrChangeId() {
    seed(11, 7, "number-A", "icc-A");
    active = Collections.singletonList(phone(11, null, null));
    assertRoute(7, 11);
    assertEquals("number-A", SMSecurePreferences.getNumberForSubscriptionId(context, 7));
    assertEquals("icc-A", SMSecurePreferences.getIccIdForSubscriptionId(context, 7));
  }

  @Test public void missingSavedIccCanBeLearnedForExistingBinding() {
    seed(11, 7, "number-A", null);
    active = Collections.singletonList(phone(11, "number-A", "icc-A"));
    assertRoute(7, 11);
    assertEquals("icc-A", SMSecurePreferences.getIccIdForSubscriptionId(context, 7));
  }

  @Test public void existingIdZeroIsRetained() {
    seed(11, 0, null, "icc-A");
    active = Collections.singletonList(phone(11, null, "icc-A"));
    assertRoute(0, 11);
  }

  @Test public void repeatedReadsDoNotWriteAndInitialBatchUsesSingleApply() {
    active = Arrays.asList(phone(11, "number-A", "icc-A"), phone(22, "number-A", "icc-B"));
    manager.getActiveSubscriptionInfoList();
    verify(editor, times(1)).apply();
    clearInvocations(editor);
    manager.getActiveSubscriptionInfoList();
    manager.getActiveSubscriptionInfoList();
    verifyNoInteractions(editor);
  }

  @Test public void maximumSavedIdDoesNotCauseAnUnboundedHistoricalScan() {
    seed(11, 7, "number-A", "icc-A");
    SMSecurePreferences.setLastAppSubscriptionId(context, Integer.MAX_VALUE);
    active = Collections.singletonList(phone(11, "number-A", "icc-A"));

    assertRoute(7, 11);
  }

  @Test public void emptyListDoesNotDeleteSavedBindings() {
    seed(11, 7, "number-A", "icc-A");
    clearInvocations(editor);
    assertTrue(manager.getActiveSubscriptionInfoList().isEmpty());
    assertEquals(7, appId(11));
    verifyNoInteractions(editor);
  }

  @Test public void systemDefaultSecondSimWithSameNumberKeepsSecondRoute() {
    assertTransportRoute(false);
  }

  @Test public void manualSecondSimKeepsQueuedRouteAfterNumberCollisionAndReordering() {
    assertTransportRoute(true);
  }

  private void assertTransportRoute(boolean manual) {
    active = Arrays.asList(phone(11, "number-A", "icc-A"),
            phone(22, manual ? "number-B" : "number-A", "icc-B"));
    try (MockedStatic<SubscriptionManagerCompat> subscriptions = mockStatic(SubscriptionManagerCompat.class, CALLS_REAL_METHODS);
         MockedStatic<SmsManager> sms = mockStatic(SmsManager.class);
         MockedStatic<Permissions> permissions = mockStatic(Permissions.class);
         MockedStatic<ThemeUtil> themes = mockStatic(ThemeUtil.class)) {
      subscriptions.when(() -> SubscriptionManagerCompat.from(context)).thenReturn(manager);
      sms.when(SmsManager::getDefaultSmsSubscriptionId).thenReturn(22);
      permissions.when(() -> Permissions.hasAll(context, Manifest.permission.READ_PHONE_STATE)).thenReturn(true);
      TransportOptions options = new TransportOptions(context);
      if (manual) {
        TransportOption second = options.getEnabledTransports().stream()
                .filter(option -> option.getType() == TransportOption.Type.INSECURE_SMS)
                .skip(1).findFirst().orElseThrow(AssertionError::new);
        options.setSelectedTransport(second);
      }
      assertEquals("Synthetic SIM 22", options.getSelectedTransport().getSimName().orNull());
      OutgoingTextMessage queued = new OutgoingTextMessage(mock(Recipients.class), "synthetic body",
              options.getSelectedTransport().getSimSubscriptionId().or(-1));
      active = Arrays.asList(phone(22, "number-A", "icc-B"), phone(11, "number-A", "icc-A"));
      assertEquals(22, DualSimUtil.getSubscriptionIdFromAppSubscriptionId(context, queued.getSubscriptionId()));
      assertEquals(appId(22), queued.getSubscriptionId());
    }
  }

  @Test public void confirmedRecoveryPreservesSelectedIdentityAndSeparatesOtherSim() {
    SubscriptionRecoveryPlan plan = sharedRecoveryPlan();
    stored.put("identity-for-7", "synthetic-key-marker");
    stored.put("session-for-7", "synthetic-session-marker");
    clearInvocations(editor);
    assertTrue(manager.applyRecovery(plan, 22));
    verify(recipientPreferences).clearDefaultSubscriptionId(7);
    verify(editor, times(1)).apply();
    assertEquals(7, appId(22));
    assertEquals(8, appId(11));
    assertEquals(-1, appId(33));
    assertEquals(1, SMSecurePreferences.getSubscriptionRecoveryGeneration(context));
    assertEquals("synthetic-key-marker", stored.get("identity-for-7"));
    assertEquals("synthetic-session-marker", stored.get("session-for-7"));
    assertEquals("icc-B", SMSecurePreferences.getIccIdForSubscriptionId(context, 7));
    assertEquals("icc-A", SMSecurePreferences.getIccIdForSubscriptionId(context, 8));
    assertRoute(7, 22);
    assertRoute(8, 11);
    assertFalse(SMSecurePreferences.hasSubscriptionMappingConflict(context, 7));
    assertEquals("", SMSecurePreferences.getDeviceSubscriptions(context));
  }

  @Test public void preparingRecoveryWithoutConfirmationDoesNotReassignAnything() {
    sharedRecoveryPlan();
    Map<String, Object> before = new HashMap<>(stored);
    manager.prepareRecovery(7);
    assertEquals(before, stored);
    assertEquals(7, appId(11));
    assertEquals(7, appId(22));
    assertEquals(0, SMSecurePreferences.getSubscriptionRecoveryGeneration(context));
  }

  @Test public void recoveryRejectsRemovedSimWithoutWriting() {
    SubscriptionRecoveryPlan plan = sharedRecoveryPlan();
    active = Collections.singletonList(active.get(1));
    assertRecoveryRejected(plan, 22);
  }

  @Test public void recoveryRejectsAddedSimWithoutWriting() {
    SubscriptionRecoveryPlan plan = sharedRecoveryPlan();
    active = Arrays.asList(active.get(0), active.get(1), phone(44, "number-C", "icc-C"));
    assertRecoveryRejected(plan, 22);
  }

  @Test public void recoveryRejectsChangedIccWithoutWriting() {
    SubscriptionRecoveryPlan plan = sharedRecoveryPlan();
    active = Arrays.asList(active.get(0), phone(22, "number-B", "icc-C"));
    assertRecoveryRejected(plan, 22);
  }

  @Test public void recoveryRejectsChangedSlotWithoutWriting() {
    SubscriptionRecoveryPlan plan = sharedRecoveryPlan();
    when(active.get(1).getSimSlotIndex()).thenReturn(9);
    assertRecoveryRejected(plan, 22);
  }

  @Test public void recoveryRejectsChangedSavedBindingAndUnknownSelection() {
    SubscriptionRecoveryPlan plan = sharedRecoveryPlan();
    assertRecoveryRejected(plan, 999);
    SMSecurePreferences.setAppSubscriptionId(context, 33, 6);
    assertRecoveryRejected(plan, 22);
  }

  @Test public void recoveryRejectsReplayAfterSuccess() {
    SubscriptionRecoveryPlan plan = sharedRecoveryPlan();
    assertTrue(manager.applyRecovery(plan, 22));
    assertRecoveryRejected(plan, 11);
    assertEquals(1, SMSecurePreferences.getSubscriptionRecoveryGeneration(context));
  }

  @Test public void recoveryDoesNotPublishMappingsWhenRecipientSelectionResetFails() {
    SubscriptionRecoveryPlan plan = sharedRecoveryPlan();
    Map<String, Object> before = new HashMap<>(stored);
    doThrow(new IllegalStateException("synthetic database failure"))
            .when(recipientPreferences).clearDefaultSubscriptionId(7);

    assertThrows(IllegalStateException.class, () -> manager.applyRecovery(plan, 22));
    assertEquals(before, stored);
    assertEquals(0, SMSecurePreferences.getSubscriptionRecoveryGeneration(context));
  }

  @Test public void recoveryAcceptsReorderingButPreservesUnrelatedBindings() {
    sharedRecoveryPlan();
    seed(44, 10, "number-C", "icc-C");
    active = Arrays.asList(active.get(0), active.get(1), phone(44, "number-C", "icc-C"));
    SubscriptionRecoveryPlan plan = requiredPlan(manager.prepareRecovery(7));
    Collections.reverse(active);
    assertTrue(manager.applyRecovery(plan, 22));
    assertEquals(10, appId(44));
    assertEquals(11, appId(11));
    assertRoute(10, 44);
    assertRoute(7, 22);
  }

  @Test public void recoveryOfOneRemainingSimClearsContradictoryEvidenceOnlyAfterConfirmation() {
    seed(11, 7, "saved-number", "saved-icc");
    active = Collections.singletonList(phone(11, "new-number", "new-icc"));
    SubscriptionRecoveryPlan plan = requiredPlan(manager.prepareRecovery(7));
    assertEquals("saved-icc", SMSecurePreferences.getIccIdForSubscriptionId(context, 7));
    assertTrue(manager.applyRecovery(plan, 11));
    assertEquals("new-icc", SMSecurePreferences.getIccIdForSubscriptionId(context, 7));
    assertRoute(7, 11);
  }

  @Test public void recoveryWithUnavailableIccDoesNotKeepContradictoryOldIcc() {
    sharedRecoveryPlan();
    active = Collections.singletonList(phone(22, null, null));
    SubscriptionRecoveryPlan plan = requiredPlan(manager.prepareRecovery(7));
    assertTrue(manager.applyRecovery(plan, 22));
    assertNull(SMSecurePreferences.getIccIdForSubscriptionId(context, 7));
    assertRoute(7, 22);
  }

  private SubscriptionRecoveryPlan sharedRecoveryPlan() {
    seed(11, 7, "saved-number", "saved-icc");
    SMSecurePreferences.setAppSubscriptionId(context, 22, 7);
    SMSecurePreferences.setAppSubscriptionId(context, 33, 7);
    active = Arrays.asList(phone(11, "number-A", "icc-A"), phone(22, "number-B", "icc-B"));
    return requiredPlan(manager.prepareRecovery(7));
  }

  private void assertRecoveryRejected(SubscriptionRecoveryPlan plan, int deviceId) {
    Map<String, Object> before = new HashMap<>(stored);
    clearInvocations(editor);
    clearInvocations(recipientPreferences);
    assertFalse(manager.applyRecovery(plan, deviceId));
    assertEquals(before, stored);
    verifyNoInteractions(editor);
    verifyNoInteractions(recipientPreferences);
  }

  private void assertDistinctAndStable() {
    List<SubscriptionInfoCompat> list = manager.getActiveSubscriptionInfoList();
    int first = list.get(0).getSubscriptionId();
    int second = list.get(1).getSubscriptionId();
    assertNotEquals(first, second);
    assertRoute(first, 11);
    assertRoute(second, 22);
    assertEquals(first, appId(11));
    assertEquals(second, appId(22));
  }

  private void assertRoute(int appId, int deviceId) {
    SubscriptionInfoCompat subscription = manager.getActiveSubscriptionInfo(appId).orNull();
    assertNotNull(subscription);
    assertEquals(deviceId, subscription.getDeviceSubscriptionId());
  }

  private SubscriptionRecoveryPlan requiredPlan(
          org.whispersystems.libsignal.util.guava.Optional<SubscriptionRecoveryPlan> plan) {
    SubscriptionRecoveryPlan result = plan.orNull();
    assertNotNull(result);
    return result;
  }

  private <T> T storedValue(String key, T defaultValue, Class<T> valueType) {
    Object value = stored.get(key);
    return valueType.isInstance(value) ? valueType.cast(value) : defaultValue;
  }

  private int appId(int deviceId) {
    return SMSecurePreferences.getAppSubscriptionId(context, deviceId);
  }

  private void seed(int deviceId, int appId, String number, String iccId) {
    SMSecurePreferences.setAppSubscriptionId(context, deviceId, appId);
    if (number != null) SMSecurePreferences.setNumberForSubscriptionId(context, appId, number);
    if (iccId != null) SMSecurePreferences.setIccIdForSubscriptionId(context, appId, iccId);
  }

  private SubscriptionInfo phone(int deviceId, String number, String iccId) {
    SubscriptionInfo info = mock(SubscriptionInfo.class);
    when(info.getSubscriptionId()).thenReturn(deviceId);
    when(info.getNumber()).thenReturn(number);
    when(info.getIccId()).thenReturn(iccId);
    when(info.getDisplayName()).thenReturn("Synthetic SIM " + deviceId);
    return info;
  }

  private SubscriptionManagerCompat newManager() throws Exception {
    Constructor<SubscriptionManagerCompat> constructor = SubscriptionManagerCompat.class.getDeclaredConstructor(Context.class);
    constructor.setAccessible(true);
    return constructor.newInstance(context);
  }
}
