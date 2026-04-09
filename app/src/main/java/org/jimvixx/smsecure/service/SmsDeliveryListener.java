/*
 * Copyright (C) 2015 Open Whisper Systems
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

package org.jimvixx.smsecure.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.jimvixx.smsecure.ApplicationContext;
import org.jimvixx.smsecure.jobs.SmsSentJob;
import org.jimvixx.smsecure.logging.Log;
import org.whispersystems.jobqueue.JobManager;

public class SmsDeliveryListener extends BroadcastReceiver {

  public static final String SENT_SMS_ACTION = "org.jimvixx.smsecure.SendReceiveService.SENT_SMS_ACTION";
  public static final String DELIVERED_SMS_ACTION = "org.jimvixx.smsecure.SendReceiveService.DELIVERED_SMS_ACTION";
  private static final String TAG = SmsDeliveryListener.class.getSimpleName();

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null) return;

    final String action = intent.getAction();
    if (action == null) {
      Log.w(TAG, "Null action!");
      return;
    }

    // Debug extras (optional but useful during bring-up).
    Bundle extras = intent.getExtras();
    if (extras != null) {
      for (String k : extras.keySet()) {
        Object v = extras.get(k);
        Log.w(TAG, "extra[" + k + "]=" + (v != null ? v.getClass() : "null"));
      }
    }

    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();

    long messageId = intent.getLongExtra("message_id", -1);
    int resultCode = getResultCode();

    if (messageId <= 0) {
      Log.w(TAG, "Missing/invalid message_id for action=" + action + " result=" + resultCode);
      return;
    }

    switch (action) {
      case SENT_SMS_ACTION:
        jobManager.add(new SmsSentJob(context, messageId, SENT_SMS_ACTION, resultCode));
        break;

      case DELIVERED_SMS_ACTION:
        // Do NOT try to validate PDU here. We accept/reject delivery in SmsSentJob using a time heuristic.
        Log.w(TAG, "DELIVERED: result=" + resultCode +
                " extras=" + (intent.getExtras() != null ? intent.getExtras().keySet() : "null"));

        jobManager.add(new SmsSentJob(context, messageId, DELIVERED_SMS_ACTION, resultCode));
        break;

      default:
        Log.w(TAG, "Unknown action: " + action);
    }
  }
}