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
import com.google.protobuf.ByteString;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jimvixx.smsecure.util.Base64;
import org.junit.*;
import org.junit.runner.RunWith;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.state.StorageProtos;
import static org.junit.Assert.*;

/** Synthetic identity rows with independently computed legacy MACs. */
@RunWith(AndroidJUnit4.class)
public class SilenceRemoteIdentityVerifierTest {
  private SilenceTestBackup fixture;
  private SilenceLegacyCipher cipher;
  private ByteString remote;
  private String encoded;
  @Before public void setup() throws Exception {
    fixture = new SilenceTestBackup();
    assertTrue(new File(fixture.input, "files/signed_prekeys/1").delete());
    File secret = new File(fixture.input, SilenceBackupDetector.SECRET_PREFS);
    try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("silence/crypto-disabled.xml");
         OutputStream out = new FileOutputStream(secret)) {
      byte[] buffer = new byte[4096]; int length;
      while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
    }
    cipher = SilenceLegacyCipher.unlock(SilencePreferencesReader.read(secret), "unencrypted".toCharArray());
    remote = ByteString.copyFrom(Curve.generateKeyPair().getPublicKey().serialize());
    encoded = Base64.encodeBytes(remote.toByteArray());
    row(1, encoded, mac(1, encoded));
  }
  @After public void cleanup() throws Exception {
    if (cipher != null) cipher.close();
    if (fixture != null) fixture.close();
  }
  @Test public void authenticatesRecordAndComparesCurrentKey() throws Exception {
    SilenceRemoteIdentityIndex index = load();
    index.compare(1, remote, false);
    assertEquals(SilenceRemoteIdentityInfo.Status.AUTHENTICATED, index.info().getStatus());
    assertEquals(1, index.info().getRecordCount());
    assertEquals(1, index.info().getCurrentMatches());
  }
  @Test public void rejectsDamagedMac() throws Exception {
    row(1, encoded, Base64.encodeBytes(new byte[20])); rejected();
  }
  @Test public void macBindsRecipientIdentifier() throws Exception {
    row(1, encoded, mac(2, encoded)); rejected();
  }
  @Test public void macBindsExactSerializedKeyText() throws Exception {
    row(1, encoded + "\n", mac(1, encoded)); rejected();
    row(1, encoded + "\n", mac(1, encoded + "\n"));
    assertEquals(SilenceRemoteIdentityInfo.Status.AUTHENTICATED, load().info().getStatus());
  }
  @Test public void rejectsAlteredKeyWithOriginalMac() throws Exception {
    String other = Base64.encodeBytes(Curve.generateKeyPair().getPublicKey().serialize());
    row(1, other, mac(1, encoded)); rejected();
  }
  @Test public void rejectsMalformedKeyAndMacLengths() throws Exception {
    for (String key : new String[]{"bad!", Base64.encodeBytes(new byte[32]), Base64.encodeBytes(new byte[33])}) {
      row(1, key, mac(1, key)); rejected();
    }
    row(1, encoded, Base64.encodeBytes(new byte[19])); rejected();
  }
  @Test public void rejectsMissingRecipientReference() throws Exception {
    row(2, encoded, mac(2, encoded)); rejected();
  }
  @Test public void rejectsDuplicateRecipientWithoutPartialResults() throws Exception {
    fixture.mutate("DROP TABLE identities", "CREATE TABLE identities (_id INTEGER PRIMARY KEY, recipient INTEGER, key TEXT, mac TEXT)");
    row(1, encoded, mac(1, encoded));
    fixture.mutate("INSERT INTO identities SELECT 8, recipient, key, mac FROM identities");
    SilenceRemoteIdentityInfo info = load().info();
    assertEquals(SilenceRemoteIdentityInfo.Status.REJECTED, info.getStatus());
    assertEquals(0, info.getRecordCount());
  }
  @Test public void reportsMissingAndDifferentCurrentKeysSeparately() throws Exception {
    SilenceRemoteIdentityIndex index = load();
    index.compare(2, remote, false);
    index.compare(1, ByteString.copyFrom(Curve.generateKeyPair().getPublicKey().serialize()), false);
    assertEquals(1, index.info().getCurrentMissing());
    assertEquals(1, index.info().getCurrentDifferent());
    assertEquals(0, index.info().getCurrentMatches());
  }
  @Test public void archivedDifferencesDoNotBecomeCurrentMismatches() throws Exception {
    SilenceRemoteIdentityIndex index = load();
    index.compare(1, remote, true);
    index.compare(1, ByteString.copyFrom(Curve.generateKeyPair().getPublicKey().serialize()), true);
    assertEquals(1, index.info().getArchivedMatches());
    assertEquals(1, index.info().getArchivedUnmatched());
    assertEquals(0, index.info().getCurrentDifferent());
  }
  @Test public void failedAuthenticationDisablesComparisons() throws Exception {
    row(1, encoded, "bad!");
    SilenceRemoteIdentityIndex index = load(); index.compare(1, remote, false);
    assertEquals(SilenceRemoteIdentityInfo.Status.REJECTED, index.info().getStatus());
    assertEquals(0, index.info().getCurrentMatches());
    assertEquals(0, index.info().getCurrentMissing());
  }
  @Test public void emptyIdentityTableDoesNotEstablishTrust() throws Exception {
    fixture.mutate("DELETE FROM identities");
    SilenceRemoteIdentityIndex index = load(); index.compare(1, remote, false);
    assertEquals(SilenceRemoteIdentityInfo.Status.AUTHENTICATED, index.info().getStatus());
    assertEquals(0, index.info().getRecordCount());
    assertEquals(1, index.info().getCurrentMissing());
  }
  @Test public void sessionTraversalDistinguishesCurrentAndArchivedRemoteKeys() throws Exception {
    SilenceRemoteIdentityIndex index = load();
    ECKeyPair local = Curve.generateKeyPair();
    Map<String, String> preferences = new HashMap<>();
    preferences.put(SilenceIdentityVerifier.PUBLIC + "_3", "string:" + Base64.encodeBytes(local.getPublicKey().serialize()));
    StorageProtos.SessionStructure current = SilenceSessionTestData.active(local).toBuilder().setRemoteIdentityPublic(remote).build();
    StorageProtos.RecordStructure record = StorageProtos.RecordStructure.newBuilder().setCurrentSession(current)
        .addPreviousSessions(SilenceSessionTestData.active(local)).build();
    new SilenceSessionVerifier(preferences, SilenceIdentityInfo.verified(1), index).verify(record.toByteArray(), 2, "1.3");
    assertEquals(1, index.info().getCurrentMatches()); assertEquals(1, index.info().getArchivedUnmatched());
  }
  @Test public void cancellationPropagates() throws Exception {
    try (SilenceBackupStager.Snapshot snapshot = new SilenceBackupStager().stage(fixture.source(), fixture.output);
         SQLiteDatabase addresses = addresses(snapshot)) {
      Thread.currentThread().interrupt();
      try { assertThrows(IOException.class, () -> SilenceRemoteIdentityIndex.load(snapshot, cipher, addresses)); }
      finally { Thread.interrupted(); }
    }
  }
  @Test public void coordinatorReportsIntegrityAndPreservesSourceWithoutPromotingTrust() throws Exception {
    fixture.mutate("UPDATE sms SET type = 20, body = 'synthetic plaintext'");
    File db = new File(fixture.input, "databases/messages.db"); byte[] before = SilenceTestBackup.digest(db);
    SilencePreflightResult result = SilenceImportCoordinator.inspect(fixture.source(), fixture.output);
    assertEquals(SilenceRemoteIdentityInfo.Status.AUTHENTICATED,
        result.getCryptoVerification().getFiles().getRemoteIdentities().getStatus());
    assertFalse(result.isReadyToImport()); assertArrayEquals(before, SilenceTestBackup.digest(db));
    assertEquals(0, new File(fixture.output, "silence-preflight").list().length);
  }
  private SilenceRemoteIdentityIndex load() throws Exception {
    try (SilenceBackupStager.Snapshot snapshot = new SilenceBackupStager().stage(fixture.source(), fixture.output);
         SQLiteDatabase addresses = addresses(snapshot)) {
      return SilenceRemoteIdentityIndex.load(snapshot, cipher, addresses);
    }
  }
  private static SQLiteDatabase addresses(SilenceBackupStager.Snapshot snapshot) {
    return SQLiteDatabase.openDatabase(new File(snapshot.root(), "databases/canonical_address.db").getAbsolutePath(),
        null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS, ignored -> { });
  }
  private void rejected() throws Exception { assertEquals(SilenceRemoteIdentityInfo.Status.REJECTED, load().info().getStatus()); }
  private void row(long recipient, String key, String mac) {
    try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(fixture.input, "databases/messages.db"), null)) {
      db.delete("identities", null, null);
      ContentValues values = new ContentValues(); values.put("_id", 7); values.put("recipient", recipient);
      values.put("key", key); values.put("mac", mac); db.insertOrThrow("identities", null, values);
    }
  }
  private static String mac(long recipient, String encoded) throws Exception {
    byte[] key = new byte[20]; for (int i = 0; i < key.length; i++) key[i] = (byte) (16 + i);
    Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(key, "HmacSHA1"));
    return Base64.encodeBytes(mac.doFinal((Long.toString(recipient) + encoded).getBytes(StandardCharsets.UTF_8)));
  }
}
