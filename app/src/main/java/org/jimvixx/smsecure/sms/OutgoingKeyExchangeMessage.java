package org.jimvixx.smsecure.sms;

import org.jimvixx.smsecure.recipients.Recipients;

public class OutgoingKeyExchangeMessage extends OutgoingTextMessage {

  public OutgoingKeyExchangeMessage(Recipients recipients, String message, int subscriptionId) {
    super(recipients, message, subscriptionId);
  }

  private OutgoingKeyExchangeMessage(OutgoingKeyExchangeMessage base, String body) {
    super(base, body);
  }

  @Override
  public boolean isKeyExchange() {
    return true;
  }

  @Override
  public OutgoingTextMessage withBody(String body) {
    return new OutgoingKeyExchangeMessage(this, body);
  }
}
