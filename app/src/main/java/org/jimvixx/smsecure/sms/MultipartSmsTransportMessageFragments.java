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

package org.jimvixx.smsecure.sms;

import androidx.annotation.NonNull;

import java.util.Locale;

public class MultipartSmsTransportMessageFragments {

  private static final long VALID_TIME = 60 * 60 * 1000; // 1 Hour

  private final byte[][] fragments;
  private final long initializedTime;

  public MultipartSmsTransportMessageFragments(int count) {
    this.fragments = new byte[count][];
    this.initializedTime = System.currentTimeMillis();
  }

  public void add(MultipartSmsTransportMessage fragment) {
    this.fragments[fragment.getMultipartIndex()] = fragment.getStrippedMessage();
  }

  public int getSize() {
    return this.fragments.length;
  }

  public boolean isExpired() {
    return (System.currentTimeMillis() - initializedTime) >= VALID_TIME;
  }

  public boolean isComplete() {
    for (byte[] fragment : fragments) if (fragment == null) return false;

    return true;
  }

  public byte[] getJoined() {
    int totalMessageLength = 0;

    for (byte[] fragment1 : fragments) {
      totalMessageLength += fragment1.length;
    }

    byte[] totalMessage = new byte[totalMessageLength];
    int totalMessageOffset = 0;

    for (byte[] fragment : fragments) {
      System.arraycopy(fragment, 0, totalMessage, totalMessageOffset, fragment.length);
      totalMessageOffset += fragment.length;
    }

    return totalMessage;
  }

  @NonNull
  @Override
  public String toString() {
    return String.format(
            Locale.ROOT,
            "[Size: %d, Initialized: %d, Expired: %s, Complete: %s]",
            fragments.length,
            initializedTime,
            isExpired(),
            isComplete()
    );
  }
}
