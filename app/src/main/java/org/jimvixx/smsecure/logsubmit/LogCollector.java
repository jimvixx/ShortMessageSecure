/*
 * Copyright (C) 2013 Open Whisper Systems
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

package org.jimvixx.smsecure.logsubmit;

import android.content.Context;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.logging.CrashLogCapture;
import org.jimvixx.smsecure.logging.DiagnosticInfoCollector;
import org.jimvixx.smsecure.logging.DiagnosticLogStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Collects a full diagnostic report:
 * - app/device/environment info
 * - last crash report
 * - internal diagnostic log
 * - logcat tail
 */
public final class LogCollector {

  private static final int MAX_LOGCAT_CHARS = 700_000;

  private LogCollector() {}

  @NonNull
  public static String collect(@NonNull Context context) {
    StringBuilder out = new StringBuilder(256_000);

    out.append(DiagnosticInfoCollector.collect(context));

    out.append("== Last crash report ==\n");
    String crash = CrashLogCapture.readCrashReport(context);
    out.append(crash.isEmpty() ? "<empty>\n" : crash).append('\n');

    out.append("== Internal diagnostic log ==\n");
    String internal = DiagnosticLogStore.readAll();
    out.append(internal.isEmpty() ? "<empty>\n" : internal).append('\n');

    out.append("== Logcat ==\n");
    out.append(collectLogcat());

    return out.toString();
  }

  @NonNull
  private static String collectLogcat() {
    String pidFiltered = collectPidFiltered();
    if (!pidFiltered.isEmpty()) {
      return "[source=pid-filtered]\n" + pidFiltered;
    }

    String fallback = collectFallbackNoPid();
    if (!fallback.isEmpty()) {
      return "[source=fallback-no-pid]\n" + fallback;
    }

    return "<logcat unavailable>\n";
  }

  @NonNull
  private static String collectPidFiltered() {
    StringBuilder out = new StringBuilder(64_000);
    java.lang.Process process = null;

    try {
      String pidArg = "--pid=" + android.os.Process.myPid();

      ProcessBuilder pb = new ProcessBuilder(
              "logcat",
              "-d",
              "-v", "time",
              pidArg,
              "*:V"
      );

      pb.redirectErrorStream(true);
      process = pb.start();

      try (BufferedReader br = new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

        String line;
        while ((line = br.readLine()) != null) {
          out.append(line).append('\n');
          trimToMax(out, MAX_LOGCAT_CHARS);
        }
      }

      int exit = process.waitFor();
      if (exit != 0 || out.length() == 0) {
        return "";
      }

      return out.toString();
    } catch (Throwable t) {
      return "";
    } finally {
      if (process != null) {
        try {
          process.destroy();
        } catch (Throwable ignore) {
        }
      }
    }
  }

  @NonNull
  private static String collectFallbackNoPid() {
    StringBuilder out = new StringBuilder(64_000);
    java.lang.Process process = null;

    try {
      ProcessBuilder pb = new ProcessBuilder(
              "logcat",
              "-d",
              "-v", "time",
              "*:I"
      );

      pb.redirectErrorStream(true);
      process = pb.start();

      try (BufferedReader br = new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

        String line;
        while ((line = br.readLine()) != null) {
          out.append(line).append('\n');
          trimToMax(out, MAX_LOGCAT_CHARS);
        }
      }

      int exit = process.waitFor();
      if (exit != 0 && out.length() == 0) {
        return "";
      }

      return out.toString();
    } catch (Throwable t) {
      return "";
    } finally {
      if (process != null) {
        try {
          process.destroy();
        } catch (Throwable ignore) {
        }
      }
    }
  }

  private static void trimToMax(@NonNull StringBuilder sb, int maxChars) {
    int extra = sb.length() - maxChars;
    if (extra > 0) {
      sb.delete(0, extra);
    }
  }
}