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

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.BuildConfig;
import org.jimvixx.smsecure.util.SMSecurePreferences;

/**
 * Application logging facade.
 * <p>
 * Safe to use even very early in process startup, before Application.onCreate().
 * Existing code can usually switch imports from android.util.Log to this class.
 */
public final class Log {

  public static final int VERBOSE = android.util.Log.VERBOSE;
  public static final int DEBUG = android.util.Log.DEBUG;
  public static final int INFO = android.util.Log.INFO;
  public static final int WARN = android.util.Log.WARN;
  public static final int ERROR = android.util.Log.ERROR;
  public static final int ASSERT = android.util.Log.ASSERT;
  @Nullable
  private static volatile Context applicationContext;

  private Log() {
  }

  public static void initialize(@NonNull Context context) {
    applicationContext = context.getApplicationContext();
  }

  @Nullable
  static Context getApplicationContextOrNull() {
    return applicationContext;
  }

  public static int v(@NonNull String tag, @NonNull String msg) {
    return println(VERBOSE, tag, msg, null);
  }

  public static int v(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
    return println(VERBOSE, tag, msg, tr);
  }

  public static int d(@NonNull String tag, @NonNull String msg) {
    return println(DEBUG, tag, msg, null);
  }

  public static int d(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
    return println(DEBUG, tag, msg, tr);
  }

  public static int i(@NonNull String tag, @NonNull String msg) {
    return println(INFO, tag, msg, null);
  }

  public static int i(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
    return println(INFO, tag, msg, tr);
  }

  public static int w(@NonNull String tag, @NonNull String msg) {
    return println(WARN, tag, msg, null);
  }

  public static int w(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
    return println(WARN, tag, msg, tr);
  }

  public static int w(@NonNull String tag, @NonNull Throwable tr) {
    return println(WARN, tag, "", tr);
  }

  public static int e(@NonNull String tag, @NonNull String msg) {
    return println(ERROR, tag, msg, null);
  }

  public static int e(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
    return println(ERROR, tag, msg, tr);
  }

  public static int println(int priority, @NonNull String tag, @NonNull String msg) {
    return println(priority, tag, msg, null);
  }

  public static String getStackTraceString(@Nullable Throwable tr) {
    return android.util.Log.getStackTraceString(tr);
  }

  public static boolean isLoggable(@NonNull String tag, int priority) {
    return shouldLog(priority);
  }

  @NonNull
  public static Level getLevel() {
    final Context context = applicationContext;

    if (context == null) {
      return BuildConfig.DEBUG ? Level.VERBOSE : Level.ERRORS_ONLY;
    }

    try {
      return SMSecurePreferences.getLogLevel(context);
    } catch (Throwable t) {
      return BuildConfig.DEBUG ? Level.VERBOSE : Level.ERRORS_ONLY;
    }
  }

  public static boolean isEnabled() {
    return getLevel() != Level.OFF;
  }

  private static int println(int priority,
                             @NonNull String tag,
                             @NonNull String msg,
                             @Nullable Throwable tr) {

    if (!shouldLog(priority)) {
      return 0;
    }

    final String safeMessage = LogSanitizer.sanitizeMessage(msg);

    try {
      DiagnosticLogStore.append(priorityToLetter(priority), tag, safeMessage, tr);
    } catch (Throwable ignore) {
    }


    if (shouldWriteToSystemLog(priority)) {
      if (tr != null) {
        return android.util.Log.println(priority, tag, safeMessage + '\n' + getStackTraceString(tr));
      } else {
        return android.util.Log.println(priority, tag, safeMessage);
      }
    }

    return 1;
  }

  private static boolean shouldLog(int priority) {
    final Level level = getLevel();

    return switch (level) {
      case OFF -> false;
      case ERRORS_ONLY -> priority >= WARN;
      case BASIC -> priority >= INFO;
      case VERBOSE -> true;
    };
  }

  private static boolean shouldWriteToSystemLog(int priority) {
    if (BuildConfig.DEBUG) {
      return true;
    }

    final Context context = applicationContext;
    if (context == null) {
      return priority >= ERROR;
    }

    try {
      return SMSecurePreferences.isSystemLogEnabled(context) && shouldLog(priority);
    } catch (Throwable t) {
      return priority >= ERROR;
    }
  }

  @NonNull
  private static String priorityToLetter(int priority) {
    return switch (priority) {
      case VERBOSE -> "V";
      case DEBUG -> "D";
      case INFO -> "I";
      case WARN -> "W";
      case ERROR -> "E";
      case ASSERT -> "A";
      default -> String.valueOf(priority);
    };
  }

  public enum Level {
    OFF,
    ERRORS_ONLY,
    BASIC,
    VERBOSE
  }
}