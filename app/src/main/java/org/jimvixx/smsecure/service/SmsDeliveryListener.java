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
import android.telephony.SmsManager;
import android.telephony.SmsMessage;

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
        String sendAttemptId = intent.getStringExtra("send_attempt_id");
        int partIndex = intent.getIntExtra("part_index", 0);
        int partsTotal = intent.getIntExtra("parts_total", 1);
        // Allowlist diagnostic values: never dump arbitrary extras or message data.
        try {
          String diagnostics = "SMS sent result: message=" + messageId
                  + " result=" + resultCode + " part=" + partIndex + "/" + partsTotal
                  + " errorCode=" + (intent.hasExtra("errorCode")
                          ? Integer.toString(intent.getIntExtra("errorCode", 0)) : "absent")
                  + " noDefault=" + (intent.hasExtra("noDefault")
                          ? Boolean.toString(intent.getBooleanExtra("noDefault", false)) : "absent");
          if (resultCode == android.app.Activity.RESULT_OK) {
            Log.i(TAG, diagnostics);
          } else {
            Log.e(TAG, diagnostics);
          }
        } catch (RuntimeException diagnosticFailure) {
          Log.w(TAG, "SMS result diagnostics unavailable: " + diagnosticFailure.getClass().getSimpleName());
        }
        boolean connectivityFailure = resultCode == SmsManager.RESULT_ERROR_NO_SERVICE ||
                                      resultCode == SmsManager.RESULT_ERROR_RADIO_OFF;

        if (sendAttemptId != null &&
            !SmsSendAttemptTracker.shouldProcessCallback(context, messageId, sendAttemptId,
                                                         partIndex, partsTotal, connectivityFailure)) {
          Log.w(TAG, "Ignoring duplicate or stale sent callback for message=" + messageId +
                  " attempt=" + sendAttemptId + " part=" + partIndex);
          break;
        }

        jobManager.add(new SmsSentJob(context, messageId, SENT_SMS_ACTION, resultCode));
        break;

      case DELIVERED_SMS_ACTION:
        // Observe the network status without changing the existing delivery heuristic.
        logDeliveryReport(intent, resultCode);
        Log.w(TAG, "DELIVERED: result=" + resultCode +
                " extras=" + (intent.getExtras() != null ? intent.getExtras().keySet() : "null"));

        jobManager.add(new SmsSentJob(context, messageId, DELIVERED_SMS_ACTION, resultCode));
        break;

      default:
        Log.w(TAG, "Unknown action: " + action);
    }
  }

  private static void logDeliveryReport(Intent intent, int resultCode) {
    try {
      byte[] pdu = intent.getByteArrayExtra("pdu");
      String format = intent.getStringExtra("format");
      boolean knownFormat = "3gpp".equals(format) || "3gpp2".equals(format);
      // Never log raw PDU, arbitrary extras, addresses or parser exception messages.
      String safeFormat = knownFormat ? format : (format == null ? "absent" : "unknown");
      SmsMessage report = pdu != null && knownFormat
              ? SmsMessage.createFromPdu(pdu, format) : null;
      Log.i(TAG, "SMS delivery diagnostic: message=" + intent.getLongExtra("message_id", -1)
              + " result=" + resultCode
              + " part=" + intent.getIntExtra("part_index", -1)
              + "/" + intent.getIntExtra("parts_total", -1)
              + " format=" + safeFormat
              + " pduPresent=" + (pdu != null)
              + " parsed=" + (report != null)
              + " statusReport=" + (report != null && report.isStatusReportMessage())
              + " status=" + (report == null ? "unavailable" : Integer.toString(report.getStatus())));
      // SmsSentJob logs deltaMs against the stored send time for this message.
    } catch (RuntimeException diagnosticFailure) {
      Log.w(TAG, "SMS delivery diagnostic unavailable: "
              + diagnosticFailure.getClass().getSimpleName());
    }
  }
}
