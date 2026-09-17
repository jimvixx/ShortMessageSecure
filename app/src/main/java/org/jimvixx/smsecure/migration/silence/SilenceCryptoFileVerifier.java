/*
 * Copyright (C) 2026 Jimvixx
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

package org.jimvixx.smsecure.migration.silence;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import org.whispersystems.libsignal.state.StorageProtos;

/** Reads legacy envelopes directly, without constructing live stores or resolving live recipients. */
final class SilenceCryptoFileVerifier {
  private static final int MAX_RECORD_BYTES = 1024 * 1024;

  SilenceCryptoFileInfo verify(SilenceBackupStager.Snapshot snapshot, SilenceLegacyCipher cipher) throws IOException {
    checkCancelled();
    try (SQLiteDatabase addresses = SQLiteDatabase.openDatabase(
        new File(snapshot.root(), "databases/canonical_address.db").getAbsolutePath(), null,
        SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS, ignored -> { })) {
      int sessions = directory(new File(snapshot.root(), "files/sessions-v2"), 0, cipher, addresses);
      int preKeys = directory(new File(snapshot.root(), "files/prekeys"), 1, cipher, addresses);
      int signed = directory(new File(snapshot.root(), "files/signed_prekeys"), 2, cipher, addresses);
      return SilenceCryptoFileInfo.readable(sessions, preKeys, signed);
    } catch (IOException | GeneralSecurityException | android.database.SQLException e) {
      checkCancelled();
      return SilenceCryptoFileInfo.rejected();
    }
  }

  private int directory(File directory, int kind, SilenceLegacyCipher cipher, SQLiteDatabase addresses)
      throws IOException, GeneralSecurityException {
    if (!directory.exists()) return 0;
    File[] files = directory.listFiles();
    if (files == null) throw new IOException("Invalid crypto directory");
    for (File file : files) {
      checkCancelled();
      // Legacy prekey filenames concatenate the key and subscription IDs without a delimiter.
      // Never infer a target subscription or rename a file from this lexical check.
      String pattern = kind == 0 ? "[0-9]{1,19}(\\.[0-9]{1,10})?" : "[0-9]{1,20}";
      if (!file.getName().matches(pattern) || !file.isFile()) throw new IOException("Unsupported crypto filename");
      if (kind == 0) {
        SilenceSourceBinding binding = SilenceSourceBinding.session(file.getName());
        try (Cursor cursor = addresses.rawQuery("SELECT address FROM canonical_addresses WHERE _id = ?",
            new String[]{Long.toString(binding.recipientId)})) {
          if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.getString(0).trim().isEmpty() || cursor.moveToNext())
            throw new IOException("Missing or ambiguous source recipient");
        }
      }
      record(file, kind, cipher);
    }
    return files.length;
  }

  private void record(File file, int kind, SilenceLegacyCipher cipher)
      throws IOException, GeneralSecurityException {
    byte[] sealed = null;
    byte[] plaintext = null;
    try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
      int version = input.readInt();
      if (kind == 0 ? (version != 1 && version != 2) : version != 1)
        throw new IOException("Unsupported crypto record version");
      int length = input.readInt();
      if (length < 52 || length > MAX_RECORD_BYTES || file.length() != 8L + length)
        throw new IOException("Invalid crypto record length");
      sealed = new byte[length];
      input.readFully(sealed);
      if (input.read() != -1) throw new IOException("Trailing crypto record data");
      checkCancelled();
      plaintext = cipher.decryptRecord(sealed);
      if (plaintext.length == 0) throw new IOException("Empty crypto record");
      parse(plaintext, kind, version, file.getName());
    } finally {
      if (sealed != null) Arrays.fill(sealed, (byte) 0);
      if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
    }
  }

  private void parse(byte[] plaintext, int kind, int version, String name) throws IOException {
    if (kind == 0) {
      if (version == 1) StorageProtos.SessionStructure.parseFrom(plaintext);
      else {
        StorageProtos.RecordStructure record = StorageProtos.RecordStructure.parseFrom(plaintext);
        if (!record.hasCurrentSession() && record.getPreviousSessionsCount() == 0)
          throw new IOException("Missing session structure");
      }
    } else if (kind == 1) {
      StorageProtos.PreKeyRecordStructure record = StorageProtos.PreKeyRecordStructure.parseFrom(plaintext);
      if (!record.hasId() || !record.hasPublicKey() || !record.hasPrivateKey()
          || record.getPublicKey().size() != 33 || record.getPublicKey().byteAt(0) != 5
          || record.getPrivateKey().size() != 32) throw new IOException("Invalid prekey structure");
      SilenceSourceBinding.preKeySubscription(name, record.getId());
    } else {
      StorageProtos.SignedPreKeyRecordStructure record = StorageProtos.SignedPreKeyRecordStructure.parseFrom(plaintext);
      if (!record.hasId() || !record.hasTimestamp() || !record.hasPublicKey() || !record.hasPrivateKey()
          || record.getPublicKey().size() != 33 || record.getPublicKey().byteAt(0) != 5
          || record.getPrivateKey().size() != 32 || record.getSignature().size() != 64)
        throw new IOException("Invalid signed prekey structure");
      SilenceSourceBinding.preKeySubscription(name, record.getId());
    }
    // Parsing and field shapes do not establish key-pair consistency, signatures, or session usability.
  }

  private static void checkCancelled() throws IOException {
    if (Thread.currentThread().isInterrupted()) throw new IOException("Crypto file verification cancelled");
  }
}
