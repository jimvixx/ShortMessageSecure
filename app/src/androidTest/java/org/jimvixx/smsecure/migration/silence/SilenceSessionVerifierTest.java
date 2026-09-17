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
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.*;
import org.jimvixx.smsecure.util.Base64;
import org.junit.*;
import org.junit.runner.RunWith;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.state.StorageProtos.RecordStructure;
import org.whispersystems.libsignal.state.StorageProtos.SessionStructure;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SilenceSessionVerifierTest {
  private ECKeyPair identity;
  private SilenceSessionVerifier verifier;
  private SessionStructure active;
  private Map<String, String> preferences;
  @Before public void setup() {
    identity = Curve.generateKeyPair(); active = SilenceSessionTestData.active(identity);
    preferences = new HashMap<>();
    String value = "string:" + Base64.encodeBytes(identity.getPublicKey().serialize());
    preferences.put(SilenceIdentityVerifier.PUBLIC + "_3", value);
    preferences.put(SilenceIdentityVerifier.PUBLIC, value);
    verifier = new SilenceSessionVerifier(preferences, SilenceIdentityInfo.verified(2));
  }
  @Test public void acceptsSupportedActiveProtocolVersions() throws Exception {
    verify(active); verify(active.toBuilder().setSessionVersion(2).build());
    verify(active.toBuilder().clearSessionVersion().build());
  }
  @Test public void acceptsValidPendingExchangeWithoutActiveSession() throws Exception {
    verify(SilenceSessionTestData.pending(identity));
  }
  @Test public void acceptsEmptyCurrentStateWithValidArchive() throws Exception {
    verifier.verify(RecordStructure.newBuilder().setCurrentSession(SessionStructure.getDefaultInstance())
        .addPreviousSessions(active).build().toByteArray(), 2, "1.3");
  }
  @Test public void rejectsEntirelyEmptyRecord() {
    assertThrows(IOException.class, () -> verifier.verify(RecordStructure.newBuilder()
        .setCurrentSession(SessionStructure.getDefaultInstance()).build().toByteArray(), 2, "1.3"));
  }
  @Test public void rejectsUnsupportedProtocolAndEnvelopeVersions() {
    rejected(active.toBuilder().setSessionVersion(4).build());
    assertThrows(IOException.class, () -> verifier.verify(active.toByteArray(), 3, "1.3"));
  }
  @Test public void rejectsWrongOrMissingSourceIdentity() {
    rejected(active.toBuilder().setLocalIdentityPublic(SilenceSessionTestData.bytes(Curve.generateKeyPair().getPublicKey().serialize())).build());
    assertThrows(IOException.class, () -> verifier.verify(active.toByteArray(), 1, "1.9"));
    SilenceSessionVerifier absent = new SilenceSessionVerifier(preferences, SilenceIdentityInfo.verified(0));
    assertThrows(IOException.class, () -> absent.verify(active.toByteArray(), 1, "1.3"));
  }
  @Test public void rejectsBadRootAndRemoteIdentityShapes() {
    rejected(active.toBuilder().setRootKey(ByteString.copyFrom(new byte[31])).build());
    rejected(active.toBuilder().setRemoteIdentityPublic(ByteString.copyFrom(new byte[33])).build());
  }
  @Test public void rejectsMismatchedSenderKeyPair() {
    rejected(active.toBuilder().setSenderChain(active.getSenderChain().toBuilder()
        .setSenderRatchetKeyPrivate(SilenceSessionTestData.bytes(Curve.generateKeyPair().getPrivateKey().serialize()))).build());
  }
  @Test public void rejectsMissingChainIndexAndInvalidKeyLength() {
    rejected(active.toBuilder().setSenderChain(active.getSenderChain().toBuilder()
        .setChainKey(active.getSenderChain().getChainKey().toBuilder().clearIndex())).build());
    rejected(active.toBuilder().setSenderChain(active.getSenderChain().toBuilder()
        .setChainKey(active.getSenderChain().getChainKey().toBuilder().setKey(ByteString.EMPTY))).build());
  }
  @Test public void rejectsDuplicateAndExcessReceiverChains() {
    SessionStructure.Chain receiver = SilenceSessionTestData.receiver();
    rejected(active.toBuilder().addReceiverChains(receiver).addReceiverChains(receiver).build());
    SessionStructure.Builder large = active.toBuilder();
    for (int i = 0; i < 6; i++) large.addReceiverChains(SilenceSessionTestData.receiver());
    rejected(large.build());
  }
  @Test public void acceptsValidCachedMessageKeys() throws Exception {
    verify(active.toBuilder().addReceiverChains(SilenceSessionTestData.receiver().toBuilder().addMessageKeys(message())).build());
  }
  @Test public void rejectsBadAndDuplicateCachedMessageKeys() {
    SessionStructure.Chain receiver = SilenceSessionTestData.receiver();
    rejected(active.toBuilder().addReceiverChains(receiver.toBuilder().addMessageKeys(message().toBuilder().clearIv())).build());
    rejected(active.toBuilder().addReceiverChains(receiver.toBuilder().addMessageKeys(message()).addMessageKeys(message())).build());
  }
  @Test public void rejectsPrivateReceiverKeyAndSenderMessageCache() {
    rejected(active.toBuilder().addReceiverChains(SilenceSessionTestData.receiver().toBuilder()
        .setSenderRatchetKeyPrivate(ByteString.copyFrom(new byte[32]))).build());
    rejected(active.toBuilder().setSenderChain(active.getSenderChain().toBuilder().addMessageKeys(message())).build());
  }
  @Test public void rejectsMismatchedPendingExchangePairs() {
    SessionStructure pending = SilenceSessionTestData.pending(identity);
    rejected(pending.toBuilder().setPendingKeyExchange(pending.getPendingKeyExchange().toBuilder()
        .setLocalBaseKeyPrivate(SilenceSessionTestData.bytes(Curve.generateKeyPair().getPrivateKey().serialize()))).build());
  }
  @Test public void rejectsPartialActiveStateAndMalformedPendingPreKey() {
    rejected(active.toBuilder().clearSenderChain().build());
    rejected(active.toBuilder().setPendingPreKey(SessionStructure.PendingPreKey.newBuilder().setSignedPreKeyId(1)).build());
  }
  @Test public void boundsArchiveAndCachedMessageCounts() {
    RecordStructure.Builder record = RecordStructure.newBuilder().setCurrentSession(active);
    for (int i = 0; i < 41; i++) record.addPreviousSessions(active);
    assertThrows(IOException.class, () -> verifier.verify(record.build().toByteArray(), 2, "1.3"));
    SessionStructure.Chain.Builder receiver = SilenceSessionTestData.receiver().toBuilder();
    for (int i = 0; i < 2001; i++) receiver.addMessageKeys(message().toBuilder().setIndex(i));
    rejected(active.toBuilder().addReceiverChains(receiver).build());
  }
  @Test public void cancellationPropagatesAndInputsRemainUnchanged() throws Exception {
    byte[] bytes = active.toByteArray(), before = bytes.clone();
    verifier.verify(bytes, 1, "1.3"); assertArrayEquals(before, bytes);
    Thread.currentThread().interrupt();
    try { assertThrows(IOException.class, () -> verifier.verify(bytes, 1, "1.3")); }
    finally { Thread.interrupted(); }
  }
  private void verify(SessionStructure state) throws IOException { verifier.verify(state.toByteArray(), 1, "1.3"); }
  private void rejected(SessionStructure state) { assertThrows(IOException.class, () -> verify(state)); }
  private static SessionStructure.Chain.MessageKey message() {
    return SessionStructure.Chain.MessageKey.newBuilder().setIndex(0).setCipherKey(ByteString.copyFrom(new byte[32]))
        .setMacKey(ByteString.copyFrom(new byte[32])).setIv(ByteString.copyFrom(new byte[16])).build();
  }
}
