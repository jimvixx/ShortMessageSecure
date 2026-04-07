/*
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

package org.jimvixx.smsecure.logging;

import org.whispersystems.libsignal.logging.SignalProtocolLogger;

public final class SignalLoggerBridge implements SignalProtocolLogger {

  private static final String PREFIX = "[libsignal] ";

  @Override
  public void log(int priority, String tag, String message) {
    final int mappedPriority = mapPriority(priority);
    final String safeTag = (tag == null || tag.trim().isEmpty()) ? "libsignal" : "libsignal/" + tag;
    final String safeMessage = PREFIX + (message == null ? "null" : message);

    Log.println(mappedPriority, safeTag, safeMessage);
  }

  private int mapPriority(int priority) {
    return switch (priority) {
      case SignalProtocolLogger.VERBOSE -> Log.VERBOSE;
      case SignalProtocolLogger.DEBUG -> Log.DEBUG;
      case SignalProtocolLogger.INFO -> Log.INFO;
      case SignalProtocolLogger.ASSERT -> Log.ASSERT;
      default -> Log.WARN;
    };
  }
}