/*
 * Copyright (C) 2011 Whisper Systems
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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.crypto.MasterCipher;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.Base64;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.InvalidKeyException;

import java.io.IOException;

public class IdentityDatabase extends Database {

  public static final String RECIPIENT = "recipient";
  public static final String IDENTITY_KEY = "identity_key";
  public static final String MAC = "mac";
  /**
   * Persistent verification state (INTEGER):
   * 0 = unknown/idle
   * 1 = verified (matches)
   * 2 = mismatch (last scan did not match)
   */
  public static final String VERIFIED = "verified";
  public static final int VERIFY_STATE_UNKNOWN = 0;
  public static final int VERIFY_STATE_VERIFIED = 1;
  public static final int VERIFY_STATE_MISMATCH = 2;
  private static final String TAG = "IdentityDatabase";
  private static final Uri CHANGE_URI = DatabaseContentProviders.Identities.CONTENT_URI;
  private static final String TABLE_NAME = "identities";
  private static final String ID = "_id";
  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME +
          " (" + ID + " INTEGER PRIMARY KEY, " +
          RECIPIENT + " INTEGER UNIQUE, " +
          IDENTITY_KEY + " TEXT, " +
          MAC + " TEXT, " +
          VERIFIED + " INTEGER DEFAULT 0);";

  public IdentityDatabase(Context context, SQLiteOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  private static int normalizeState(int state) {
    if (state == VERIFY_STATE_VERIFIED) return VERIFY_STATE_VERIFIED;
    if (state == VERIFY_STATE_MISMATCH) return VERIFY_STATE_MISMATCH;
    return VERIFY_STATE_UNKNOWN;
  }

  public Cursor getIdentities() {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor cursor = database.query(TABLE_NAME, null, null, null, null, null, null);

    cursor.setNotificationUri(context.getContentResolver(), CHANGE_URI);

    return cursor;
  }

  /**
   * TOFU validation:
   * - If we have a stored identity for recipient: compare keys (after MAC validation).
   * - If no stored identity yet: return true (trust on first use).
   */
  public boolean isValidIdentity(@NonNull MasterSecret masterSecret,
                                 long recipientId,
                                 @NonNull IdentityKey theirIdentity) {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    MasterCipher masterCipher = new MasterCipher(masterSecret);

    try (Cursor cursor = database.query(TABLE_NAME, null, RECIPIENT + " = ?",
            new String[]{String.valueOf(recipientId)}, null, null, null)) {

      if (cursor.moveToFirst()) {
        String serializedIdentity = cursor.getString(cursor.getColumnIndexOrThrow(IDENTITY_KEY));
        String mac = cursor.getString(cursor.getColumnIndexOrThrow(MAC));

        if (!masterCipher.verifyMacFor(recipientId + serializedIdentity, Base64.decode(mac))) {
          Log.w(TAG, "MAC failed");
          return false;
        }

        IdentityKey storedIdentity = new IdentityKey(Base64.decode(serializedIdentity), 0);
        return storedIdentity.equals(theirIdentity);
      } else {
        return true;
      }
    } catch (IOException | InvalidKeyException e) {
      Log.w(TAG, e);
      return false;
    }
  }

  /**
   * Saves identity and resets verification state (because identity changed / could be new).
   */
  public void saveIdentity(@NonNull MasterSecret masterSecret, long recipientId, @NonNull IdentityKey identityKey) {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    MasterCipher masterCipher = new MasterCipher(masterSecret);

    String identityKeyString = Base64.encodeBytes(identityKey.serialize());
    String macString = Base64.encodeBytes(masterCipher.getMacFor(recipientId + identityKeyString));

    ContentValues contentValues = new ContentValues();
    contentValues.put(RECIPIENT, recipientId);
    contentValues.put(IDENTITY_KEY, identityKeyString);
    contentValues.put(MAC, macString);

    // IMPORTANT: new identity => reset verification state to unknown
    contentValues.put(VERIFIED, VERIFY_STATE_UNKNOWN);

    database.replace(TABLE_NAME, null, contentValues);

    context.getContentResolver().notifyChange(CHANGE_URI, null);
  }

  public void deleteIdentity(long id) {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    database.delete(TABLE_NAME, ID_WHERE, new String[]{String.valueOf(id)});

    context.getContentResolver().notifyChange(CHANGE_URI, null);
  }

  /**
   * true => VERIFIED, false => UNKNOWN.
   * If row doesn't exist yet, creates a minimal row (recipient + state).
   */
  public void setVerified(long recipientId, boolean verified) {
    setVerificationState(recipientId, verified ? VERIFY_STATE_VERIFIED : VERIFY_STATE_UNKNOWN);
  }

  /**
   * Convenience: clears verification state to unknown.
   */
  public void clearVerified(long recipientId) {
    setVerificationState(recipientId, VERIFY_STATE_UNKNOWN);
  }

  /**
   * Returns true only when state == VERIFIED.
   * Missing row => false.
   */
  public boolean isVerified(long recipientId) {
    return getVerificationState(recipientId) == VERIFY_STATE_VERIFIED;
  }

  /**
   * Set explicit verification state.
   * If row doesn't exist yet, creates a minimal row (recipient + state).
   */
  public void setVerificationState(long recipientId, int state) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();

    ContentValues cv = new ContentValues();
    cv.put(VERIFIED, normalizeState(state));

    int updated = db.update(TABLE_NAME, cv, RECIPIENT + " = ?", new String[]{String.valueOf(recipientId)});

    if (updated == 0) {
      cv.put(RECIPIENT, recipientId);
      db.insert(TABLE_NAME, null, cv);
    }

    context.getContentResolver().notifyChange(CHANGE_URI, null);
  }

  /**
   * Get verification state.
   * Missing row => UNKNOWN.
   */
  public int getVerificationState(long recipientId) {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();

    try (Cursor c = db.query(TABLE_NAME,
            new String[]{VERIFIED},
            RECIPIENT + " = ?",
            new String[]{String.valueOf(recipientId)},
            null, null, null)) {

      if (!c.moveToFirst()) return VERIFY_STATE_UNKNOWN;

      int idx = c.getColumnIndex(VERIFIED);
      if (idx < 0) return VERIFY_STATE_UNKNOWN;
      if (c.isNull(idx)) return VERIFY_STATE_UNKNOWN;

      return normalizeState(c.getInt(idx));
    }
  }

  /**
   * Useful for QR flow:
   * - If scanned identity matches stored identity => state=VERIFIED and return true.
   * - If not matches (or MAC invalid) => state=MISMATCH and return false.
   * - If no stored identity yet (TOFU) => returns true and sets state=VERIFIED.
   */
  public boolean setVerifiedIfIdentityMatches(@NonNull MasterSecret masterSecret,
                                              long recipientId,
                                              @NonNull IdentityKey theirIdentity) {
    boolean ok = isValidIdentity(masterSecret, recipientId, theirIdentity);
    setVerificationState(recipientId, ok ? VERIFY_STATE_VERIFIED : VERIFY_STATE_MISMATCH);
    return ok;
  }

  /**
   * Stronger status check:
   * - state must be VERIFIED
   * - identity must still match the stored identity
   * <p>
   * If identity key changed later, this returns false even if state was left VERIFIED somehow.
   */
  public boolean isVerifiedAndIdentityMatches(@NonNull MasterSecret masterSecret,
                                              long recipientId,
                                              @NonNull IdentityKey theirIdentity) {
    if (getVerificationState(recipientId) != VERIFY_STATE_VERIFIED) return false;
    return isValidIdentity(masterSecret, recipientId, theirIdentity);
  }

  public Reader readerFor(MasterSecret masterSecret, Cursor cursor) {
    return new Reader(masterSecret, cursor);
  }

  public static class Identity {
    private final Recipients recipients;
    private final IdentityKey identityKey;

    /**
     * Kept for backwards compatibility: true only when state == VERIFIED.
     */
    private final boolean verified;

    /**
     * New field: full verification state (UNKNOWN/VERIFIED/MISMATCH).
     */
    private final int verificationState;

    public Identity(Recipients recipients, IdentityKey identityKey, boolean verified) {
      this(recipients, identityKey, verified, verified ? VERIFY_STATE_VERIFIED : VERIFY_STATE_UNKNOWN);
    }

    public Identity(Recipients recipients, IdentityKey identityKey, boolean verified, int verificationState) {
      this.recipients = recipients;
      this.identityKey = identityKey;
      this.verified = verified;
      this.verificationState = normalizeState(verificationState);
    }

    public Recipients getRecipients() {
      return recipients;
    }

    public IdentityKey getIdentityKey() {
      return identityKey;
    }

    public boolean isVerified() {
      return verified;
    }

    public int getVerificationState() {
      return verificationState;
    }
  }

  public class Reader {
    private final Cursor cursor;
    private final MasterCipher cipher;

    public Reader(MasterSecret masterSecret, Cursor cursor) {
      this.cursor = cursor;
      this.cipher = new MasterCipher(masterSecret);
    }

    public Identity getCurrent() {
      long recipientId = cursor.getLong(cursor.getColumnIndexOrThrow(RECIPIENT));
      Recipients recipients = RecipientFactory.getRecipientsForIds(context, new long[]{recipientId}, true);

      try {
        String identityKeyString = cursor.getString(cursor.getColumnIndexOrThrow(IDENTITY_KEY));
        String mac = cursor.getString(cursor.getColumnIndexOrThrow(MAC));

        if (!cipher.verifyMacFor(recipientId + identityKeyString, Base64.decode(mac))) {
          // MAC failed: treat identity as unavailable and state as unknown.
          return new Identity(recipients, null, false, VERIFY_STATE_UNKNOWN);
        }

        IdentityKey identityKey = new IdentityKey(Base64.decode(identityKeyString), 0);

        int state = VERIFY_STATE_UNKNOWN;
        int idx = cursor.getColumnIndex(VERIFIED);
        if (idx >= 0 && !cursor.isNull(idx)) state = normalizeState(cursor.getInt(idx));

        boolean verified = state == VERIFY_STATE_VERIFIED;

        return new Identity(recipients, identityKey, verified, state);
      } catch (IOException | InvalidKeyException e) {
        Log.w(TAG, e);
        return new Identity(recipients, null, false, VERIFY_STATE_UNKNOWN);
      }
    }
  }
}
