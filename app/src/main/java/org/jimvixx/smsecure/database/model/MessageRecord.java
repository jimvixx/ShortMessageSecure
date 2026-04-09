/*
 * Copyright (C) 2012 Moxie Marlinpsike
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

package org.jimvixx.smsecure.database.model;

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.database.MessageColumns;
import org.jimvixx.smsecure.database.SmsDatabase;
import org.jimvixx.smsecure.database.documents.IdentityKeyMismatch;
import org.jimvixx.smsecure.database.documents.NetworkFailure;
import org.jimvixx.smsecure.protocol.AutoInitiate;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.SMSecurePreferences;

import java.util.List;

/**
 * The base class for message record models that are displayed in
 * conversations, as opposed to models that are displayed in a thread list.
 *
 * @author Moxie Marlinspike
 *
 */
public abstract class MessageRecord extends DisplayRecord {

  private static final int MAX_DISPLAY_LENGTH = 2000;

  private final Recipient individualRecipient;
  private final int recipientDeviceId;
  private final long id;
  private final List<IdentityKeyMismatch> mismatches;
  private final List<NetworkFailure> networkFailures;
  private final int subscriptionId;

  MessageRecord(Context context, long id, Body body, Recipients recipients,
                Recipient individualRecipient, int recipientDeviceId,
                long dateSent, long dateReceived, long threadId,
                int deliveryStatus, long dateDeliveryReceived, long type,
                List<IdentityKeyMismatch> mismatches,
                List<NetworkFailure> networkFailures,
                int subscriptionId) {
    super(context, body, recipients, dateSent, dateReceived, dateDeliveryReceived, threadId, deliveryStatus, type);
    this.id = id;
    this.individualRecipient = individualRecipient;
    this.recipientDeviceId = recipientDeviceId;
    this.mismatches = mismatches;
    this.networkFailures = networkFailures;
    this.subscriptionId = subscriptionId;
  }

  public boolean isSecure() {
    return MessageColumns.Types.isSecureType(type);
  }

  public boolean isLegacyMessage() {
    return MessageColumns.Types.isLegacyType(type);
  }

  public boolean isAsymmetricEncryption() {
    return MessageColumns.Types.isAsymmetricEncryption(type);
  }

  @Override
  public SpannableString getDisplayBody() {
    if (isGroupUpdate() && isOutgoing()) {
      return emphasisAdded(context.getString(R.string.MessageRecord_updated_group));
    } else if (isGroupQuit() && isOutgoing()) {
      return emphasisAdded(context.getString(R.string.MessageRecord_left_group));
    } else if (isGroupQuit()) {
      return emphasisAdded(context.getString(R.string.ConversationItem_group_action_left, getIndividualRecipient().toShortString()));
    } else if (isProcessedKeyExchange() || (isKeyExchange() && isOutgoing())) {
      return emphasisAdded(context.getString(R.string.MessageRecord_key_exchange_message));
    } else if (getBody().getBody().length() > MAX_DISPLAY_LENGTH) {
      return new SpannableString(getBody().getBody().substring(0, MAX_DISPLAY_LENGTH));
    }

    return new SpannableString(AutoInitiate.stripTag(getBody().getBody()));
  }

  public long getId() {
    return id;
  }

  public boolean isPush() {
    return SmsDatabase.Types.isPushType(type) && !SmsDatabase.Types.isForcedSms(type);
  }

  public long getTimestamp() {
    if (SMSecurePreferences.showSentTime(context)) return getDateSent();
    else return getDateReceived();
  }

  public boolean isStaleKeyExchange() {
    return SmsDatabase.Types.isStaleKeyExchange(type);
  }

  public boolean isProcessedKeyExchange() {
    return SmsDatabase.Types.isProcessedKeyExchange(type);
  }

  public boolean isIdentityMismatchFailure() {
    return mismatches != null && !mismatches.isEmpty();
  }

  public boolean isBundleKeyExchange() {
    return SmsDatabase.Types.isBundleKeyExchange(type);
  }

  public boolean isIdentityUpdate() {
    return SmsDatabase.Types.isIdentityUpdate(type);
  }

  public boolean isCorruptedKeyExchange() {
    return SmsDatabase.Types.isCorruptedKeyExchange(type);
  }

  public boolean isInvalidVersionKeyExchange() {
    return SmsDatabase.Types.isInvalidVersionKeyExchange(type);
  }

  public Recipient getIndividualRecipient() {
    return individualRecipient;
  }

  public int getRecipientDeviceId() {
    return recipientDeviceId;
  }

  public long getType() {
    return type;
  }

  public List<IdentityKeyMismatch> getIdentityKeyMismatches() {
    return mismatches;
  }

  public List<NetworkFailure> getNetworkFailures() {
    return networkFailures;
  }

  public boolean hasNetworkFailures() {
    return networkFailures != null && !networkFailures.isEmpty();
  }

  protected SpannableString emphasisAdded(String sequence) {
    SpannableString spannable = new SpannableString(sequence);
    spannable.setSpan(new RelativeSizeSpan(0.9f), 0, sequence.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    spannable.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), 0, sequence.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

    return spannable;
  }

  public boolean equals(Object other) {
    return other instanceof MessageRecord &&
            ((MessageRecord) other).getId() == getId();
  }

  public int hashCode() {
    return (int) getId();
  }

  public int getSubscriptionId() {
    return subscriptionId;
  }
}
