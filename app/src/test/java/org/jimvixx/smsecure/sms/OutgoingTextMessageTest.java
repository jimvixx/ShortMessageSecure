package org.jimvixx.smsecure.sms;

import org.jimvixx.smsecure.database.model.DisplayRecord;
import org.jimvixx.smsecure.database.model.MessageRecord;
import org.jimvixx.smsecure.recipients.Recipients;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class OutgoingTextMessageTest {

  @Test
  public void fromPreservesKeyExchangeType() {
    MessageRecord record = createRecord(false, true, false);

    OutgoingTextMessage message = OutgoingTextMessage.from(record);

    assertThat(message).isInstanceOf(OutgoingKeyExchangeMessage.class);
    assertThat(message.isKeyExchange()).isTrue();
    assertThat(message.getMessageBody()).isEqualTo("serialized key exchange");
    assertThat(message.getSubscriptionId()).isEqualTo(2);
  }

  @Test
  public void fromPreservesEndSessionType() {
    MessageRecord record = createRecord(false, false, true);

    OutgoingTextMessage message = OutgoingTextMessage.from(record);

    assertThat(message).isInstanceOf(OutgoingEndSessionMessage.class);
    assertThat(message.isEndSession()).isTrue();
  }

  private MessageRecord createRecord(boolean secure, boolean keyExchange, boolean endSession) {
    MessageRecord record = mock(MessageRecord.class);
    DisplayRecord.Body body = mock(DisplayRecord.Body.class);

    when(record.isSecure()).thenReturn(secure);
    when(record.isKeyExchange()).thenReturn(keyExchange);
    when(record.isEndSession()).thenReturn(endSession);
    when(record.getRecipients()).thenReturn(mock(Recipients.class));
    when(record.getBody()).thenReturn(body);
    when(body.getBody()).thenReturn("serialized key exchange");
    when(record.getSubscriptionId()).thenReturn(2);

    return record;
  }
}
