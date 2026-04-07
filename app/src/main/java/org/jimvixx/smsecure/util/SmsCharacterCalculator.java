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

package org.jimvixx.smsecure.util;

import android.telephony.SmsMessage;

public class SmsCharacterCalculator extends CharacterCalculator {

  @Override
  public CharacterState calculateCharacters(String messageBody) {

    int[] length = SmsMessage.calculateLength(messageBody, false);

    int messagesSpent =
            (length != null && length.length > 0 && length[0] > 0) ? length[0] : 1;

    int charactersSpent =
            (length != null && length.length > 1) ? length[1] : 0;

    int charactersRemaining =
            (length != null && length.length > 2) ? length[2] : 0;

    int codeUnitSize =
            (length != null && length.length > 3) ? length[3] : -1;

    boolean isGsm7 = (codeUnitSize == 1);

    final int maxMessageSize;
    if (messagesSpent == 1) {
      maxMessageSize = charactersSpent + charactersRemaining;
    } else {
      maxMessageSize = isGsm7 ? 153 : 67;
    }

    return new CharacterState(messagesSpent, charactersRemaining, maxMessageSize);
  }
}
