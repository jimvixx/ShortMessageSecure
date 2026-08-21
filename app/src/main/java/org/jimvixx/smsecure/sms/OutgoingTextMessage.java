/*
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2013 Open Whisper Systems
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

package org.jimvixx.smsecure.sms;

import org.jimvixx.smsecure.database.model.MessageRecord;
import org.jimvixx.smsecure.recipients.Recipients;

public class OutgoingTextMessage {

  private final Recipients recipients;
  private final String message;
  private final int subscriptionId;

  public OutgoingTextMessage(Recipients recipients, String message, int subscriptionId) {
    this.recipients = recipients;
    this.message = message;
    this.subscriptionId = subscriptionId;
  }

  protected OutgoingTextMessage(OutgoingTextMessage base, String body) {
    this.recipients = base.getRecipients();
    this.subscriptionId = base.getSubscriptionId();
    this.message = body;
  }

  public static OutgoingTextMessage from(MessageRecord record) {
    if (record.isSecure()) {
      return new OutgoingEncryptedMessage(record.getRecipients(), record.getBody().getBody(), record.getSubscriptionId());
    } else if (record.isKeyExchange()) {
      return new OutgoingKeyExchangeMessage(record.getRecipients(), record.getBody().getBody(), record.getSubscriptionId());
    } else if (record.isEndSession()) {
      return new OutgoingEndSessionMessage(new OutgoingTextMessage(record.getRecipients(), record.getBody().getBody(), record.getSubscriptionId()));
    } else {
      return new OutgoingTextMessage(record.getRecipients(), record.getBody().getBody(), record.getSubscriptionId());
    }
  }

  public int getSubscriptionId() {
    return subscriptionId;
  }

  public String getMessageBody() {
    return message;
  }

  public Recipients getRecipients() {
    return recipients;
  }

  public boolean isKeyExchange() {
    return false;
  }

  public boolean isSecureMessage() {
    return false;
  }

  public boolean isEndSession() {
    return false;
  }

  public boolean isPreKeyBundle() {
    return false;
  }

  public OutgoingTextMessage withBody(String body) {
    return new OutgoingTextMessage(this, body);
  }
}
