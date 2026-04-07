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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Internal diagnostic log storage.
 * <p>
 * Safe to call very early during process startup.
 * If application context is not ready yet, writes are simply skipped.
 */
public final class DiagnosticLogStore {

  private static final long MAX_BYTES = 512L * 1024L;
  private static final long TRIM_TO_BYTES = 384L * 1024L;
  private static final Object LOCK = new Object();

  private DiagnosticLogStore() {
  }

  public static void append(@NonNull String level,
                            @NonNull String tag,
                            @NonNull String message,
                            @Nullable Throwable tr) {
    try {
      File file = getLogFileOrNull();
      if (file == null) return;

      String line = formatLine(level, tag, message, tr);
      byte[] bytes = line.getBytes(StandardCharsets.UTF_8);

      synchronized (LOCK) {
        trimIfNeeded(file, bytes.length);

        try (FileOutputStream os = new FileOutputStream(file, true)) {
          os.write(bytes);
        }
      }
    } catch (Throwable ignore) {
    }
  }

  @NonNull
  public static String readAll() {
    try {
      File file = getLogFileOrNull();
      if (file == null || !file.exists()) {
        return "";
      }

      try (FileInputStream fis = new FileInputStream(file);
           ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

        byte[] buffer = new byte[4096];
        int read;

        while ((read = fis.read(buffer)) != -1) {
          bos.write(buffer, 0, read);
        }

        return bos.toString(StandardCharsets.UTF_8.name());
      }
    } catch (Throwable t) {
      return "";
    }
  }

  public static void clear() {
    try {
      File file = getLogFileOrNull();
      if (file == null) return;

      synchronized (LOCK) {
        if (file.exists()) {
          //noinspection ResultOfMethodCallIgnored
          file.delete();
        }
      }
    } catch (Throwable ignore) {
    }
  }

  @Nullable
  public static File getFile() {
    return getLogFileOrNull();
  }

  @Nullable
  private static File getLogFileOrNull() {
    try {
      Context context = Log.getApplicationContextOrNull();
      if (context == null) return null;

      File dir = new File(context.getFilesDir(), "logs");
      //noinspection ResultOfMethodCallIgnored
      dir.mkdirs();

      return new File(dir, "diagnostic.log");
    } catch (Throwable t) {
      return null;
    }
  }

  @NonNull
  private static String formatLine(@NonNull String level,
                                   @NonNull String tag,
                                   @NonNull String message,
                                   @Nullable Throwable tr) {
    String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            .format(new Date());

    StringBuilder sb = new StringBuilder(256);
    sb.append(timestamp)
            .append(' ')
            .append(level)
            .append('/')
            .append(tag)
            .append(": ")
            .append(message)
            .append('\n');

    if (tr != null) {
      sb.append(android.util.Log.getStackTraceString(tr)).append('\n');
    }

    return sb.toString();
  }

  private static void trimIfNeeded(@NonNull File file, int incomingBytes) {
    try {
      if (!file.exists()) {
        return;
      }

      long currentSize = file.length();
      if (currentSize + incomingBytes <= MAX_BYTES) {
        return;
      }

      long bytesToKeep = Math.min(TRIM_TO_BYTES, currentSize);
      if (bytesToKeep <= 0) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        return;
      }

      byte[] tail = readTail(file, bytesToKeep);
      if (tail.length == 0) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        return;
      }

      int start = findFirstLineStart(tail);

      try (FileOutputStream os = new FileOutputStream(file, false)) {
        String marker = "---- log trimmed ----\n";
        os.write(marker.getBytes(StandardCharsets.UTF_8));
        os.write(tail, start, tail.length - start);
      }
    } catch (Throwable t) {
      //noinspection ResultOfMethodCallIgnored
      file.delete();
    }
  }

  @NonNull
  private static byte[] readTail(@NonNull File file, long bytesToKeep) {
    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      long fileLength = raf.length();
      long start = Math.max(0L, fileLength - bytesToKeep);
      int length = (int) (fileLength - start);

      byte[] result = new byte[length];
      raf.seek(start);
      raf.readFully(result);
      return result;
    } catch (Throwable t) {
      return new byte[0];
    }
  }

  private static int findFirstLineStart(@NonNull byte[] data) {
    if (data.length == 0) return 0;

    for (int i = 0; i < data.length; i++) {
      if (data[i] == (byte) '\n') {
        int next = i + 1;
        if (next < data.length) {
          return next;
        }
        break;
      }
    }

    return 0;
  }
}