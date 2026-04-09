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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashLogCapture {

  private static final String CRASH_FILE_NAME = "last_crash.txt";

  private CrashLogCapture() {
  }

  public static void install(@NonNull Context context) {
    final Context appContext = context.getApplicationContext();
    final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();

    Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
      try {
        writeCrashFile(appContext, thread, throwable);
      } catch (Throwable ignore) {
      }

      if (previous != null) {
        previous.uncaughtException(thread, throwable);
      } else {
        System.exit(10);
      }
    });
  }

  @NonNull
  public static String readCrashReport(@NonNull Context context) {
    File file = getCrashFile(context);
    if (!file.exists()) return "";

    try (FileInputStream fis = new FileInputStream(file);
         ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

      byte[] buffer = new byte[4096];
      int read;

      while ((read = fis.read(buffer)) != -1) {
        bos.write(buffer, 0, read);
      }

      return bos.toString(StandardCharsets.UTF_8.name());
    } catch (Throwable t) {
      return "";
    }
  }

  public static boolean hasCrashReport(@NonNull Context context) {
    return getCrashFile(context).exists();
  }

  public static void clearCrashReport(@NonNull Context context) {
    try {
      File file = getCrashFile(context);
      if (file.exists()) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
      }
    } catch (Throwable ignore) {
    }
  }

  private static void writeCrashFile(@NonNull Context context,
                                     @Nullable Thread thread,
                                     @NonNull Throwable throwable) {
    File file = getCrashFile(context);

    StringBuilder sb = new StringBuilder(4096);
    sb.append("==== SMSecure Crash Report ====\n");
    sb.append("Generated: ")
            .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()))
            .append('\n');
    sb.append("Thread: ").append(thread == null ? "<null>" : thread.getName()).append('\n');
    sb.append("App: ").append(BuildConfig.APPLICATION_ID).append('\n');
    sb.append("Version name: ").append(BuildConfig.VERSION_NAME).append('\n');
    sb.append("Version code: ").append(BuildConfig.VERSION_CODE).append('\n');
    sb.append("Build type: ").append(BuildConfig.BUILD_TYPE).append('\n');
    sb.append('\n');
    sb.append(android.util.Log.getStackTraceString(throwable)).append('\n');

    try (FileOutputStream os = new FileOutputStream(file, false)) {
      os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    } catch (Throwable ignore) {
    }
  }

  @NonNull
  private static File getCrashFile(@NonNull Context context) {
    File dir = new File(context.getFilesDir(), "logs");
    //noinspection ResultOfMethodCallIgnored
    dir.mkdirs();
    return new File(dir, CRASH_FILE_NAME);
  }
}