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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.protobuf.ByteString;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.whispersystems.libsignal.state.StorageProtos;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.jimvixx.smsecure.util.Base64;
import java.io.*;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

/** Synthetic protobuf structures and independently sealed legacy file envelopes. */
@RunWith(AndroidJUnit4.class)
public class SilenceCryptoFileVerifierTest {
  private SilenceTestBackup fixture;
  private SilenceLegacyCipher cipher;
  private ECKeyPair signingIdentity;

  @Before public void setup() throws Exception {
    fixture = new SilenceTestBackup();
    assertTrue(new File(fixture.input, "files/signed_prekeys/1").delete());
    File secret = new File(fixture.input, SilenceBackupDetector.SECRET_PREFS);
    try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("silence/crypto-disabled.xml");
         OutputStream out = new FileOutputStream(secret)) {
      byte[] buffer = new byte[4096];
      int length;
      while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
    }
    signingIdentity = Curve.generateKeyPair();
    addIdentity(secret, 3, Curve.generateKeyPair());
    addIdentity(secret, 6, signingIdentity);
    cipher = SilenceLegacyCipher.unlock(SilencePreferencesReader.read(secret), "unencrypted".toCharArray());
  }
  @After public void cleanup() throws Exception {
    if (cipher != null) cipher.close();
    if (fixture != null) fixture.close();
  }

  @Test public void acceptsAbsentCryptoDirectoriesWithoutClaimingImportReadiness() throws Exception {
    SilenceCryptoFileInfo info = verify();
    assertEquals(SilenceCryptoFileInfo.Status.READABLE, info.getStatus());
    assertEquals(0, info.getSessionCount());
    assertEquals(0, info.getPreKeyCount());
    assertEquals(0, info.getSignedPreKeyCount());
  }

  @Test public void authenticatesBothSessionVersionsAndBothPreKeyTypes() throws Exception {
    write("sessions-v2/1", envelope(1, session()));
    write("sessions-v2/1.3", envelope(2, StorageProtos.RecordStructure.newBuilder()
        .setCurrentSession(StorageProtos.SessionStructure.parseFrom(session())).build().toByteArray()));
    write("prekeys/123", envelope(1, preKey()));
    write("signed_prekeys/456", envelope(1, signedPreKey()));
    SilenceCryptoFileInfo info = verify();
    assertEquals(SilenceCryptoFileInfo.Status.READABLE, info.getStatus());
    assertEquals(2, info.getSessionCount());
    assertEquals(1, info.getPreKeyCount());
    assertEquals(1, info.getSignedPreKeyCount());
  }

  @Test public void rejectsUnknownVersions() throws Exception {
    for (int version : new int[]{-1, 0, 3, Integer.MAX_VALUE}) {
      write("sessions-v2/1", envelope(version, session()));
      rejected();
    }
    assertTrue(new File(fixture.input, "files/sessions-v2/1").delete());
    write("prekeys/123", envelope(2, preKey()));
    rejected();
  }

  @Test public void rejectsTamperedCiphertext() throws Exception {
    byte[] record = envelope(1, preKey());
    record[30] ^= 1;
    write("prekeys/123", record);
    rejected();
  }

  @Test public void boundsDeclaredLengthBeforeAllocation() throws Exception {
    for (int length : new int[]{-1, 0, 51, 1024 * 1024 + 1, Integer.MAX_VALUE}) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeInt(1); out.writeInt(length);
      write("prekeys/123", bytes.toByteArray());
      rejected();
    }
  }

  @Test public void rejectsTruncatedAndTrailingData() throws Exception {
    byte[] valid = envelope(1, preKey());
    for (int length : new int[]{1, 4, 7, valid.length - 1, valid.length + 1}) {
      write("prekeys/123", Arrays.copyOf(valid, length));
      rejected();
    }
  }

  @Test public void rejectsAuthenticatedMalformedProtobuf() throws Exception {
    write("prekeys/123", envelope(1, new byte[]{(byte) 0x80}));
    rejected();
  }

  @Test public void rejectsMissingPreKeyFieldsAndBadKeyLengths() throws Exception {
    write("prekeys/123", envelope(1, StorageProtos.PreKeyRecordStructure.newBuilder().setId(12).build().toByteArray()));
    rejected();
    write("prekeys/123", envelope(1, StorageProtos.PreKeyRecordStructure.parseFrom(preKey()).toBuilder()
        .setPrivateKey(ByteString.copyFrom(new byte[31])).build().toByteArray()));
    rejected();
  }

  @Test public void rejectsMissingSignedPreKeySignature() throws Exception {
    write("signed_prekeys/456", envelope(1, StorageProtos.SignedPreKeyRecordStructure.parseFrom(signedPreKey())
        .toBuilder().clearSignature().build().toByteArray()));
    rejected();
  }

  @Test public void rejectsUnknownFilesInsteadOfIgnoringThem() throws Exception {
    write("prekeys/123.tmp", envelope(1, preKey()));
    rejected();
  }

  @Test public void cancellationPropagates() throws Exception {
    try (SilenceBackupStager.Snapshot snapshot = new SilenceBackupStager().stage(fixture.source(), fixture.output)) {
      Thread.currentThread().interrupt();
      try { assertThrows(IOException.class, () -> new SilenceCryptoFileVerifier().verify(snapshot, cipher)); }
      finally { Thread.interrupted(); }
    }
  }

  @Test public void sourceFilesRemainByteIdentical() throws Exception {
    File file = write("prekeys/123", envelope(1, preKey()));
    byte[] before = SilenceTestBackup.digest(file);
    assertEquals(SilenceCryptoFileInfo.Status.READABLE, verify().getStatus());
    assertArrayEquals(before, SilenceTestBackup.digest(file));
  }

  @Test public void coordinatorReportsFileFailureSeparatelyFromSuccessfulSmsCheck() throws Exception {
    fixture.mutate("UPDATE sms SET type = 20, body = 'synthetic plaintext'");
    write("prekeys/123", new byte[]{1});
    SilencePreflightResult info = SilenceImportCoordinator.inspect(fixture.source(), fixture.output);
    assertEquals(SilenceCryptoVerificationInfo.Status.VERIFIED, info.getCryptoVerification().getStatus());
    assertEquals(SilenceCryptoFileInfo.Status.REJECTED, info.getCryptoVerification().getFiles().getStatus());
    assertFalse(info.isReadyToImport());
    assertEquals(0, new File(fixture.output, "silence-preflight").list().length);
  }

  @Test public void rejectsSessionWithoutSourceRecipient() throws Exception {
    write("sessions-v2/999.3", envelope(1, session()));
    rejected();
  }

  @Test public void rejectsSessionWithEmptySourceAddress() throws Exception {
    try (android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
        new File(fixture.input, "databases/canonical_address.db"), null)) {
      db.execSQL("UPDATE canonical_addresses SET address = ''");
    }
    write("sessions-v2/1", envelope(1, session()));
    rejected();
  }

  @Test public void rejectsPreKeyFilenameDisagreement() throws Exception {
    write("prekeys/456", envelope(1, preKey()));
    rejected();
  }

  @Test public void rejectsSignedPreKeyFilenameDisagreement() throws Exception {
    write("signed_prekeys/123", envelope(1, signedPreKey()));
    rejected();
  }

  @Test public void rejectsMismatchedPreKeyPairInsideAuthenticatedEnvelope() throws Exception {
    write("prekeys/123", envelope(1, StorageProtos.PreKeyRecordStructure.parseFrom(preKey()).toBuilder()
        .setPrivateKey(ByteString.copyFrom(Curve.generateKeyPair().getPrivateKey().serialize())).build().toByteArray()));
    rejected();
  }

  @Test public void rejectsSignedPreKeyWithValidMacButBadSignature() throws Exception {
    write("signed_prekeys/456", envelope(1, StorageProtos.SignedPreKeyRecordStructure.parseFrom(signedPreKey()).toBuilder()
        .setSignature(ByteString.copyFrom(new byte[64])).build().toByteArray()));
    rejected();
  }

  @Test public void rejectsSignedPreKeyFromAnotherSourceSlot() throws Exception {
    write("signed_prekeys/453", envelope(1, signedPreKey()));
    rejected();
  }

  @Test public void rejectsPreKeyWithoutMatchingSourceIdentity() throws Exception {
    write("prekeys/129", envelope(1, preKey()));
    rejected();
  }

  @Test public void coordinatorChecksSignedPreKeyAndRemovesStaging() throws Exception {
    fixture.mutate("UPDATE sms SET type = 20, body = 'synthetic plaintext'");
    write("signed_prekeys/456", envelope(1, signedPreKey()));
    SilencePreflightResult result = SilenceImportCoordinator.inspect(fixture.source(), fixture.output);
    assertEquals(SilenceIdentityInfo.Status.VERIFIED, result.getCryptoVerification().getIdentities().getStatus());
    assertEquals(SilenceCryptoFileInfo.Status.READABLE, result.getCryptoVerification().getFiles().getStatus());
    assertEquals(1, result.getCryptoVerification().getFiles().getSignedPreKeyCount());
    assertFalse(result.isReadyToImport());
    assertEquals(0, new File(fixture.output, "silence-preflight").list().length);
  }

  private SilenceCryptoFileInfo verify() throws Exception {
    try (SilenceBackupStager.Snapshot snapshot = new SilenceBackupStager().stage(fixture.source(), fixture.output)) {
      return new SilenceCryptoFileVerifier().verify(snapshot, cipher);
    }
  }
  private void rejected() throws Exception {
    assertEquals(SilenceCryptoFileInfo.Status.REJECTED, verify().getStatus());
  }
  private File write(String name, byte[] value) throws IOException {
    File file = new File(fixture.input, "files/" + name);
    if (!file.getParentFile().isDirectory()) assertTrue(file.getParentFile().mkdirs());
    try (OutputStream out = new FileOutputStream(file)) { out.write(value); }
    return file;
  }
  private static byte[] session() {
    return StorageProtos.SessionStructure.newBuilder().setSessionVersion(3).build().toByteArray();
  }
  private static byte[] preKey() {
    ECKeyPair pair = Curve.generateKeyPair();
    return StorageProtos.PreKeyRecordStructure.newBuilder().setId(12)
        .setPublicKey(ByteString.copyFrom(pair.getPublicKey().serialize()))
        .setPrivateKey(ByteString.copyFrom(pair.getPrivateKey().serialize())).build().toByteArray();
  }
  private byte[] signedPreKey() throws Exception {
    ECKeyPair pair = Curve.generateKeyPair();
    byte[] publicKey = pair.getPublicKey().serialize();
    return StorageProtos.SignedPreKeyRecordStructure.newBuilder().setId(45).setTimestamp(123L)
        .setPublicKey(ByteString.copyFrom(publicKey)).setPrivateKey(ByteString.copyFrom(pair.getPrivateKey().serialize()))
        .setSignature(ByteString.copyFrom(Curve.calculateSignature(signingIdentity.getPrivateKey(), publicKey))).build().toByteArray();
  }
  private static void addIdentity(File secret, int subscription, ECKeyPair pair) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (InputStream in = new FileInputStream(secret)) {
      byte[] buffer = new byte[4096]; int length;
      while ((length = in.read(buffer)) != -1) bytes.write(buffer, 0, length);
    }
    String xml = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    byte[] sealed = envelope(1, pair.getPrivateKey().serialize());
    String entries = "<string name='" + SilenceIdentityVerifier.PUBLIC + "_" + subscription + "'>"
        + Base64.encodeBytes(pair.getPublicKey().serialize()) + "</string><string name='"
        + SilenceIdentityVerifier.PRIVATE + "_" + subscription + "'>"
        + Base64.encodeBytes(Arrays.copyOfRange(sealed, 8, sealed.length)) + "</string>";
    try (Writer out = new OutputStreamWriter(new FileOutputStream(secret), java.nio.charset.StandardCharsets.UTF_8)) {
      out.write(xml.replace("</map>", entries + "</map>"));
    }
  }
  static byte[] envelope(int version, byte[] plaintext) throws Exception {
    byte[] key = new byte[16], macKey = new byte[20], iv = new byte[16];
    for (int i = 0; i < key.length; i++) key[i] = (byte) i;
    for (int i = 0; i < macKey.length; i++) macKey[i] = (byte) (16 + i);
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
    ByteArrayOutputStream sealed = new ByteArrayOutputStream();
    sealed.write(iv); sealed.write(cipher.doFinal(plaintext));
    Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(macKey, "HmacSHA1"));
    sealed.write(mac.doFinal(sealed.toByteArray()));
    ByteArrayOutputStream record = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(record);
    out.writeInt(version); out.writeInt(sealed.size()); out.write(sealed.toByteArray());
    return record.toByteArray();
  }
}
