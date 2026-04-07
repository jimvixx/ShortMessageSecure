package org.jimvixx.smsecure;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.junit.Before;

import javax.crypto.spec.SecretKeySpec;

public abstract class BaseUnitTest {

  protected MasterSecret masterSecret;

  @Before
  public void setUp() throws Exception {
    masterSecret = new MasterSecret(
            new SecretKeySpec(new byte[16], "AES"),
            new SecretKeySpec(new byte[16], "HmacSHA1")
    );
  }
}
