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

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.jimvixx.smsecure.util.Base64;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

/** Golden master-secret vectors were generated independently using OpenSSL PKCS12KDF. */
@RunWith(AndroidJUnit4.class)
public class SilenceCryptoVerifierTest {
  private SilenceTestBackup fixture;
  private String body;
  @Before public void setup() throws Exception {
    fixture = new SilenceTestBackup();
    body = asset("crypto-sms.b64").trim();
    setBody(body);
    useSecret("crypto-disabled.xml");
  }
  @After public void cleanup() throws Exception { if (fixture != null) fixture.close(); }

  @Test public void verifiesPasswordDisabledBackupWithLegacyIterationFallback() throws Exception {
    SilencePreflightResult result = SilenceImportCoordinator.inspect(fixture.source(), fixture.output);
    assertEquals(SilenceCryptoVerificationInfo.Status.VERIFIED, result.getCryptoVerification().getStatus());
    assertEquals(1, result.getCryptoVerification().getVerifiedSmsCount());
    assertEquals(0, result.getCryptoVerification().getUncheckedSmsCount());
    assertFalse(result.isReadyToImport());
    assertEquals(0, new File(fixture.output, "silence-preflight").list().length);
  }

  @Test public void protectedBackupRequiresPasswordWithoutGuessing() throws Exception {
    useSecret("crypto-protected.xml");
    assertEquals(SilenceCryptoVerificationInfo.Status.PASSWORD_REQUIRED, verify(false, null).getStatus());
  }

  @Test public void verifiesProtectedBackupAgainstIndependentVector() throws Exception {
    useSecret("crypto-protected.xml");
    char[] password = "fixture-password".toCharArray();
    try { assertEquals(SilenceCryptoVerificationInfo.Status.VERIFIED, verify(false, password).getStatus()); }
    finally { Arrays.fill(password, '\0'); }
  }

  @Test public void rejectsWrongPassword() throws Exception {
    useSecret("crypto-protected.xml");
    assertEquals(SilenceCryptoVerificationInfo.Status.REJECTED, verify(false, "incorrect".toCharArray()).getStatus());
  }

  @Test public void rejectsDamagedMasterSecretMac() throws Exception {
    File file = new File(fixture.input, SilenceBackupDetector.SECRET_PREFS);
    Map<String, String> preferences = SilencePreferencesReader.read(file);
    String encoded = preferences.get("master_secret").substring(7);
    byte[] damaged = Base64.decode(encoded, Base64.DONT_GUNZIP);
    damaged[damaged.length - 1] ^= 1;
    write(file, asset("crypto-disabled.xml").replace(encoded, Base64.encodeBytes(damaged)));
    assertEquals(SilenceCryptoVerificationInfo.Status.REJECTED, verify(true, null).getStatus());
  }

  @Test public void rejectsDamagedSmsMac() throws Exception {
    byte[] damaged = Base64.decode(body, Base64.DONT_GUNZIP);
    damaged[20] ^= 1;
    setBody(Base64.encodeBytes(damaged));
    assertEquals(SilenceCryptoVerificationInfo.Status.REJECTED, verify(true, null).getStatus());
  }

