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

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Characterizes synthetic mapping states; does not reproduce a modem or device failure. */
@SuppressWarnings("deprecation")
public class SubscriptionMappingDiagnosticsTest {

  private final Context context = mock(Context.class);
  private MockedStatic<PreferenceManager> preferenceManager;
  private MockedStatic<TextUtils> textUtils;

  @Before
  public void setUp() {
    when(context.getApplicationContext()).thenReturn(context);

    SharedPreferences preferences = mock(SharedPreferences.class);
    SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
    Map<String, Object> stored = new HashMap<>();
    Map<String, Object> pending = new HashMap<>();

    when(preferences.edit()).thenReturn(editor);
    when(preferences.getInt(anyString(), anyInt())).thenAnswer(invocation ->
            stored.getOrDefault(invocation.getArgument(0), invocation.getArgument(1)));
    when(preferences.getString(anyString(), nullable(String.class))).thenAnswer(invocation ->
            stored.getOrDefault(invocation.getArgument(0), invocation.getArgument(1)));
    when(editor.putInt(anyString(), anyInt())).thenAnswer(invocation -> {
      pending.put(invocation.getArgument(0), invocation.getArgument(1));
      return editor;
    });
    when(editor.putString(anyString(), nullable(String.class))).thenAnswer(invocation -> {
      pending.put(invocation.getArgument(0), invocation.getArgument(1));
      return editor;
    });
    doAnswer(invocation -> {
      stored.putAll(pending);
      pending.clear();
      return null;
    }).when(editor).apply();

    preferenceManager = mockStatic(PreferenceManager.class);
    preferenceManager.when(() -> PreferenceManager.getDefaultSharedPreferences(context))
            .thenReturn(preferences);
    textUtils = mockStatic(TextUtils.class);
    textUtils.when(() -> TextUtils.isEmpty(nullable(CharSequence.class))).thenAnswer(invocation -> {
      CharSequence value = invocation.getArgument(0);
      return value == null || value.length() == 0;
    });
  }

  @After
  public void tearDown() {
    if (textUtils != null) textUtils.close();
    if (preferenceManager != null) preferenceManager.close();
  }

  @Test
  public void missingNumbersAndIccIdsKeepDistinctDeviceSubscriptionsDistinct() {
    SubscriptionInfoCompat first = subscription(11, 1, null, "");
    SubscriptionInfoCompat second = subscription(22, 2, "", null);

    assertNotEquals(first.getSubscriptionId(), second.getSubscriptionId());
    assertEquals(first.getSubscriptionId(), SMSecurePreferences.getAppSubscriptionId(context, 11));
    assertEquals(second.getSubscriptionId(), SMSecurePreferences.getAppSubscriptionId(context, 22));
  }

  @Test
  public void rebuildingInReverseOrderRetainsPersistedDeviceMappings() {
    SubscriptionInfoCompat first = subscription(11, 1, null, null);
    SubscriptionInfoCompat second = subscription(22, 2, null, null);

    SubscriptionInfoCompat secondReloaded = subscription(22, 2, null, null);
    SubscriptionInfoCompat firstReloaded = subscription(11, 1, null, null);

    assertEquals(first.getSubscriptionId(), firstReloaded.getSubscriptionId());
    assertEquals(second.getSubscriptionId(), secondReloaded.getSubscriptionId());
    assertNotEquals(firstReloaded.getSubscriptionId(), secondReloaded.getSubscriptionId());

    SubscriptionManagerCompat manager = managerWith(secondReloaded, firstReloaded);
    assertSame(firstReloaded, manager.getActiveSubscriptionInfo(first.getSubscriptionId()).get());
    assertSame(secondReloaded, manager.getActiveSubscriptionInfo(second.getSubscriptionId()).get());
  }

  @Test
  public void identicalNonemptyNumbersCollapseEvenWithDifferentIccIds() {
    SubscriptionInfoCompat first = subscription(11, 1, "synthetic-number", "synthetic-icc-a");
    SubscriptionInfoCompat second = subscription(22, 2, "synthetic-number", "synthetic-icc-b");

    assertAmbiguousMappingResolvesFirstEntry(first, second);
  }

  @Test
  public void identicalNonemptyIccIdsCollapseWhenNumbersAreUnavailable() {
    SubscriptionInfoCompat first = subscription(11, 1, null, "synthetic-icc");
    SubscriptionInfoCompat second = subscription(22, 2, "", "synthetic-icc");

    assertAmbiguousMappingResolvesFirstEntry(first, second);
  }

  @Test
  public void preexistingAmbiguousDeviceMappingRemainsAmbiguousWithoutIdentifiers() {
    SMSecurePreferences.setAppSubscriptionId(context, 11, 7);
    SMSecurePreferences.setAppSubscriptionId(context, 22, 7);

    SubscriptionInfoCompat first = subscription(11, 1, null, null);
    SubscriptionInfoCompat second = subscription(22, 2, null, null);

    assertEquals(7, first.getSubscriptionId());
    assertAmbiguousMappingResolvesFirstEntry(first, second);
  }

  private SubscriptionInfoCompat subscription(int deviceId, int slot, String number, String iccId) {
    return new SubscriptionInfoCompat(context, deviceId, "Synthetic SIM " + slot,
            number, iccId, slot, 0, 0, false);
  }

  private void assertAmbiguousMappingResolvesFirstEntry(SubscriptionInfoCompat first,
                                                      SubscriptionInfoCompat second) {
    assertNotEquals(first.getDeviceSubscriptionId(), second.getDeviceSubscriptionId());
    assertEquals(first.getSubscriptionId(), second.getSubscriptionId());
    assertEquals(first.getSubscriptionId(),
            SMSecurePreferences.getAppSubscriptionId(context, second.getDeviceSubscriptionId()));

    SubscriptionManagerCompat manager = managerWith(first, second);
    assertSame(first, manager.getActiveSubscriptionInfo(second.getSubscriptionId()).get());

    doReturn(Arrays.asList(second, first)).when(manager).getActiveSubscriptionInfoList();
    assertSame(second, manager.getActiveSubscriptionInfo(first.getSubscriptionId()).get());
  }

  private SubscriptionManagerCompat managerWith(SubscriptionInfoCompat... subscriptions) {
    SubscriptionManagerCompat manager = mock(SubscriptionManagerCompat.class, CALLS_REAL_METHODS);
    doReturn(Arrays.asList(subscriptions)).when(manager).getActiveSubscriptionInfoList();
    return manager;
  }
}
