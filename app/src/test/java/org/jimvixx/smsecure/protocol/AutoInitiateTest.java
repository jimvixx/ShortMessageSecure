package org.jimvixx.smsecure.protocol;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AutoInitiateTest {

  @Test
  public void whitespaceTagContainsThirteenSpaces() {
    assertThat(AutoInitiate.WHITESPACE_TAG).isEqualTo("             ");
    assertThat(AutoInitiate.WHITESPACE_TAG).hasSize(13);
    assertThat(AutoInitiate.WHITESPACE_TAG).doesNotContain("{13}");
  }

  @Test
  public void taggedMessageIsInvisibleAndRoundTrips() {
    String taggedMessage = AutoInitiate.getTaggedMessage("Test");

    assertThat(taggedMessage).isEqualTo("Test" + AutoInitiate.WHITESPACE_TAG);
    assertThat(AutoInitiate.isTagged(taggedMessage)).isTrue();
    assertThat(AutoInitiate.stripTag(taggedMessage)).isEqualTo("Test");
  }
}
