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
import java.io.IOException;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.jimvixx.smsecure.util.Base64;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import static org.junit.Assert.*;

/** Tests exact source-slot selection independently of envelope parsing. */
@RunWith(AndroidJUnit4.class)
public class SilencePreKeyVerifierTest {
  private Map<String, String> preferences;
  private ECKeyPair identity;
  private ECKeyPair preKey;
  private SilencePreKeyVerifier verifier;
  @Before public void setup() {
    identity = Curve.generateKeyPair(); preKey = Curve.generateKeyPair(); preferences = new HashMap<>();
    preferences.put(SilenceIdentityVerifier.PUBLIC + "_3", "string:" + Base64.encodeBytes(identity.getPublicKey().serialize()));
    verifier = new SilencePreKeyVerifier(preferences, SilenceIdentityInfo.verified(1));
  }
  @Test public void verifiesScopedUnsignedPair() throws Exception {
    verifier.verify(3, publicKey(), privateKey(), null);
  }
  @Test public void verifiesScopedSignature() throws Exception {
    verifier.verify(3, publicKey(), privateKey(), signature());
  }
  @Test public void verifiesExactUnscopedIdentity() throws Exception {
    preferences.put(SilenceIdentityVerifier.PUBLIC, preferences.remove(SilenceIdentityVerifier.PUBLIC + "_3"));
    verifier.verify(-1, publicKey(), privateKey(), signature());
  }
  @Test public void rejectsMismatchedPairEvenWithValidSignature() throws Exception {
    byte[] signature = signature();
    assertThrows(IOException.class, () -> verifier.verify(3, publicKey(), Curve.generateKeyPair().getPrivateKey().serialize(), signature));
  }
  @Test public void rejectsSignatureFromDifferentIdentity() throws Exception {
    byte[] signature = Curve.calculateSignature(Curve.generateKeyPair().getPrivateKey(), publicKey());
    assertThrows(IOException.class, () -> verifier.verify(3, publicKey(), privateKey(), signature));
  }
  @Test public void rejectsCorruptedAndWrongLengthSignatures() throws Exception {
    byte[] signature = signature(); signature[0] ^= 1;
    assertThrows(IOException.class, () -> verifier.verify(3, publicKey(), privateKey(), signature));
    assertThrows(IOException.class, () -> verifier.verify(3, publicKey(), privateKey(), new byte[63]));
  }
  @Test public void neverFallsBackFromScopedToUnscopedIdentity() {
    preferences.put(SilenceIdentityVerifier.PUBLIC, preferences.remove(SilenceIdentityVerifier.PUBLIC + "_3"));
    assertThrows(IOException.class, () -> verifier.verify(3, publicKey(), privateKey(), null));
  }
  @Test public void neverFallsBackFromUnscopedToScopedIdentity() {
    assertThrows(IOException.class, () -> verifier.verify(-1, publicKey(), privateKey(), null));
  }
  @Test public void rejectsUnverifiedIdentitySet() {
    for (SilenceIdentityInfo info : new SilenceIdentityInfo[]{SilenceIdentityInfo.rejected(), SilenceIdentityInfo.verified(0)}) {
      SilencePreKeyVerifier rejected = new SilencePreKeyVerifier(preferences, info);
      assertThrows(IOException.class, () -> rejected.verify(3, publicKey(), privateKey(), null));
    }
  }
  @Test public void wipesOwnedInputsOnSuccessAndFailure() throws Exception {
    for (int subscription : new int[]{3, 4}) {
      byte[] pub = publicKey(), priv = privateKey(), sig = signature();
      if (subscription == 3) verifier.verify(subscription, pub, priv, sig);
      else assertThrows(IOException.class, () -> verifier.verify(subscription, pub, priv, sig));
      assertArrayEquals(new byte[pub.length], pub);
      assertArrayEquals(new byte[priv.length], priv);
      assertArrayEquals(new byte[sig.length], sig);
    }
  }
  @Test public void cancellationWipesOwnedPrivateKey() {
    byte[] pub = publicKey(), priv = privateKey();
    Thread.currentThread().interrupt();
    try { assertThrows(IOException.class, () -> verifier.verify(3, pub, priv, null)); }
    finally { Thread.interrupted(); }
    assertArrayEquals(new byte[priv.length], priv);
  }
  @Test public void preservesSourcePreferences() throws Exception {
    Map<String, String> before = new HashMap<>(preferences);
    verifier.verify(3, publicKey(), privateKey(), signature());
    assertEquals(before, preferences);
  }
  private byte[] publicKey() { return preKey.getPublicKey().serialize(); }
  private byte[] privateKey() { return preKey.getPrivateKey().serialize().clone(); }
  private byte[] signature() throws Exception { return Curve.calculateSignature(identity.getPrivateKey(), publicKey()); }
}
