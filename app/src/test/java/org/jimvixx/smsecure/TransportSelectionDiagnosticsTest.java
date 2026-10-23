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

package org.jimvixx.smsecure;

import android.content.Context;

import org.jimvixx.smsecure.logging.SmsSendDiagnostics.SelectionSource;
import org.jimvixx.smsecure.permissions.Permissions;
import org.jimvixx.smsecure.util.ThemeUtil;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.whispersystems.libsignal.util.guava.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.*;

public class TransportSelectionDiagnosticsTest {
  @Test public void diagnosticOriginFollowsExistingSelectionAndNeverRefreshesSimState() {
    Context context = mock(Context.class);
    SubscriptionManagerCompat manager = mock(SubscriptionManagerCompat.class);
    try (MockedStatic<SubscriptionManagerCompat> subscriptions = mockStatic(SubscriptionManagerCompat.class);
         MockedStatic<ThemeUtil> themes = mockStatic(ThemeUtil.class);
         MockedStatic<Permissions> permissions = mockStatic(Permissions.class)) {
      subscriptions.when(() -> SubscriptionManagerCompat.getDefaultMessagingSubscriptionId(context))
              .thenReturn(Optional.of(10));
      subscriptions.when(() -> SubscriptionManagerCompat.from(context)).thenReturn(manager);
      TransportOptions options = new TransportOptions(context);
      TransportOption first = transport(10);
      TransportOption second = transport(20);
      options.getEnabledTransports().add(first);
      options.getEnabledTransports().add(second);
      subscriptions.clearInvocations();

      assertSelection(options, first, SelectionSource.SYSTEM_DEFAULT);
      options.setDefaultSubscriptionId(Optional.of(20), true);
      assertSelection(options, second, SelectionSource.RECIPIENT_PREFERENCE);
      options.setSelectedTransport(first);
      assertSelection(options, first, SelectionSource.MANUAL);
      options.setDefaultSubscriptionId(Optional.of(20), false);
      assertSelection(options, first, SelectionSource.MANUAL);
      options.setSelectedTransport(null);
      assertSelection(options, second, SelectionSource.SYSTEM_DEFAULT);
      options.setDefaultSubscriptionId(Optional.of(99), true);
      assertSelection(options, first, SelectionSource.FALLBACK);
      options.setDefaultSubscriptionId(Optional.absent(), false);
      assertSelection(options, first, SelectionSource.FALLBACK);
      subscriptions.verifyNoInteractions();
      verifyNoInteractions(manager);
    }
  }

  @Test public void absentPreferenceDoesNotRelabelAnExistingPreferenceAsSystemDefault() {
    Context context = mock(Context.class);
    try (MockedStatic<SubscriptionManagerCompat> subscriptions = mockStatic(SubscriptionManagerCompat.class);
         MockedStatic<ThemeUtil> themes = mockStatic(ThemeUtil.class);
         MockedStatic<Permissions> permissions = mockStatic(Permissions.class)) {
      subscriptions.when(() -> SubscriptionManagerCompat.getDefaultMessagingSubscriptionId(context))
              .thenReturn(Optional.absent());
      TransportOptions options = new TransportOptions(context);
      TransportOption first = transport(10);
      options.getEnabledTransports().add(first);
      assertSelection(options, first, SelectionSource.FALLBACK);
      options.setDefaultSubscriptionId(Optional.of(10), true);
      options.setDefaultSubscriptionId(Optional.of(-1), false);
      assertSelection(options, first, SelectionSource.RECIPIENT_PREFERENCE);
      options.setDefaultSubscriptionId(Optional.absent(), false);
      assertSelection(options, first, SelectionSource.RECIPIENT_PREFERENCE);
    }
  }

  @Test public void resetReportsTheDefaultActuallyRetainedByEachExistingBranch() {
    Context context = mock(Context.class);
    try (MockedStatic<SubscriptionManagerCompat> subscriptions = mockStatic(SubscriptionManagerCompat.class);
         MockedStatic<ThemeUtil> themes = mockStatic(ThemeUtil.class);
         MockedStatic<Permissions> permissions = mockStatic(Permissions.class)) {
      subscriptions.when(() -> SubscriptionManagerCompat.getDefaultMessagingSubscriptionId(context))
              .thenReturn(Optional.of(10));
      TransportOptions options = new TransportOptions(context);
      TransportOption first = transport(10);
      TransportOption second = transport(20);
      options.getEnabledTransports().add(first);
      options.getEnabledTransports().add(second);
      options.setDefaultSubscriptionId(Optional.of(20), true);
      options.reset();
      options.getEnabledTransports().add(first);
      options.getEnabledTransports().add(second);
      assertSelection(options, first, SelectionSource.SYSTEM_DEFAULT);

      options.setDefaultSubscriptionId(Optional.of(20), true);
      options.setSelectedTransport(first);
      options.reset();
      options.getEnabledTransports().add(first);
      options.getEnabledTransports().add(second);
      assertSelection(options, second, SelectionSource.RECIPIENT_PREFERENCE);
    }
  }

  @Test public void recoveryResetDiscardsOldManualAndRecipientSelections() {
    Context context = mock(Context.class);
    try (MockedStatic<SubscriptionManagerCompat> subscriptions = mockStatic(SubscriptionManagerCompat.class);
         MockedStatic<ThemeUtil> themes = mockStatic(ThemeUtil.class);
         MockedStatic<Permissions> permissions = mockStatic(Permissions.class)) {
      subscriptions.when(() -> SubscriptionManagerCompat.getDefaultMessagingSubscriptionId(context))
              .thenReturn(Optional.of(10));
      TransportOptions options = new TransportOptions(context);
      TransportOption old = transport(20);
      options.getEnabledTransports().add(old);
      options.setDefaultSubscriptionId(Optional.of(20), true);
      options.setSelectedTransport(old);
      options.resetAfterSimRecovery();
      TransportOption fresh = transport(10);
      options.getEnabledTransports().add(fresh);
      options.getEnabledTransports().add(transport(20));
      assertSelection(options, fresh, SelectionSource.SYSTEM_DEFAULT);
      org.junit.Assert.assertFalse(options.isManualSelection());
    }
  }

  private static TransportOption transport(int appSub) {
    TransportOption option = mock(TransportOption.class);
    when(option.getType()).thenReturn(TransportOption.Type.INSECURE_SMS);
    when(option.getSimSubscriptionId()).thenReturn(Optional.of(appSub));
    return option;
  }

  private static void assertSelection(TransportOptions options, TransportOption selected,
                                      SelectionSource source) {
    assertSame(selected, options.getSelectedTransport());
    assertEquals(source, options.getSelectionSource());
    assertSame(selected, options.getSelectedTransport());
  }
}
