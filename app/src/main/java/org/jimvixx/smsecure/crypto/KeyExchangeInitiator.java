/*
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2013 Open Whisper Systems
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

package org.jimvixx.smsecure.crypto;

import android.app.AlertDialog;
import android.content.Context;
import android.telephony.SubscriptionInfo;
import android.widget.Toast;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.crypto.storage.SMSecureIdentityKeyStore;
import org.jimvixx.smsecure.crypto.storage.SMSecurePreKeyStore;
import org.jimvixx.smsecure.crypto.storage.SMSecureSessionStore;
import org.jimvixx.smsecure.protocol.KeyExchangeMessage;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.sms.MessageSender;
import org.jimvixx.smsecure.sms.OutgoingEndSessionMessage;
import org.jimvixx.smsecure.sms.OutgoingKeyExchangeMessage;
import org.jimvixx.smsecure.sms.OutgoingTextMessage;
import org.jimvixx.smsecure.util.Base64;
import org.jimvixx.smsecure.util.TelephonyUtil;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.IdentityKeyStore;
import org.whispersystems.libsignal.state.PreKeyStore;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionStore;
import org.whispersystems.libsignal.state.SignedPreKeyStore;

import java.util.List;

public class KeyExchangeInitiator {

  public static void abort(final Context context, final MasterSecret masterSecret, final Recipients recipients, final int subscriptionId) {
    OutgoingEndSessionMessage endSessionMessage = new OutgoingEndSessionMessage(new OutgoingTextMessage(recipients, "TERMINATE", subscriptionId));
    MessageSender.send(context, masterSecret, endSessionMessage, -1, false);
  }

  public static void initiate(final Context context, final MasterSecret masterSecret, final Recipients recipients, boolean promptOnExisting) {
    List<SubscriptionInfo> listSubscriptionInfo = TelephonyUtil.getActiveSubscriptionInfoListSafe(context);
    for (SubscriptionInfo subscriptionInfo : listSubscriptionInfo) {
      initiate(context, masterSecret, recipients, promptOnExisting, subscriptionInfo.getSubscriptionId());
    }
  }

  public static void initiate(final Context context, final MasterSecret masterSecret, final Recipients recipients, boolean promptOnExisting, final int subscriptionId) {
    if (promptOnExisting && hasInitiatedSession(context, masterSecret, recipients, subscriptionId)) {
      AlertDialog.Builder dialog = new AlertDialog.Builder(context);
      dialog.setTitle(R.string.KeyExchangeInitiator_initiate_despite_existing_request_question);
      dialog.setMessage(R.string.KeyExchangeInitiator_youve_already_sent_a_session_initiation_request_to_this_recipient_are_you_sure);
      dialog.setIconAttribute(R.attr.dialog_alert_icon);
      dialog.setCancelable(true);
      dialog.setPositiveButton(R.string.Send, (dialog1, which) -> initiateKeyExchange(context, masterSecret, recipients, subscriptionId));
      dialog.setNegativeButton(android.R.string.cancel, null);
      dialog.show();
    } else {
      initiateKeyExchange(context, masterSecret, recipients, subscriptionId);
    }
  }

  public static void initiateKeyExchange(Context context, MasterSecret masterSecret, Recipients recipients, int subscriptionId) {
    Recipient recipient = recipients.getPrimaryRecipient();
    SessionStore sessionStore = new SMSecureSessionStore(context, masterSecret, subscriptionId);
    PreKeyStore preKeyStore = new SMSecurePreKeyStore(context, masterSecret, subscriptionId);
    SignedPreKeyStore signedPreKeyStore = new SMSecurePreKeyStore(context, masterSecret, subscriptionId);
    IdentityKeyStore identityKeyStore = new SMSecureIdentityKeyStore(context, masterSecret, subscriptionId);

    SessionBuilder sessionBuilder = new SessionBuilder(sessionStore, preKeyStore, signedPreKeyStore,
            identityKeyStore, new SignalProtocolAddress(recipient.getNumber(), 1));

    if (identityKeyStore.getIdentityKeyPair() != null) {
      KeyExchangeMessage keyExchangeMessage = sessionBuilder.process();
      String serializedMessage = Base64.encodeBytesWithoutPadding(keyExchangeMessage.serialize());
      OutgoingKeyExchangeMessage textMessage = new OutgoingKeyExchangeMessage(recipients, serializedMessage, subscriptionId);

      MessageSender.send(context, masterSecret, textMessage, -1, false);
    } else {
      Toast.makeText(context, R.string.IdentityActivity__you_do_not_have_an_identity_key,
              Toast.LENGTH_LONG).show();
    }
  }

  private static boolean hasInitiatedSession(Context context, MasterSecret masterSecret,
                                             Recipients recipients, int subscriptionId) {
    Recipient recipient = recipients.getPrimaryRecipient();
    SessionStore sessionStore = new SMSecureSessionStore(context, masterSecret, subscriptionId);
    SessionRecord sessionRecord = sessionStore.loadSession(new SignalProtocolAddress(recipient.getNumber(), 1));

    return sessionRecord.getSessionState().hasPendingKeyExchange();
  }
}
