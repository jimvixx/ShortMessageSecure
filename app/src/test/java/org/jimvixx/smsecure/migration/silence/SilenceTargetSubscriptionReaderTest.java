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

package org.jimvixx.smsecure.migration.silence;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.preference.PreferenceManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import org.junit.Test;
import org.mockito.MockedStatic;
import java.util.Collections;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SilenceTargetSubscriptionReaderTest {
  @Test public void missingPermissionDoesNotTouchTelephonyOrPreferences() {
    Context context = mock(Context.class);
    when(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)).thenReturn(PackageManager.PERMISSION_DENIED);
    assertEquals(SilenceTargetSubscriptions.Status.PERMISSION_REQUIRED,
        SilenceTargetSubscriptionReader.read(context).getStatus());
    verify(context).checkSelfPermission(Manifest.permission.READ_PHONE_STATE);
    verifyNoMoreInteractions(context);
  }
  @Test public void revokedPermissionAndUnavailableServiceHaveDistinctResults() {
    Context context = permittedContext();
    assertEquals(SilenceTargetSubscriptions.Status.UNAVAILABLE, SilenceTargetSubscriptionReader.read(context).getStatus());
    SubscriptionManager manager = mock(SubscriptionManager.class);
    when(context.getSystemService(SubscriptionManager.class)).thenReturn(manager);
    when(manager.getActiveSubscriptionInfoList()).thenThrow(new SecurityException());
    assertEquals(SilenceTargetSubscriptions.Status.PERMISSION_REQUIRED, SilenceTargetSubscriptionReader.read(context).getStatus());
    doThrow(new IllegalStateException()).when(manager).getActiveSubscriptionInfoList();
    assertEquals(SilenceTargetSubscriptions.Status.UNAVAILABLE, SilenceTargetSubscriptionReader.read(context).getStatus());
  }
  @Test public void readsOnlyIdsAndPreferenceSnapshotWithoutEditing() {
    Context context = permittedContext();
    SubscriptionManager manager = mock(SubscriptionManager.class);
    SubscriptionInfo info = mock(SubscriptionInfo.class);
    SharedPreferences preferences = mock(SharedPreferences.class);
    SharedPreferences keys = mock(SharedPreferences.class);
    when(context.getSharedPreferences(org.jimvixx.smsecure.crypto.MasterSecretUtil.PREFERENCES_NAME, Context.MODE_PRIVATE)).thenReturn(keys);
    when(context.getSystemService(SubscriptionManager.class)).thenReturn(manager);
    when(manager.getActiveSubscriptionInfoList()).thenReturn(Collections.singletonList(info));
    when(info.getSubscriptionId()).thenReturn(42);
    doReturn(Collections.singletonMap("app_subscription_id_for_device_subscription_id_42", 7)).when(preferences).getAll();
    try (MockedStatic<PreferenceManager> factory = mockStatic(PreferenceManager.class)) {
      factory.when(() -> PreferenceManager.getDefaultSharedPreferences(context)).thenReturn(preferences);
      assertEquals(Collections.singletonMap(42, 7), SilenceTargetSubscriptionReader.read(context).getCandidates());
      verify(preferences).getAll(); verifyNoMoreInteractions(preferences);
      verify(keys).contains(org.jimvixx.smsecure.crypto.IdentityKeyUtil.getIdentityPublicKeyDjbPref(7));
      verify(keys).contains(org.jimvixx.smsecure.crypto.IdentityKeyUtil.getIdentityPrivateKeyDjbPref(7));
      verifyNoMoreInteractions(keys);
      when(keys.contains(org.jimvixx.smsecure.crypto.IdentityKeyUtil.getIdentityPrivateKeyDjbPref(7))).thenReturn(true);
      SilenceTargetSubscriptions occupied = SilenceTargetSubscriptionReader.read(context);
      assertTrue(occupied.getCandidates().isEmpty()); assertEquals(1, occupied.getOccupiedCount());
      when(keys.contains(org.jimvixx.smsecure.crypto.IdentityKeyUtil.getIdentityPrivateKeyDjbPref(7))).thenReturn(false);
      when(keys.contains(org.jimvixx.smsecure.crypto.IdentityKeyUtil.getIdentityPublicKeyDjbPref(7))).thenReturn(true);
      assertEquals(1, SilenceTargetSubscriptionReader.read(context).getOccupiedCount());
      verify(info, atLeastOnce()).getSubscriptionId(); verifyNoMoreInteractions(info);
    }
  }
  private static Context permittedContext() {
    Context context = mock(Context.class);
    when(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)).thenReturn(PackageManager.PERMISSION_GRANTED);
    return context;
  }
}
