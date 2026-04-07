/*
 * Copyright (C) 2011 Whisper Systems
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
import android.provider.Telephony;
import android.telephony.SmsMessage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.ApplicationContext;
import org.jimvixx.smsecure.jobs.SmsReceiveJob;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.protocol.WirePrefix;
import org.jimvixx.smsecure.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SmsListener extends BroadcastReceiver {

  private static final String TAG = "SmsListener";

  private static final String SMS_RECEIVED_ACTION  = Telephony.Sms.Intents.SMS_RECEIVED_ACTION;
  private static final String SMS_DELIVERED_ACTION = Telephony.Sms.Intents.SMS_DELIVER_ACTION;

  private static final String EXTRA_PDUS         = "pdus";
  private static final String EXTRA_FORMAT       = "format";
  private static final String EXTRA_SUBSCRIPTION = "subscription";

  private static final class ParsedSmsData {
    private final @NonNull Object[] rawPdus;
    private final @NonNull SmsMessage[] messages;
    private final @Nullable String body;

    private ParsedSmsData(@NonNull Object[] rawPdus,
                          @NonNull SmsMessage[] messages,
                          @Nullable String body) {
      this.rawPdus  = rawPdus;
      this.messages = messages;
      this.body     = body;
    }
  }

  private boolean isExemption(@NonNull SmsMessage message, @NonNull String messageBody) {
    // Ignore CLASS0 ("flash") messages.
    if (message.getMessageClass() == SmsMessage.MessageClass.CLASS_0) {
      return true;
    }

    String originatingAddress = message.getOriginatingAddress();

    if (originatingAddress == null) {
      return false;
    }

    return originatingAddress.length() < 7 &&
            (messageBody.toUpperCase(Locale.ROOT).startsWith("//ANDROID:") || // Sprint Visual Voicemail
                    messageBody.startsWith("//BREW:"));                              // BREW = Binary Runtime Environment for Wireless
  }

  @Nullable
  private ParsedSmsData parseSmsData(@Nullable Intent intent) {
    if (intent == null) {
      return null;
    }

    Bundle extras = intent.getExtras();

    if (extras == null) {
      return null;
    }

    Object[] pdus = (Object[]) extras.get(EXTRA_PDUS);

    if (pdus == null || pdus.length == 0) {
      return null;
    }

    String format = intent.getStringExtra(EXTRA_FORMAT);

    List<SmsMessage> parsedMessages = new ArrayList<>(pdus.length);
    StringBuilder bodyBuilder = new StringBuilder();

    for (Object pdu : pdus) {
      SmsMessage message = createSmsMessage(pdu, format);

      if (message == null) {
        continue;
      }

      parsedMessages.add(message);

      String bodyPart = message.getDisplayMessageBody();
      if (bodyPart != null) {
        bodyBuilder.append(bodyPart);
      }
    }

    if (parsedMessages.isEmpty()) {
      return null;
    }

    String body = bodyBuilder.length() > 0 ? bodyBuilder.toString() : null;

    return new ParsedSmsData(
            pdus,
            parsedMessages.toArray(new SmsMessage[0]),
            body
    );
  }

  @Nullable
  private SmsMessage createSmsMessage(@NonNull Object pdu, @Nullable String format) {
    if (!(pdu instanceof byte[])) {
      return null;
    }

    try {
      return SmsMessage.createFromPdu((byte[]) pdu, format);
    } catch (RuntimeException e) {
      Log.w(TAG, "Failed to create SmsMessage from PDU", e);
      return null;
    }
  }

  private boolean isRelevant(@NonNull Context context,
                             @NonNull Intent intent,
                             @NonNull ParsedSmsData smsData) {
    SmsMessage firstMessage = smsData.messages[0];
    String messageBody = smsData.body;

    if (messageBody == null) {
      return false;
    }

    if (isExemption(firstMessage, messageBody)) {
      return false;
    }

    if (ApplicationMigrationService.isDatabaseNotImported(context)) {
      return false;
    }

    if (SMS_RECEIVED_ACTION.equals(intent.getAction()) && Util.isDefaultSmsProvider(context)) {
      return false;
    }

    return WirePrefix.isPrefixedMessage(messageBody);
  }

  @Override
  public void onReceive(Context context, Intent intent) {
    Log.w(TAG, "Got SMS broadcast...");

    if (intent == null) {
      return;
    }

    String action = intent.getAction();

    if (action == null) {
      return;
    }

    ParsedSmsData smsData = parseSmsData(intent);

    if (smsData == null) {
      return;
    }

    boolean shouldProcess =
            SMS_DELIVERED_ACTION.equals(action) ||
                    (SMS_RECEIVED_ACTION.equals(action) && isRelevant(context, intent, smsData));

    if (!shouldProcess) {
      return;
    }

    Bundle extras = intent.getExtras();
    int subscriptionId = extras != null ? extras.getInt(EXTRA_SUBSCRIPTION, -1) : -1;

    ApplicationContext.getInstance(context)
            .getJobManager()
            .add(new SmsReceiveJob(context, smsData.rawPdus, subscriptionId));

    abortBroadcast();
  }
}