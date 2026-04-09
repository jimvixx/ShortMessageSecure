/*
 * Copyright (C) 2015 Whisper Systems
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

package org.jimvixx.smsecure.crypto.storage;

import android.content.Context;

import org.jimvixx.smsecure.crypto.IdentityKeyUtil;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.IdentityKeyStore;

public class SMSecureIdentityKeyStore implements IdentityKeyStore {

  private static final Object LOCK = new Object();

  private final Context context;
  private final MasterSecret masterSecret;
  private final int subscriptionId;

  public SMSecureIdentityKeyStore(Context context, MasterSecret masterSecret, int subscriptionId) {
    this.context = context;
    this.masterSecret = masterSecret;
    this.subscriptionId = subscriptionId;
  }

  @Override
  public IdentityKeyPair getIdentityKeyPair() {
    return IdentityKeyUtil.getIdentityKeyPair(context, masterSecret, subscriptionId);
  }

  @Override
  public int getLocalRegistrationId() {
    return SMSecurePreferences.getLocalRegistrationId(context);
  }

  @Override
  public boolean saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
    synchronized (LOCK) {
      long recipientId = RecipientFactory.getRecipientsFromString(context, address.getName(), true).getPrimaryRecipient().getRecipientId();
      DatabaseFactory.getIdentityDatabase(context).saveIdentity(masterSecret, recipientId, identityKey);
      return true;
    }
  }

  @Override
  public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey, Direction direction) {
    synchronized (LOCK) {
      return switch (direction) {
        case SENDING -> isTrustedIdentity(address, identityKey);
        case RECEIVING -> true;
        default -> throw new AssertionError("Unknown direction: " + direction);
      };
    }
  }

  public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
    long recipientId = RecipientFactory.getRecipientsFromString(context, address.getName(), true).getPrimaryRecipient().getRecipientId();
    return DatabaseFactory.getIdentityDatabase(context)
            .isValidIdentity(masterSecret, recipientId, identityKey);
  }

  @Override
  public IdentityKey getIdentity(SignalProtocolAddress address) {
    return null;
  }
}
