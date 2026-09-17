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

import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.whispersystems.libsignal.state.StorageProtos;

/** Checks snapshot session invariants without advancing ratchets or consulting live protocol stores. */
final class SilenceSessionVerifier {
  private final Map<String, String> preferences;
  private final SilenceIdentityInfo identities;
  SilenceSessionVerifier(Map<String, String> preferences, SilenceIdentityInfo identities) {
    this.preferences = preferences;
    this.identities = identities;
  }

  void verify(byte[] plaintext, int version, String filename) throws IOException {
    checkCancelled();
    int subscription = SilenceSourceBinding.session(filename).subscriptionId;
    if (identities.getStatus() != SilenceIdentityInfo.Status.VERIFIED)
      throw new IOException("Sessions require verified source identities");
    String suffix = subscription == -1 ? "" : "_" + subscription;
    String typed = preferences.get(SilenceIdentityVerifier.PUBLIC + suffix);
    if (typed == null || !typed.startsWith("string:")) throw new IOException("Missing source session identity");
    byte[] identity = SilenceLegacyCipher.decode(typed.substring(7));
    try {
      ByteString expected = ByteString.copyFrom(identity);
      publicKey(expected);
      if (version == 1) {
        if (!state(StorageProtos.SessionStructure.parseFrom(plaintext), expected)) throw new IOException("Empty session");
      } else if (version == 2) {
        StorageProtos.RecordStructure record = StorageProtos.RecordStructure.parseFrom(plaintext);
        if (record.getPreviousSessionsCount() > 40) throw new IOException("Too many archived sessions");
        boolean meaningful = record.hasCurrentSession() && state(record.getCurrentSession(), expected);
        for (StorageProtos.SessionStructure previous : record.getPreviousSessionsList())
          meaningful = state(previous, expected) || meaningful;
        if (!meaningful) throw new IOException("Empty session record");
      } else throw new IOException("Unsupported session envelope");
    } finally { Arrays.fill(identity, (byte) 0); }
  }

  private boolean state(StorageProtos.SessionStructure state, ByteString identity) throws IOException {
    checkCancelled();
    if (state.getSerializedSize() == 0) return false;
    int version = state.hasSessionVersion() ? state.getSessionVersion() : 2;
    if (version != 2 && version != 3) throw new IOException("Unsupported session protocol");
    if (state.hasLocalIdentityPublic()) equalIdentity(state.getLocalIdentityPublic(), identity);
    if (state.hasRemoteIdentityPublic()) publicKey(state.getRemoteIdentityPublic());
    if (state.hasAliceBaseKey()) publicKey(state.getAliceBaseKey());
    if (state.hasPreviousCounter() && state.getPreviousCounter() < 0) throw new IOException("Unsupported session counter");
    if (state.hasSenderChain()) {
      equalIdentity(state.getLocalIdentityPublic(), identity);
      publicKey(state.getRemoteIdentityPublic());
      length(state.getRootKey(), 32);
      chain(state.getSenderChain(), true);
    } else if (state.hasRootKey() || state.getReceiverChainsCount() != 0 || state.hasPendingPreKey()) {
      throw new IOException("Incomplete active session");
    }
    if (state.getReceiverChainsCount() > 5) throw new IOException("Too many receiver chains");
    Set<ByteString> ratchets = new HashSet<>();
    for (StorageProtos.SessionStructure.Chain chain : state.getReceiverChainsList()) {
      if (!ratchets.add(chain.getSenderRatchetKey())) throw new IOException("Duplicate receiver ratchet");
      chain(chain, false);
    }
    if (state.hasPendingKeyExchange()) {
      StorageProtos.SessionStructure.PendingKeyExchange pending = state.getPendingKeyExchange();
      if (!pending.hasSequence() || pending.getSequence() < 0) throw new IOException("Invalid exchange sequence");
      equalIdentity(pending.getLocalIdentityKey(), identity);
      pair(pending.getLocalIdentityKey(), pending.getLocalIdentityKeyPrivate());
      pair(pending.getLocalBaseKey(), pending.getLocalBaseKeyPrivate());
      pair(pending.getLocalRatchetKey(), pending.getLocalRatchetKeyPrivate());
    }
    if (state.hasPendingPreKey()) {
      StorageProtos.SessionStructure.PendingPreKey pending = state.getPendingPreKey();
      publicKey(pending.getBaseKey());
      if (!pending.hasSignedPreKeyId() || pending.getSignedPreKeyId() < -1 ||
          (pending.hasPreKeyId() && pending.getPreKeyId() < 0)) throw new IOException("Invalid pending prekey");
    }
    if (!state.hasSenderChain() && !state.hasPendingKeyExchange()) throw new IOException("Incomplete session state");
    return true;
  }

  private void chain(StorageProtos.SessionStructure.Chain chain, boolean sender) throws IOException {
    checkCancelled();
    publicKey(chain.getSenderRatchetKey());
    if (!chain.hasChainKey() || !chain.getChainKey().hasIndex() || chain.getChainKey().getIndex() < 0)
      throw new IOException("Invalid chain index");
    length(chain.getChainKey().getKey(), 32);
    if (sender) {
      pair(chain.getSenderRatchetKey(), chain.getSenderRatchetKeyPrivate());
      if (chain.getMessageKeysCount() != 0) throw new IOException("Unexpected sender message keys");
    } else if (chain.hasSenderRatchetKeyPrivate()) throw new IOException("Unexpected receiver private key");
    if (chain.getMessageKeysCount() > 2000) throw new IOException("Too many cached message keys");
    Set<Integer> indices = new HashSet<>();
    for (StorageProtos.SessionStructure.Chain.MessageKey key : chain.getMessageKeysList()) {
      checkCancelled();
      if (!key.hasIndex() || key.getIndex() < 0 || !indices.add(key.getIndex()))
        throw new IOException("Invalid cached message index");
      length(key.getCipherKey(), 32); length(key.getMacKey(), 32); length(key.getIv(), 16);
    }
  }

  private static void pair(ByteString publicKey, ByteString privateKey) throws IOException {
    byte[] pub = publicKey.toByteArray(), priv = privateKey.toByteArray();
    try { SilenceKeyPairVerifier.verify(pub, priv); }
    finally { Arrays.fill(pub, (byte) 0); Arrays.fill(priv, (byte) 0); }
  }
  private static void equalIdentity(ByteString actual, ByteString expected) throws IOException {
    publicKey(actual);
    if (!actual.equals(expected)) throw new IOException("Session identity differs from source slot");
  }
  private static void publicKey(ByteString key) throws IOException {
    if (key.size() != 33 || key.byteAt(0) != 5) throw new IOException("Invalid session public key");
  }
  private static void length(ByteString value, int expected) throws IOException {
    if (value.size() != expected) throw new IOException("Invalid session key length");
  }
  private static void checkCancelled() throws IOException {
    if (Thread.currentThread().isInterrupted()) throw new IOException("Session verification cancelled");
  }
}
