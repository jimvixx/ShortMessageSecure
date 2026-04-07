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

import android.content.Intent;
import android.net.Uri;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.RecipientPreferenceDatabase;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.sms.MessageSender;
import org.jimvixx.smsecure.sms.OutgoingTextMessage;
import org.jimvixx.smsecure.util.Rfc5724Uri;
import org.whispersystems.libsignal.util.guava.Optional;

public class QuickResponseService extends MasterSecretIntentService {

  private static final String TAG = QuickResponseService.class.getSimpleName();

  public QuickResponseService() {
    super("QuickResponseService");
  }

  @Override
  protected void onHandleIntent(Intent intent, @Nullable MasterSecret masterSecret) {
    if (intent == null) return;

    if (!TelephonyManager.ACTION_RESPOND_VIA_MESSAGE.equals(intent.getAction())) {
      Log.w(TAG, "Received unknown intent action: " + intent.getAction());
      return;
    }

    if (masterSecret == null) {
      Log.w(TAG, "Quick response requested while app is locked.");
      Toast.makeText(this,
              R.string.QuickResponseService_quick_response_unavailable_when_SMSecure_is_locked,
              Toast.LENGTH_LONG).show();
      return;
    }

    final String content = intent.getStringExtra(Intent.EXTRA_TEXT);
    if (TextUtils.isEmpty(content)) {
      Log.w(TAG, "Quick response content is empty.");
      return;
    }

    try {
      final String dataString = intent.getDataString();
      if (TextUtils.isEmpty(dataString)) {
        Log.w(TAG, "Quick response intent has empty dataString.");
        Toast.makeText(this,
                R.string.QuickResponseService_problem_sending_message,
                Toast.LENGTH_LONG).show();
        return;
      }

      final Rfc5724Uri rfcUri = new Rfc5724Uri(dataString);
      if (!rfcUri.isValid()) {
        Log.w(TAG, "Invalid quick response URI: " + dataString);
        Toast.makeText(this,
                R.string.QuickResponseService_problem_sending_message,
                Toast.LENGTH_LONG).show();
        return;
      }

      String numbers = rfcUri.getPath();
      if (TextUtils.isEmpty(numbers)) {
        Log.w(TAG, "Quick response URI path is empty: " + dataString);
        Toast.makeText(this,
                R.string.QuickResponseService_problem_sending_message,
                Toast.LENGTH_LONG).show();
        return;
      }

      if (numbers.contains("%")) {
        numbers = Uri.decode(numbers);
      }

      final Recipients recipients =
              RecipientFactory.getRecipientsFromString(this, numbers, false);

      final Optional<RecipientPreferenceDatabase.RecipientsPreferences> preferences =
              DatabaseFactory.getRecipientPreferenceDatabase(this)
                      .getRecipientsPreferences(recipients.getIds());

      final int subscriptionId =
              preferences.isPresent()
                      ? preferences.get().getDefaultSubscriptionId().or(-1)
                      : -1;

      MessageSender.send(this,
              masterSecret,
              new OutgoingTextMessage(recipients, content, subscriptionId),
              -1,
              false);

    } catch (RuntimeException e) {
      Log.w(TAG, "Problem sending quick response.", e);
      Toast.makeText(this,
              R.string.QuickResponseService_problem_sending_message,
              Toast.LENGTH_LONG).show();
    }
  }
}
