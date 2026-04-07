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
import android.net.Uri;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.model.SmsMessageRecord;

import java.io.IOException;
import java.io.OutputStream;

public class PlaintextBackupExporter {

  /// Legacy API kept for compatibility. On modern Android it is not reliable due to scoped storage.
  @Deprecated
  public static void exportPlaintextToSd(Context context, MasterSecret masterSecret)
          throws NoExternalStorageException {
    throw new NoExternalStorageException("Legacy external storage export is not supported. Use exportPlaintextToUri().");
  }

  /// SAF export: write XML backup to a caller-provided Uri (CreateDocument).
  public static void exportPlaintextToUri(@NonNull Context context,
                                          @NonNull MasterSecret masterSecret,
                                          @NonNull Uri outputUri) throws IOException {

    int count = DatabaseFactory.getSmsDatabase(context).getMessageCount();

    try (OutputStream os = context.getContentResolver().openOutputStream(outputUri, "wt")) {
      if (os == null) throw new IOException("openOutputStream() returned null for: " + outputUri);

      XmlBackup.Writer writer = new XmlBackup.Writer(os, count);

      SmsMessageRecord record;
      EncryptingSmsDatabase.Reader reader = null;
      int skip = 0;
      final int ROW_LIMIT = 500;

      try {
        do {
          if (reader != null) reader.close();

          reader = DatabaseFactory.getEncryptingSmsDatabase(context).getMessages(masterSecret, skip, ROW_LIMIT);

          while ((record = reader.getNext()) != null) {
            XmlBackup.XmlBackupItem item =
                    new XmlBackup.XmlBackupItem(
                            0,
                            record.getIndividualRecipient().getNumber(),
                            record.getDateReceived(),
                            MessageColumns.Types.translateToSystemBaseType(record.getType()),
                            null,
                            record.getDisplayBody().toString(),
                            null,
                            1,
                            record.getDeliveryStatus()
                    );

            writer.writeItem(item);
          }

          skip += ROW_LIMIT;
        } while (reader.getCount() > 0);
      } finally {
        if (reader != null) reader.close();
        writer.close();
      }
    }
  }
}
