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

package org.jimvixx.smsecure.preferences.widgets;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;

final class RingtoneImportUtil {

  private RingtoneImportUtil() {
  }

  @NonNull
  static Uri addCustomRingtoneToMediaStore(@NonNull Context context,
                                           @NonNull Uri sourceUri,
                                           int ringtoneType) throws IOException {
    ContentResolver contentResolver = context.getContentResolver();

    String displayName = getFileDisplayNameFromUri(context, sourceUri);
    if (TextUtils.isEmpty(displayName)) {
      displayName = "ringtone_" + System.currentTimeMillis();
    }

    String mimeType = contentResolver.getType(sourceUri);
    if (mimeType == null) {
      mimeType = guessMimeTypeFromName(displayName);
    }
    if (mimeType == null) {
      mimeType = "audio/*";
    }

    Uri existing = findExistingRingtone(context, sourceUri, displayName, ringtoneType);
    if (existing != null) {
      return existing;
    }

    String relativeDir = dirForType(ringtoneType);

    ContentValues values = new ContentValues();
    values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
    values.put(MediaStore.Audio.Media.MIME_TYPE, mimeType);
    values.put(MediaStore.Audio.Media.IS_RINGTONE,
            ringtoneType == RingtoneManager.TYPE_RINGTONE || ringtoneType == RingtoneManager.TYPE_ALL);
    values.put(MediaStore.Audio.Media.IS_NOTIFICATION,
            ringtoneType == RingtoneManager.TYPE_NOTIFICATION || ringtoneType == RingtoneManager.TYPE_ALL);
    values.put(MediaStore.Audio.Media.IS_ALARM,
            ringtoneType == RingtoneManager.TYPE_ALARM || ringtoneType == RingtoneManager.TYPE_ALL);

    Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      values.put(MediaStore.Audio.Media.RELATIVE_PATH, relativeDir);
      values.put(MediaStore.Audio.Media.IS_PENDING, 1);

      Uri dest = contentResolver.insert(collection, values);
      if (dest == null) {
        throw new IOException("MediaStore insert failed");
      }

      try {
        copyToUri(contentResolver, sourceUri, dest);
      } catch (IOException e) {
        contentResolver.delete(dest, null, null);
        throw e;
      }

      ContentValues done = new ContentValues();
      done.put(MediaStore.Audio.Media.IS_PENDING, 0);
      contentResolver.update(dest, done, null, null);

      return dest;
    }

    java.io.File dir = Environment.getExternalStoragePublicDirectory(relativeDir);
    //noinspection ResultOfMethodCallIgnored
    dir.mkdirs();

    String ext = getExtensionFromMimeType(mimeType);
    String safeName = buildUniqueFileName(dir, displayName, ext);

    java.io.File outFile = new java.io.File(dir, safeName);
    values.put(MediaStore.Audio.Media.DATA, outFile.getAbsolutePath());

    Uri dest = contentResolver.insert(collection, values);
    if (dest == null) {
      throw new IOException("MediaStore insert failed");
    }

    try {
      copyToUri(contentResolver, sourceUri, dest);
    } catch (IOException e) {
      contentResolver.delete(dest, null, null);
      //noinspection ResultOfMethodCallIgnored
      outFile.delete();
      throw e;
    }

