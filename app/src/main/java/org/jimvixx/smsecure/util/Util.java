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

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.mms.pdu_alt.EncodedStringValue;

import org.jimvixx.smsecure.permissions.Permissions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Util {

  private static volatile Handler handler;

  private static Handler getMainHandler() {
    Handler local = handler;
    if (local == null) {
      synchronized (Util.class) {
        local = handler;
        if (local == null) {
          local = new Handler(Looper.getMainLooper());
          handler = local;
        }
      }
    }
    return local;
  }

  public static String join(String[] list, String delimiter) {
    return join(Arrays.asList(list), delimiter);
  }

  public static String join(Collection<String> list, String delimiter) {
    StringBuilder result = new StringBuilder();
    int i = 0;

    for (String item : list) {
      result.append(item);

      if (++i < list.size()) {
        result.append(delimiter);
      }
    }

    return result.toString();
  }

  public static String join(long[] list, String delimiter) {
    StringBuilder sb = new StringBuilder();

    for (int j = 0; j < list.length; j++) {
      if (j != 0) sb.append(delimiter);
      sb.append(list[j]);
    }

    return sb.toString();
  }

  public static ExecutorService newSingleThreadedLifoExecutor() {
    ThreadFactory tf = r -> {
      Thread t = new Thread(r);
      t.setName("SMSecure-LIFO");
      t.setPriority(Thread.MIN_PRIORITY);
      return t;
    };

    return new ThreadPoolExecutor(
            1, 1,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingLifoQueue<>(),
            tf
    );
  }

  public static boolean isEmpty(EncodedStringValue[] value) {
    return value == null || value.length == 0;
  }

  public static boolean isEmpty(EditText value) {
    return value == null
            || value.getText() == null
            || TextUtils.isEmpty(value.getText().toString());
  }

  public static CharSequence getBoldedString(String value) {
    SpannableString spanned = new SpannableString(value);
    spanned.setSpan(new StyleSpan(Typeface.BOLD), 0,
            spanned.length(),
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

    return spanned;
  }

  public static @NonNull String toIsoString(byte[] bytes) {
    return new String(bytes, StandardCharsets.ISO_8859_1);
  }

  public static byte[] toIsoBytes(String isoString) {
    return isoString.getBytes(StandardCharsets.ISO_8859_1);
  }

  public static byte[] toUtf8Bytes(String utf8String) {
    return utf8String.getBytes(StandardCharsets.UTF_8);
  }

  public static void wait(Object lock, long timeout) {
    try {
      lock.wait(timeout);
    } catch (InterruptedException ie) {
      throw new AssertionError(ie);
    }
  }

  public static String canonicalizeNumber(Context context, String number)
          throws InvalidNumberException {
    String localNumber = SMSecurePreferences.getLocalNumber(context);
    return PhoneNumberFormatter.formatNumber(number, localNumber);
  }

  public static byte[] readFully(InputStream in) throws IOException {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int read;

    while ((read = in.read(buffer)) != -1) {
      bout.write(buffer, 0, read);
    }

    in.close();

    return bout.toByteArray();
  }

  public static void readFully(InputStream in, byte[] buffer) throws IOException {
    int offset = 0;

    for (; ; ) {
      int read = in.read(buffer, offset, buffer.length - offset);

      if (read + offset < buffer.length) offset += read;
      else return;
    }
  }

  public static long copy(InputStream in, OutputStream out) throws IOException {
    byte[] buffer = new byte[4096];
    int read;
    long total = 0;

    while ((read = in.read(buffer)) != -1) {
      out.write(buffer, 0, read);
      total += read;
    }

    in.close();
    out.close();

    return total;
  }

  // Pure function: no permissions, no Android deps.
  public static <T> List<List<T>> partition(List<T> list, int partitionSize) {
    List<List<T>> results = new LinkedList<>();

    for (int index = 0; index < list.size(); index += partitionSize) {
      int subListSize = Math.min(partitionSize, list.size() - index);
      results.add(list.subList(index, index + subListSize));
    }

    return results;
  }

  public static List<String> split(String source, String delimiter) {
    List<String> results = new LinkedList<>();

    if (TextUtils.isEmpty(source)) {
      return results;
    }

    String[] elements = source.split(delimiter);
    Collections.addAll(results, elements);

    return results;
  }

  public static byte[][] split(byte[] input, int firstLength, int secondLength) {
    byte[][] parts = new byte[2][];

    parts[0] = new byte[firstLength];
    System.arraycopy(input, 0, parts[0], 0, firstLength);

    parts[1] = new byte[secondLength];
    System.arraycopy(input, firstLength, parts[1], 0, secondLength);

    return parts;
  }

  public static byte[] combine(byte[]... elements) {
    try {
      ByteArrayOutputStream b = new ByteArrayOutputStream();

      for (byte[] element : elements) {
        b.write(element);
      }

      return b.toByteArray();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  public static byte[] trim(byte[] input, int length) {
    byte[] result = new byte[length];
    System.arraycopy(input, 0, result, 0, result.length);

    return result;
  }

  public static int toIntExact(long value) {
    if ((int) value != value) {
      throw new ArithmeticException("integer overflow");
    }
    return (int) value;
  }

  public static boolean isDefaultSmsProvider(@NonNull Context context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // 29+
      RoleManager rm = context.getSystemService(RoleManager.class);
      return rm != null
              && rm.isRoleAvailable(RoleManager.ROLE_SMS)
              && rm.isRoleHeld(RoleManager.ROLE_SMS);
    }

    String def = Telephony.Sms.getDefaultSmsPackage(context); // 19–28
    return def != null && def.equals(context.getPackageName());
  }

  public static int getCurrentApkReleaseVersion(Context context) {
    try {
      return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
    } catch (PackageManager.NameNotFoundException e) {
      throw new AssertionError(e);
    }
  }

  public static byte[] getSecretBytes(int size) {
    byte[] secret = new byte[size];
    getSecureRandom().nextBytes(secret);
    return secret;
  }

  public static SecureRandom getSecureRandom() {
    return new SecureRandom();
  }

  public static boolean isMainThread() {
    return Looper.myLooper() == Looper.getMainLooper();
  }

  public static void assertMainThread() {
    if (!isMainThread()) {
      throw new AssertionError("Main-thread assertion failed.");
    }
  }

  public static void runOnMain(Runnable runnable) {
    if (isMainThread()) runnable.run();
    else getMainHandler().post(runnable);
  }

  public static boolean equals(@Nullable Object a, @Nullable Object b) {
    return Objects.equals(a, b);
  }

  public static int hashCode(@Nullable Object... objects) {
    return Arrays.hashCode(objects);
  }

  public static int clamp(int value, int min, int max) {
    return Math.min(Math.max(value, min), max);
  }

  public static boolean missingMandatoryPermissions(Context context) {
    return !Permissions.hasAll(context,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECEIVE_MMS);
  }

  public static boolean missingContactsPermissions(Context context) {
    return !Permissions.hasAny(context,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS);
  }

  @NonNull
  public static String formatNumber(@NonNull Context context, @NonNull String number) {
    try {
      TelephonyManager tm =
              (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

      String countryIso = null;
      if (tm != null) {
        countryIso = tm.getNetworkCountryIso();
        if (TextUtils.isEmpty(countryIso)) {
          countryIso = tm.getSimCountryIso();
        }
      }

      if (!TextUtils.isEmpty(countryIso)) {
        return PhoneNumberUtils.formatNumber(number, countryIso.toUpperCase(Locale.ROOT));
      }
    } catch (Exception e) {
      // deliberately ignore — fallback below
    }

    // fallback: raw number
    return number;
  }
}
