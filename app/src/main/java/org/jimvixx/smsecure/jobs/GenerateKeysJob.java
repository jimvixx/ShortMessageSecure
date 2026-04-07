/*
 * Copyright (C) 2010 Open Whisper Systems
 * Copyright (C) 2025 Jimvixx
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
import org.jimvixx.smsecure.jobs.requirements.MasterSecretRequirement;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.util.dualsim.DualSimUtil;
import org.jimvixx.smsecure.util.dualsim.SubscriptionInfoCompat;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;
import org.whispersystems.jobqueue.JobParameters;

import java.util.List;

public class GenerateKeysJob extends MasterSecretJob {
  private static final String TAG = GenerateKeysJob.class.getSimpleName();

  public GenerateKeysJob(Context context) {
    super(context, JobParameters.newBuilder()
            .withPersistence()
            .withRequirement(new MasterSecretRequirement(context))
            .create());

  }

  @Override
  public void onAdded() {
  }

  @Override
  public void onRun(MasterSecret masterSecret) {
    Log.w(TAG, "onRun()");
    List<SubscriptionInfoCompat> activeSubscriptions = SubscriptionManagerCompat.from(context).updateActiveSubscriptionInfoList();
    DualSimUtil.generateKeysIfDoNotExist(context, masterSecret, activeSubscriptions);
  }

  @Override
  public boolean onShouldRetryThrowable(Exception exception) {
    return false;
  }

  @Override
  public void onCanceled() {
  }
}