    return dest;
  }

  @Nullable
  private static Uri findExistingRingtone(@NonNull Context context,
                                          @NonNull Uri sourceUri,
                                          @NonNull String displayName,
                                          int ringtoneType) {
    ContentResolver contentResolver = context.getContentResolver();

    Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

    String selection = MediaStore.Audio.Media.DISPLAY_NAME + " = ?";
    String[] selectionArgs = new String[]{displayName};

    String[] projection = new String[]{
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.IS_RINGTONE,
            MediaStore.Audio.Media.IS_NOTIFICATION,
            MediaStore.Audio.Media.IS_ALARM
    };

    Long sourceSize = querySourceSize(context, sourceUri);
    String sourceHash = null;

    try (Cursor cursor = contentResolver.query(collection, projection, selection, selectionArgs, null)) {
      if (cursor == null) {
        return null;
      }

      int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
      int sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
      int ringtoneIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RINGTONE);
      int notificationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_NOTIFICATION);
      int alarmIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_ALARM);

      while (cursor.moveToNext()) {
        if (!matchesType(cursor, ringtoneType, ringtoneIndex, notificationIndex, alarmIndex)) {
          continue;
        }

        Uri existingUri = Uri.withAppendedPath(collection, String.valueOf(cursor.getLong(idIndex)));
        Long existingSize = cursor.isNull(sizeIndex) ? null : cursor.getLong(sizeIndex);

        if (sourceSize != null && existingSize != null && sourceSize.longValue() != existingSize.longValue()) {
          continue;
        }

        if (sourceHash == null) {
          sourceHash = sha256OfUri(contentResolver, sourceUri);
          if (sourceHash == null) {
            return existingUri;
          }
        }

        String existingHash = sha256OfUri(contentResolver, existingUri);
        if (existingHash != null && existingHash.equals(sourceHash)) {
          return existingUri;
        }
      }
    } catch (Exception ignore) {
    }

    return null;
  }

  private static boolean matchesType(@NonNull Cursor cursor,
                                     int ringtoneType,
                                     int ringtoneIndex,
                                     int notificationIndex,
                                     int alarmIndex) {
    boolean isRingtone = cursor.getInt(ringtoneIndex) != 0;
    boolean isNotification = cursor.getInt(notificationIndex) != 0;
    boolean isAlarm = cursor.getInt(alarmIndex) != 0;

    if (ringtoneType == RingtoneManager.TYPE_NOTIFICATION) {
      return isNotification;
    }

    if (ringtoneType == RingtoneManager.TYPE_ALARM) {
      return isAlarm;
    }

    if (ringtoneType == RingtoneManager.TYPE_RINGTONE) {
      return isRingtone;
    }

    if (ringtoneType == RingtoneManager.TYPE_ALL) {
      return isRingtone || isNotification || isAlarm;
    }

    return false;
  }

  @Nullable
  private static Long querySourceSize(@NonNull Context context, @NonNull Uri uri) {
    if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
      try (Cursor cursor = context.getContentResolver().query(
              uri,
              new String[]{OpenableColumns.SIZE},
              null,
              null,
              null
      )) {
        if (cursor != null && cursor.moveToFirst()) {
          int index = cursor.getColumnIndex(OpenableColumns.SIZE);
          if (index >= 0 && !cursor.isNull(index)) {
            return cursor.getLong(index);
          }
        }
      } catch (Exception ignore) {
      }
    }

    return null;
  }

  @Nullable
  private static String sha256OfUri(@NonNull ContentResolver contentResolver, @NonNull Uri uri) {
    try (InputStream inputStream = contentResolver.openInputStream(uri)) {
      if (inputStream == null) {
        return null;
      }

      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[16 * 1024];

      for (int read; (read = inputStream.read(buffer)) != -1; ) {
        digest.update(buffer, 0, read);
      }

      byte[] hash = digest.digest();
      StringBuilder builder = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        builder.append(String.format("%02x", b));
      }
      return builder.toString();
    } catch (Exception ignore) {
      return null;
    }
  }

  private static void copyToUri(@NonNull ContentResolver contentResolver,
                                @NonNull Uri inUri,
                                @NonNull Uri outUri) throws IOException {
    try (InputStream in = contentResolver.openInputStream(inUri);
         OutputStream out = contentResolver.openOutputStream(outUri)) {
      if (in == null || out == null) {
        throw new IOException("Unable to open streams");
      }

      byte[] buffer = new byte[16 * 1024];
      for (int read; (read = in.read(buffer)) != -1; ) {
        out.write(buffer, 0, read);
      }
      out.flush();
    }
  }

  @NonNull
  private static String dirForType(int type) {
    if (type == RingtoneManager.TYPE_NOTIFICATION) {
      return Environment.DIRECTORY_NOTIFICATIONS;
    }
    if (type == RingtoneManager.TYPE_ALARM) {
      return Environment.DIRECTORY_ALARMS;
    }
    return Environment.DIRECTORY_RINGTONES;
  }

  @Nullable
  private static String getFileDisplayNameFromUri(@NonNull Context context, @NonNull Uri uri) {
    if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
      try (Cursor cursor = context.getContentResolver().query(
              uri,
              new String[]{OpenableColumns.DISPLAY_NAME},
              null,
              null,
              null
      )) {
        if (cursor != null && cursor.moveToFirst()) {
          int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
          if (index >= 0) {
            String name = cursor.getString(index);
            if (!TextUtils.isEmpty(name)) {
              return name;
            }
          }
        }
      } catch (Exception ignore) {
      }
    }

    String lastSegment = uri.getLastPathSegment();
    return !TextUtils.isEmpty(lastSegment) ? lastSegment : null;
  }

  @Nullable
  private static String guessMimeTypeFromName(@NonNull String name) {
    String ext = getExtension(name);
    if (TextUtils.isEmpty(ext)) {
      return null;
    }
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
  }

  @Nullable
  private static String getExtensionFromMimeType(@NonNull String mimeType) {
    return MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
  }

  @NonNull
  private static String buildUniqueFileName(@NonNull java.io.File dir,
                                            @NonNull String displayName,
                                            @Nullable String forcedExtension) {
    String baseName = stripExtension(displayName);
    String sourceExt = getExtension(displayName);
    String ext = !TextUtils.isEmpty(forcedExtension) ? forcedExtension : sourceExt;

    String candidate = appendExtension(baseName, ext);
    java.io.File outFile = new java.io.File(dir, candidate);

    int n = 1;
    while (outFile.exists() && n < 1000) {
      candidate = appendExtension(baseName + " (" + n++ + ")", ext);
      outFile = new java.io.File(dir, candidate);
    }

    return candidate;
  }

  @Nullable
  private static String getExtension(@NonNull String name) {
    int dot = name.lastIndexOf('.');
    if (dot < 0 || dot == name.length() - 1) {
      return null;
    }
    return name.substring(dot + 1);
  }

  @NonNull
  private static String appendExtension(@NonNull String baseName, @Nullable String ext) {
    if (TextUtils.isEmpty(ext)) {
      return baseName;
    }
    return baseName + "." + ext;
  }

  @NonNull
  private static String stripExtension(@NonNull String name) {
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }
}