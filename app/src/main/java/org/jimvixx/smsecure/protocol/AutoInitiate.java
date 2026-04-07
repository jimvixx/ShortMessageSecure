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

package org.jimvixx.smsecure.protocol;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.SessionUtil;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;

import java.util.Locale;

public class AutoInitiate {

  public static final String WHITESPACE_TAG = " {13}";
  private static final String TAG = AutoInitiate.class.getSimpleName();
  private static final String PREFS_NAME = "auto_initiate_prefs";

  public static boolean isTaggableMessage(String message) {
    return message.matches(".*\\S.*") &&
            message.replaceAll("\\s+$", "").length() + WHITESPACE_TAG.length() <= 158;
  }

  public static boolean isTaggableDestination(Recipients recipients) {
    if (recipients.isGroupRecipient()) {
      return false;
    }

    PhoneNumberUtil util = PhoneNumberUtil.getInstance();

    try {
      PhoneNumber num = util.parse(
              recipients.getPrimaryRecipient().getNumber(),
              Locale.getDefault().getCountry());

      PhoneNumberType type = util.getNumberType(num);

      Log.d(TAG, "Number type: " + type);

      return type == PhoneNumberType.FIXED_LINE ||
              type == PhoneNumberType.MOBILE ||
              type == PhoneNumberType.FIXED_LINE_OR_MOBILE;

    } catch (NumberParseException e) {
      Log.w(TAG, "Couldn't get number type (country: " + Locale.getDefault().getCountry() + ")");
      return false;
    }
  }

  public static boolean isTagged(String message) {
    if (message == null) {
      return false;
    }

    int tagStart = message.length() - WHITESPACE_TAG.length();

    if (tagStart <= 0) {
      return false;
    }

    return message.endsWith(WHITESPACE_TAG) &&
            !Character.isWhitespace(message.charAt(tagStart - 1));
  }

  public static String getTaggedMessage(String message) {
    return message.replaceAll("\\s+$", "") + WHITESPACE_TAG;
  }

  public static String stripTag(String message) {
    if (isTagged(message)) {
      return message.substring(0, message.length() - WHITESPACE_TAG.length());
    }

    return message;
  }

  public static void exemptThread(Context context, long threadId) {
    SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

    sp.edit()
            .putBoolean("pref_thread_auto_init_exempt_" + threadId, true)
            .apply();
  }

  public static boolean isValidAutoInitiateSituation(Context context,
                                                     MasterSecret masterSecret,
                                                     Recipient recipient,
                                                     String message,
                                                     long threadId) {
    return isTagged(message) &&
            isThreadQualified(context, threadId) &&
            isExchangeQualified(context, masterSecret, recipient);
  }

  private static boolean isThreadQualified(Context context, long threadId) {
    SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    return !sp.getBoolean("pref_thread_auto_init_exempt_" + threadId, false);
  }

  private static boolean isExchangeQualified(Context context,
                                             MasterSecret masterSecret,
                                             Recipient recipient) {
    return !SessionUtil.hasSession(
            context,
            masterSecret,
            recipient.getNumber(),
            SubscriptionManagerCompat.from(context).getActiveSubscriptionInfoList());
  }
}