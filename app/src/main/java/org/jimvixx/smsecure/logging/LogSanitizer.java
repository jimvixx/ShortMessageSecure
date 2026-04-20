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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/**
 * Best-effort sanitizer for diagnostic logging.
 *
 * Keep it conservative. Do not try to be too smart at first.
 */
public final class LogSanitizer {

  private static final Pattern PHONE_PATTERN =
          Pattern.compile("\\+?\\d[\\d\\-() ]{6,}\\d");

  private static final Pattern EMAIL_PATTERN =
          Pattern.compile("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", Pattern.CASE_INSENSITIVE);

  private LogSanitizer() {
  }

  @NonNull
  public static String sanitizeMessage(@Nullable String message) {
    if (message == null) return "null";

    String out = message;
    out = EMAIL_PATTERN.matcher(out).replaceAll("<redacted-email>");
    out = PHONE_PATTERN.matcher(out).replaceAll("<redacted-phone>");
    return out;
  }

  @NonNull
  public static String maskPhone(@Nullable String value) {
    if (value == null || value.isEmpty()) return "<empty>";
    if (value.length() <= 4) return "***";
    return "***" + value.substring(value.length() - 4);
  }
}