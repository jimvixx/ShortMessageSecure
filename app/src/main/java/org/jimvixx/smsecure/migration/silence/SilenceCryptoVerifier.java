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
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Map;

/** Verifies the original snapshot, without modifying records or writing decrypted data. */
final class SilenceCryptoVerifier {
  SilenceCryptoVerificationInfo verify(SilenceBackupStager.Snapshot snapshot, boolean passwordDisabled,
                                       char[] suppliedPassword) throws IOException {
    checkCancelled();
    if (!passwordDisabled && (suppliedPassword == null || suppliedPassword.length == 0))
      return SilenceCryptoVerificationInfo.passwordRequired();
    char[] password = passwordDisabled ? new char[]{'u','n','e','n','c','r','y','p','t','e','d'} : suppliedPassword.clone();
    try {
      checkCancelled();
      Map<String, String> preferences = SilencePreferencesReader.read(
          new File(snapshot.root(), SilenceBackupDetector.SECRET_PREFS));
      try (SilenceLegacyCipher cipher = SilenceLegacyCipher.unlock(preferences, password);
           SQLiteDatabase db = SQLiteDatabase.openDatabase(new File(snapshot.root(), "databases/messages.db").getAbsolutePath(),
               null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS, ignored -> { });
           Cursor cursor = db.rawQuery("SELECT type, body FROM sms ORDER BY _id", null)) {
        long checked = 0;
        long unchecked = 0;
        while (cursor.moveToNext()) {
          checkCancelled();
          long type = cursor.getLong(0);
          boolean symmetric = (type & 0x80000000L) != 0;
          boolean asymmetric = (type & 0x40000000L) != 0;
          if (symmetric && asymmetric) return SilenceCryptoVerificationInfo.rejected();
          String body = cursor.getString(1);
          if (!symmetric || body == null || body.isEmpty()) { unchecked++; continue; }
          cipher.verifyBody(body);
          checked++;
        }
        SilenceIdentityInfo identities = new SilenceIdentityVerifier().verify(preferences, cipher);
        return SilenceCryptoVerificationInfo.verified(checked, unchecked).withIdentities(identities).withFiles(
            new SilenceCryptoFileVerifier().verify(snapshot, cipher, preferences, identities));
      } catch (GeneralSecurityException | IOException e) {
        checkCancelled();
        // Wrong passwords and damaged records deliberately share a non-sensitive result.
        return SilenceCryptoVerificationInfo.rejected();
      }
    } finally { Arrays.fill(password, '\0'); }
  }

  private static void checkCancelled() throws IOException {
    if (Thread.currentThread().isInterrupted()) throw new IOException("Crypto verification cancelled");
  }
}
