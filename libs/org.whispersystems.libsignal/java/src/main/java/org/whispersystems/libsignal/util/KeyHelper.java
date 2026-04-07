/*
 * Copyright (C) 2014-2016 Open Whisper Systems
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

package org.whispersystems.libsignal.util;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;

import java.security.SecureRandom;
import java.util.LinkedList;
import java.util.List;

/**
 * Helper class for generating keys of different types.
 *
 */
public final class KeyHelper {

  /**
   * Single process-wide RNG instance.
   * SecureRandom instances are thread-safe for typical usage.
   */
  private static final SecureRandom RNG = new SecureRandom();

  /** Hidden constructor. */
  private KeyHelper() {}

  /**
   * Generate an identity key pair. Clients should only do this once, at install time.
   *
   * @return the generated IdentityKeyPair.
   */
  public static IdentityKeyPair generateIdentityKeyPair() {
    ECKeyPair   keyPair   = Curve.generateKeyPair();
    IdentityKey publicKey = new IdentityKey(keyPair.getPublicKey());
    return new IdentityKeyPair(publicKey, keyPair.getPrivateKey());
  }

  /**
   * Generate a registration ID. Clients should only do this once, at install time.
   *
   * @param extendedRange By default (false), the generated registration ID is sized to require
   *                      the minimal possible protobuf encoding overhead. Specify true if the caller
   *                      needs the full range of MAX_INT at the cost of slightly higher encoding overhead.
   * @return the generated registration ID.
   */
  public static int generateRegistrationId(boolean extendedRange) {
    if (extendedRange) {
      // Range: [1, Integer.MAX_VALUE - 1]
      return RNG.nextInt(Integer.MAX_VALUE - 1) + 1;
    } else {
      // Range: [1, 16380]
      return RNG.nextInt(16380) + 1;
    }
  }

  /**
   * Generate a random sequence number in range [0, max).
   *
   * @param max Upper bound (exclusive). Must be > 0.
   * @return a random int in range [0, max).
   * @throws IllegalArgumentException if max <= 0.
   */
  public static int getRandomSequence(int max) {
    if (max <= 0) {
      throw new IllegalArgumentException("max must be > 0");
    }
    return RNG.nextInt(max);
  }

  /**
   * Generate a list of PreKeys. Clients should do this at install time, and subsequently any time
   * the list of PreKeys stored on the server runs low.
   *
   * PreKey IDs are shorts, so they will eventually be repeated. Clients should store PreKeys in a
   * circular buffer, so that they are repeated as infrequently as possible.
   *
   * @param start The starting PreKey ID, inclusive.
   * @param count The number of PreKeys to generate.
   * @return the list of generated PreKeyRecords.
   */
  public static List<PreKeyRecord> generatePreKeys(int start, int count) {
    List<PreKeyRecord> results = new LinkedList<>();

    start--;

    for (int i = 0; i < count; i++) {
      results.add(new PreKeyRecord(((start + i) % (Medium.MAX_VALUE - 1)) + 1,
              Curve.generateKeyPair()));
    }

    return results;
  }

  /**
   * Generate a signed PreKey.
   *
   * @param identityKeyPair The local client's identity key pair.
   * @param signedPreKeyId  The PreKey id to assign the generated signed PreKey.
   * @return the generated signed PreKey.
   * @throws InvalidKeyException when the provided identity key is invalid.
   */
  public static SignedPreKeyRecord generateSignedPreKey(IdentityKeyPair identityKeyPair, int signedPreKeyId)
          throws InvalidKeyException
  {
    ECKeyPair keyPair   = Curve.generateKeyPair();
    byte[]    signature = Curve.calculateSignature(identityKeyPair.getPrivateKey(),
            keyPair.getPublicKey().serialize());

    return new SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(), keyPair, signature);
  }

  /**
   * Generate a sender signing key pair.
   *
   * @return an ECKeyPair suitable for sender signing.
   */
  public static ECKeyPair generateSenderSigningKey() {
    return Curve.generateKeyPair();
  }

  /**
   * Generate a 32-byte sender key.
   *
   * @return 32 random bytes.
   */
  public static byte[] generateSenderKey() {
    byte[] key = new byte[32];
    RNG.nextBytes(key);
    return key;
  }

  /**
   * Generate a sender key id.
   *
   * @return a non-negative int in range [0, Integer.MAX_VALUE).
   */
  public static int generateSenderKeyId() {
    return RNG.nextInt(Integer.MAX_VALUE);
  }
}
