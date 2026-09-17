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
import java.io.*;
import java.util.*;
import org.jimvixx.smsecure.util.Base64;
import org.junit.*;
import org.junit.runner.RunWith;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SilenceIdentityVerifierTest {
  private SilenceTestBackup fixture;
  private SilenceLegacyCipher cipher;
  private Map<String, String> preferences;

  @Before public void setup() throws Exception {
    fixture = new SilenceTestBackup();
    File secret = new File(fixture.input, SilenceBackupDetector.SECRET_PREFS);
    try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("silence/crypto-disabled.xml");
         OutputStream out = new FileOutputStream(secret)) {
      byte[] buffer = new byte[4096]; int length;
      while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
    }
    preferences = SilencePreferencesReader.read(secret);
    cipher = SilenceLegacyCipher.unlock(preferences, "unencrypted".toCharArray());
  }
  @After public void cleanup() throws Exception {
    if (cipher != null) cipher.close();
    if (fixture != null) fixture.close();
  }
  @Test public void reportsAbsentIdentitiesWithoutClaimingSuccess() throws Exception {
    assertEquals(SilenceIdentityInfo.Status.ABSENT, verify().getStatus());
  }
  @Test public void validatesUnscopedAndMultipleSubscriptionKeyPairs() throws Exception {
    add(""); add("_0"); add("_12");
    SilenceIdentityInfo info = verify();
    assertEquals(SilenceIdentityInfo.Status.VERIFIED, info.getStatus());
    assertEquals(3, info.getCount());
  }
  @Test public void rejectsMismatchedPublicAndPrivateKeys() throws Exception {
    add("");
    preferences.put(SilenceIdentityVerifier.PUBLIC, encode(Curve.generateKeyPair().getPublicKey().serialize()));
    rejected();
  }
  @Test public void rejectsMissingPrivateKey() throws Exception {
    add("_1"); preferences.remove(SilenceIdentityVerifier.PRIVATE + "_1"); rejected();
  }
  @Test public void rejectsMissingPublicKey() throws Exception {
    add("_1"); preferences.remove(SilenceIdentityVerifier.PUBLIC + "_1"); rejected();
  }
  @Test public void rejectsWrongPreferenceType() throws Exception {
    add(""); preferences.put(SilenceIdentityVerifier.PUBLIC, "int:5"); rejected();
  }
  @Test public void rejectsCorruptPrivateKeyMac() throws Exception {
    add("");
    byte[] value = Base64.decode(preferences.get(SilenceIdentityVerifier.PRIVATE).substring(7), Base64.DONT_GUNZIP);
    value[value.length - 1] ^= 1;
    preferences.put(SilenceIdentityVerifier.PRIVATE, encode(value)); rejected();
  }
  @Test public void rejectsAuthenticatedWrongPrivateKeyLength() throws Exception {
    add(""); preferences.put(SilenceIdentityVerifier.PRIVATE, seal(new byte[31])); rejected();
  }
  @Test public void rejectsMalformedPublicKeyAndEncoding() throws Exception {
    add("");
    for (String value : new String[]{"string:invalid!", encode(new byte[32]), encode(new byte[33])}) {
      preferences.put(SilenceIdentityVerifier.PUBLIC, value); rejected();
    }
  }
  @Test public void rejectsNonCanonicalIdentitySlots() throws Exception {
    for (String suffix : new String[]{"_01", "_-1", "_2147483648", "_", "suffix"}) {
      add(suffix); rejected();
      preferences.remove(SilenceIdentityVerifier.PUBLIC + suffix);
      preferences.remove(SilenceIdentityVerifier.PRIVATE + suffix);
    }
  }
  @Test public void cancellationPropagates() {
    Thread.currentThread().interrupt();
    try { assertThrows(IOException.class, this::verify); }
    finally { Thread.interrupted(); }
  }
  @Test public void doesNotMutateSuppliedPreferences() throws Exception {
    add("_3"); Map<String, String> before = new HashMap<>(preferences);
    assertEquals(SilenceIdentityInfo.Status.VERIFIED, verify().getStatus());
    assertEquals(before, preferences);
  }
  private void add(String suffix) throws Exception {
    ECKeyPair pair = Curve.generateKeyPair();
    byte[] privateKey = pair.getPrivateKey().serialize();
    try {
      preferences.put(SilenceIdentityVerifier.PUBLIC + suffix, encode(pair.getPublicKey().serialize()));
      preferences.put(SilenceIdentityVerifier.PRIVATE + suffix, seal(privateKey));
    } finally { Arrays.fill(privateKey, (byte) 0); }
  }
  private static String encode(byte[] bytes) { return "string:" + Base64.encodeBytes(bytes); }
  private static String seal(byte[] plaintext) throws Exception {
    byte[] file = SilenceCryptoFileVerifierTest.envelope(1, plaintext);
    return encode(Arrays.copyOfRange(file, 8, file.length));
  }
  private SilenceIdentityInfo verify() throws IOException { return new SilenceIdentityVerifier().verify(preferences, cipher); }
  private void rejected() throws Exception { assertEquals(SilenceIdentityInfo.Status.REJECTED, verify().getStatus()); }
}
