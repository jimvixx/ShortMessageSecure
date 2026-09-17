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
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import com.google.protobuf.ByteString;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Transient authenticated source index; no core identity store, notifications, or trust updates. */
final class SilenceRemoteIdentityIndex {
  private static final int MAX_IDENTITIES = 100000;
  private final Map<Long, ByteString> keys = new HashMap<>();
  private boolean authenticated;
  private int currentMatches, currentMissing, currentDifferent, archivedMatches, archivedUnmatched;

  static SilenceRemoteIdentityIndex load(SilenceBackupStager.Snapshot snapshot, SilenceLegacyCipher cipher,
                                        SQLiteDatabase addresses) throws IOException {
    checkCancelled();
    SilenceRemoteIdentityIndex result = new SilenceRemoteIdentityIndex();
    try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
        new File(snapshot.root(), "databases/messages.db").getAbsolutePath(), null,
        SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS, ignored -> { });
        Cursor cursor = db.rawQuery("SELECT recipient, key, mac FROM identities ORDER BY _id", null)) {
      while (cursor.moveToNext()) {
        checkCancelled();
        long recipient = cursor.getLong(0);
        if (cursor.getType(0) != Cursor.FIELD_TYPE_INTEGER || recipient <= 0 || result.keys.containsKey(recipient)
            || result.keys.size() >= MAX_IDENTITIES) throw new IOException("Invalid identity recipient set");
        try (Cursor address = addresses.rawQuery("SELECT address FROM canonical_addresses WHERE _id = ?",
            new String[]{Long.toString(recipient)})) {
          if (!address.moveToFirst() || address.isNull(0) || address.getString(0).trim().isEmpty() || address.moveToNext())
            throw new IOException("Missing identity recipient");
        }
        String encoded = cursor.getString(1), mac = cursor.getString(2);
        if (encoded == null || encoded.length() > 1024 || mac == null || mac.length() > 128)
          throw new IOException("Invalid identity record size");
        byte[] key = SilenceLegacyCipher.decode(encoded);
        try {
          if (key.length != 33 || key[0] != 5) throw new IOException("Invalid remote identity key");
          cipher.verifyIdentityMac(recipient, encoded, mac);
          result.keys.put(recipient, ByteString.copyFrom(key));
        } finally { Arrays.fill(key, (byte) 0); }
      }
      result.authenticated = true;
    } catch (IOException | GeneralSecurityException | SQLException e) {
      checkCancelled();
      result.keys.clear();
    }
    return result;
  }

  void compare(long recipient, ByteString remote, boolean archived) throws IOException {
    checkCancelled();
    if (!authenticated) return;
    ByteString stored = keys.get(recipient);
    boolean matches = stored != null && stored.equals(remote);
    if (archived) {
      if (matches) archivedMatches++; else archivedUnmatched++;
    } else if (matches) currentMatches++;
    else if (stored == null) currentMissing++;
    else currentDifferent++;
  }

  SilenceRemoteIdentityInfo info() {
    return new SilenceRemoteIdentityInfo(authenticated ? SilenceRemoteIdentityInfo.Status.AUTHENTICATED
        : SilenceRemoteIdentityInfo.Status.REJECTED, keys.size(), currentMatches, currentMissing,
        currentDifferent, archivedMatches, archivedUnmatched);
  }
  private static void checkCancelled() throws IOException {
    if (Thread.currentThread().isInterrupted()) throw new IOException("Remote identity verification cancelled");
  }
}
