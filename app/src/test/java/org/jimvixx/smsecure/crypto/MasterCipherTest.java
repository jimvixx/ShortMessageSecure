package org.jimvixx.smsecure.crypto;

import org.jimvixx.smsecure.BaseUnitTest;
import org.junit.Before;
import org.junit.Test;
import org.whispersystems.libsignal.InvalidMessageException;

import static org.junit.Assert.fail;

public class MasterCipherTest extends BaseUnitTest {

  private MasterCipher masterCipher;

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    masterCipher = new MasterCipher(masterSecret);
  }

  @Test
  public void testEncryptBytesWithZeroBody() throws Exception {
    try {
      masterCipher.decryptBytes(new byte[]{});
      fail("Expected InvalidMessageException to be thrown");
    } catch (InvalidMessageException expected) {
      // success
    }
  }
}
