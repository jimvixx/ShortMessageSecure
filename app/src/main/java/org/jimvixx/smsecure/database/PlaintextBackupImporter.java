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

package org.jimvixx.smsecure.database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.crypto.MasterCipher;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

public class PlaintextBackupImporter {

  private static final String TAG = PlaintextBackupImporter.class.getSimpleName();

  /// Legacy API kept for compatibility. On modern Android it is not reliable due to scoped storage.
  @Deprecated
  public static void importPlaintextFromSd(@NonNull Context context, @NonNull MasterSecret masterSecret)
          throws NoExternalStorageException {
    throw new NoExternalStorageException("Legacy external storage import is not supported. Use importPlaintextFromUri().");
  }

  /// SAF import: read plaintext XML backup from Uri (OpenDocument).
  public static void importPlaintextFromUri(@NonNull Context context,
                                            @NonNull MasterSecret masterSecret,
                                            @NonNull Uri inputUri) throws IOException {

    Log.i(TAG, "Importing plaintext from Uri: " + inputUri);

    try (InputStream is = context.getContentResolver().openInputStream(inputUri)) {
      if (is == null) throw new IOException("openInputStream() returned null for: " + inputUri);
      importPlaintext(context, masterSecret, is);
    }
  }

  private static void importPlaintext(@NonNull Context context,
                                      @NonNull MasterSecret masterSecret,
                                      @NonNull InputStream inputStream) throws IOException {
    Log.w(TAG, "importPlaintext(InputStream)");

    SmsDatabase db = DatabaseFactory.getSmsDatabase(context);
    SQLiteDatabase transaction = db.beginTransaction();

    try {
      ThreadDatabase threads = DatabaseFactory.getThreadDatabase(context);
      XmlBackup backup = new XmlBackup(inputStream);
      MasterCipher masterCipher = new MasterCipher(masterSecret);

      Set<Long> modifiedThreads = new HashSet<>();
      XmlBackup.XmlBackupItem item;

      while ((item = backup.getNext()) != null) {

        // Skip invalid address
        String address = item.getAddress();
        if (address == null || "null".equals(address)) continue;

        // Skip unsupported types
        if (!isAppropriateTypeForImport(item.getType())) continue;

        Recipients recipients = RecipientFactory.getRecipientsFromString(context, address, false);
        long threadId = threads.getThreadIdFor(recipients);

        SQLiteStatement statement = db.createInsertStatement(transaction);

        addStringToStatement(statement, 1, address);
        addNullToStatement(statement, 2);
        addLongToStatement(statement, 3, item.getDate());
        addLongToStatement(statement, 4, item.getDate());
        addLongToStatement(statement, 5, item.getProtocol());
        addLongToStatement(statement, 6, item.getRead());
        addLongToStatement(statement, 7, item.getStatus());
        addTranslatedTypeToStatement(statement, 8, item.getType());
        addNullToStatement(statement, 9);
        addStringToStatement(statement, 10, item.getSubject());
        addEncryptedStringToStatement(masterCipher, statement, 11, item.getBody());
        addStringToStatement(statement, 12, item.getServiceCenter());
        addLongToStatement(statement, 13, threadId);

        modifiedThreads.add(threadId);
        statement.execute();
      }

      for (long threadId : modifiedThreads) {
        threads.update(threadId, true);
      }

      Log.w(TAG, "Import finished. Updated threads: " + modifiedThreads.size());

    } catch (XmlPullParserException e) {
      Log.w(TAG, e);
      throw new IOException("XML parsing error!", e);
    } finally {
      db.endTransaction(transaction);
    }
  }

  private static void addEncryptedStringToStatement(@NonNull MasterCipher masterCipher,
                                                    @NonNull SQLiteStatement statement,
                                                    int index,
                                                    @Nullable String value) {
    if (value == null || "null".equals(value)) {
      statement.bindNull(index);
    } else {
      statement.bindString(index, masterCipher.encryptBody(value));
    }
  }

  private static void addTranslatedTypeToStatement(@NonNull SQLiteStatement statement,
                                                   int index,
                                                   int type) {
    long value =
            SmsDatabase.Types.translateFromSystemBaseType(type)
                    | SmsDatabase.Types.ENCRYPTION_SYMMETRIC_BIT;

    statement.bindLong(index, value);
  }

  private static void addStringToStatement(@NonNull SQLiteStatement statement, int index, @Nullable String value) {
    if (value == null || "null".equals(value)) statement.bindNull(index);
    else statement.bindString(index, value);
  }

  private static void addNullToStatement(@NonNull SQLiteStatement statement, int index) {
    statement.bindNull(index);
  }

  private static void addLongToStatement(@NonNull SQLiteStatement statement, int index, long value) {
    statement.bindLong(index, value);
  }

  private static boolean isAppropriateTypeForImport(long theirType) {
    long ourType = SmsDatabase.Types.translateFromSystemBaseType(theirType);

    return ourType == MessageColumns.Types.BASE_INBOX_TYPE ||
            ourType == MessageColumns.Types.BASE_SENT_TYPE ||
            ourType == MessageColumns.Types.BASE_SENT_FAILED_TYPE;
  }
}