  @Test public void checksPaddingAfterSuccessfulSmsAuthentication() throws Exception {
    byte[] key = new byte[16];
    byte[] macKey = new byte[20];
    for (int i = 0; i < key.length; i++) key[i] = (byte) i;
    for (int i = 0; i < macKey.length; i++) macKey[i] = (byte) (16 + i);
    byte[] iv = new byte[16];
    Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
    byte[] encrypted = cipher.doFinal(new byte[16]);
    byte[] sealed = new byte[52];
    System.arraycopy(encrypted, 0, sealed, 16, 16);
    Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(macKey, "HmacSHA1"));
    mac.update(sealed, 0, 32);
    System.arraycopy(mac.doFinal(), 0, sealed, 32, 20);
    setBody(Base64.encodeBytes(sealed));
    assertEquals(SilenceCryptoVerificationInfo.Status.REJECTED, verify(true, null).getStatus());
  }

  @Test public void rejectsMalformedEncryptedRecords() throws Exception {
    for (String value : new String[]{"not base64!", Base64.encodeBytes(new byte[20]), Base64.encodeBytes(new byte[53])}) {
      setBody(value);
      assertEquals(SilenceCryptoVerificationInfo.Status.REJECTED, verify(true, null).getStatus());
    }
  }

  @Test public void reportsUnsupportedOrUnencryptedBodiesAsUnchecked() throws Exception {
    fixture.mutate("INSERT INTO sms (_id, thread_id, type, body) VALUES (2, 1, 1073741844, 'asymmetric-record')",
        "INSERT INTO sms (_id, thread_id, type, body) VALUES (3, 1, 20, 'synthetic-plaintext')",
        "INSERT INTO sms (_id, thread_id, type, body) VALUES (4, 1, 2147483668, NULL)");
    SilenceCryptoVerificationInfo result = verify(true, null);
    assertEquals(SilenceCryptoVerificationInfo.Status.VERIFIED, result.getStatus());
    assertEquals(1, result.getVerifiedSmsCount());
    assertEquals(3, result.getUncheckedSmsCount());
  }

  @Test public void rejectsConflictingEncryptionFlags() throws Exception {
    fixture.mutate("UPDATE sms SET type = 3221225492");
    assertEquals(SilenceCryptoVerificationInfo.Status.REJECTED, verify(true, null).getStatus());
  }

  @Test public void boundsUntrustedKdfCost() throws Exception {
    write(new File(fixture.input, SilenceBackupDetector.SECRET_PREFS),
        asset("crypto-protected.xml").replace("value='10000'", "value='2147483647'"));
    assertEquals(SilenceCryptoVerificationInfo.Status.REJECTED, verify(false, "fixture-password".toCharArray()).getStatus());
  }

  @Test public void cancellationDoesNotBecomeAuthenticationFailure() throws Exception {
    try (SilenceBackupStager.Snapshot snapshot = new SilenceBackupStager().stage(fixture.source(), fixture.output)) {
      Thread.currentThread().interrupt();
      try { assertThrows(IOException.class, () -> new SilenceCryptoVerifier().verify(snapshot, true, null)); }
      finally { Thread.interrupted(); }
    }
  }

  @Test public void closesAndWipesOwnedMasterKeyBuffers() throws Exception {
    SilenceLegacyCipher cipher = SilenceLegacyCipher.unlock(SilencePreferencesReader.read(
        new File(fixture.input, SilenceBackupDetector.SECRET_PREFS)), "unencrypted".toCharArray());
    cipher.verifyBody(body);
    cipher.close();
    assertThrows(IllegalStateException.class, () -> cipher.verifyBody(body));
    for (String name : new String[]{"encryptionKey", "macKey"}) {
      java.lang.reflect.Field field = SilenceLegacyCipher.class.getDeclaredField(name);
      field.setAccessible(true);
      byte[] key = (byte[]) field.get(cipher);
      assertArrayEquals(new byte[key.length], key);
    }
  }

  @Test public void leavesSourceDatabaseAndSecretPreferencesUnchanged() throws Exception {
    File db = new File(fixture.input, "databases/messages.db");
    File prefs = new File(fixture.input, SilenceBackupDetector.SECRET_PREFS);
    byte[] databaseBefore = SilenceTestBackup.digest(db);
    byte[] preferencesBefore = SilenceTestBackup.digest(prefs);
    assertEquals(SilenceCryptoVerificationInfo.Status.VERIFIED, verify(true, null).getStatus());
    assertArrayEquals(databaseBefore, SilenceTestBackup.digest(db));
    assertArrayEquals(preferencesBefore, SilenceTestBackup.digest(prefs));
  }

  private SilenceCryptoVerificationInfo verify(boolean disabled, char[] password) throws Exception {
    try (SilenceBackupStager.Snapshot snapshot = new SilenceBackupStager().stage(fixture.source(), fixture.output)) {
      return new SilenceCryptoVerifier().verify(snapshot, disabled, password);
    }
  }
  private void useSecret(String name) throws Exception {
    write(new File(fixture.input, SilenceBackupDetector.SECRET_PREFS), asset(name));
  }
  private void setBody(String value) {
    try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(fixture.input, "databases/messages.db"), null)) {
      ContentValues values = new ContentValues();
      values.put("body", value);
      db.update("sms", values, "_id = 1", null);
    }
  }
  private static String asset(String name) throws Exception {
    try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("silence/" + name);
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int length;
      while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
      return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
  }
  private static void write(File file, String value) throws IOException {
    try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) { writer.write(value); }
  }
}
