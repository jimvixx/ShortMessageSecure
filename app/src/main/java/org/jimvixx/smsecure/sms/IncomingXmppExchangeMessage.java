package org.jimvixx.smsecure.sms;

public class IncomingXmppExchangeMessage extends IncomingTextMessage {

  private boolean isDuplicate;

  public IncomingXmppExchangeMessage(IncomingTextMessage base, String newBody) {
    super(base, newBody);
  }

  @Override
  public IncomingTextMessage withMessageBody(String messageBody) {
    return new IncomingXmppExchangeMessage(this, messageBody);
  }

  public boolean isDuplicate() {
    return isDuplicate;
  }

  public void setDuplicate(boolean isDuplicate) {
    this.isDuplicate = isDuplicate;
  }

  @Override
  public boolean isXmppExchange() {
    return true;
  }
}
