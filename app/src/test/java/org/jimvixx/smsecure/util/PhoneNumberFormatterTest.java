package org.jimvixx.smsecure.util;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public class PhoneNumberFormatterTest {

  private static final String LOCAL_NUMBER = "+15555555555";

  @Test
  public void testFormatNumberE164() throws InvalidNumberException {
    assertThat(PhoneNumberFormatter.formatNumber("(555) 555-5555", LOCAL_NUMBER))
            .isEqualTo(LOCAL_NUMBER);

    assertThat(PhoneNumberFormatter.formatNumber("555-5555", LOCAL_NUMBER))
            .isEqualTo(LOCAL_NUMBER);

    assertThat(PhoneNumberFormatter.formatNumber("(123) 555-555-5555", LOCAL_NUMBER))
            .isNotEqualTo(LOCAL_NUMBER);
  }

  @Test
  public void testFormatNumberEmail() {
    try {
      PhoneNumberFormatter.formatNumber("person@domain.com", LOCAL_NUMBER);
      fail("Should have thrown InvalidNumberException");
    } catch (InvalidNumberException ine) {
      // expected
    }
  }
}
