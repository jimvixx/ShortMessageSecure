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

package org.jimvixx.smsecure.jobs.requirements;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 31)
public class ServiceRequirementProviderTelephonyCallbackTest {

  @Test
  public void currentServiceStateNotifiesAfterStabilityDelay() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assumeTrue(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
            == PackageManager.PERMISSION_GRANTED);

    TelephonyManager telephonyManager =
            (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    ServiceState currentState = telephonyManager.getServiceState();
    assumeTrue(currentState != null && currentState.getState() == ServiceState.STATE_IN_SERVICE);

    CountDownLatch requirementChanged = new CountDownLatch(1);
    ServiceRequirementProvider provider = new ServiceRequirementProvider(context);
    provider.setListener(requirementChanged::countDown);

    ServiceRequirementProvider.listenForServiceState();

    assertTrue("Stable service callback was not delivered",
            requirementChanged.await(15, TimeUnit.SECONDS));
    assertTrue("Service listener should be unregistered after the stability delay",
            ServiceRequirementProvider.isServiceReady());
  }
}
