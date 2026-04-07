/*
 * Copyright (C) 2014 Open Whisper Systems
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

package org.jimvixx.smsecure.util;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

import org.whispersystems.libsignal.logging.Log;

/**
 * Phone number formats are a pain.
 *
 * @author Moxie Marlinspike
 *
 */
public class PhoneNumberFormatter {

  private static final String TAG = PhoneNumberFormatter.class.getSimpleName();

  private static String impreciseFormatNumber(String number, String localNumber) {
    number = number.replaceAll("[^0-9+]", "");

    if (number.charAt(0) == '+')
      return number;

    if (localNumber.charAt(0) == '+')
      localNumber = localNumber.substring(1);

    if (localNumber.length() == number.length() || number.length() > localNumber.length())
      return "+" + number;

    int difference = localNumber.length() - number.length();

    return "+" + localNumber.substring(0, difference) + number;
  }

  public static String formatNumber(String number, String localNumber)
          throws InvalidNumberException {
    if (number.contains("@")) {
      throw new InvalidNumberException("Possible attempt to use email address.");
    }

    number = number.replaceAll("[^0-9+]", "");

    if (number.length() == 0) {
      throw new InvalidNumberException("No valid characters found.");
    }

    if (number.charAt(0) == '+')
      return number;

    try {
      PhoneNumberUtil util = PhoneNumberUtil.getInstance();
      PhoneNumber localNumberObject = util.parse(localNumber, null);

      String localCountryCode = util.getRegionCodeForNumber(localNumberObject);
      Log.w(TAG, "Got local CC: " + localCountryCode);

      PhoneNumber numberObject = util.parse(number, localCountryCode);
      return util.format(numberObject, PhoneNumberFormat.E164);
    } catch (NumberParseException e) {
      Log.w(TAG, e);
      return impreciseFormatNumber(number, localNumber);
    }
  }
}
