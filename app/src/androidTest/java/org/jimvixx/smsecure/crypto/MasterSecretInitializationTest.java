/*
 * Copyright (C) 2026 Jimvixx
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

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class MasterSecretInitializationTest {
  private Context context;
  private SharedPreferences preferences;

  @Before
  public void setUp() {
    Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
    preferences = base.getSharedPreferences("master-secret-test-" + UUID.randomUUID(), 0);
    context = new ContextWrapper(base) {
      @Override
      public SharedPreferences getSharedPreferences(String name, int mode) {
        return preferences;
      }
    };
  }

  @After
  public void tearDown() {
    assertTrue(preferences.edit().clear().commit());
  }

  @Test
  public void repeatedInitializationPreservesKeysAndMessages() throws Exception {
    MasterSecret original = MasterSecretUtil.generateMasterSecret(context, "unencrypted");
    assertNotNull(original);
    MasterSecretUtil.generateAsymmetricMasterSecret(context, original);
    String body = new MasterCipher(original).encryptBody("synthetic message");
    Map<String, ?> before = preferences.getAll();
    assertNull(MasterSecretUtil.generateMasterSecret(context, "unencrypted"));
    assertEquals(before, preferences.getAll());
    assertEquals("synthetic message", new MasterCipher(
            MasterSecretUtil.getMasterSecret(context, "unencrypted")).decryptBody(body));
  }

  @Test
  public void passwordChangeStillPreservesMessages() throws Exception {
    MasterSecret original = MasterSecretUtil.generateMasterSecret(context, "first password");
    assertNotNull(original);
    String body = new MasterCipher(original).encryptBody("protected message");
    Map<String, ?> before = preferences.getAll();
    assertNull(MasterSecretUtil.generateMasterSecret(context, "unencrypted"));
    assertEquals(before, preferences.getAll());
    MasterSecretUtil.changeMasterSecretPassphrase(context, "first password", "second password");
    assertEquals("protected message", new MasterCipher(
            MasterSecretUtil.getMasterSecret(context, "second password")).decryptBody(body));
  }

  @Test
  public void missingInitializedFlagDoesNotPermitReplacement() {
    assertNotNull(MasterSecretUtil.generateMasterSecret(context, "unencrypted"));
    assertTrue(preferences.edit().remove("passphrase_initialized").commit());
    Map<String, ?> before = preferences.getAll();
    assertNull(MasterSecretUtil.generateMasterSecret(context, "unencrypted"));
    assertEquals(before, preferences.getAll());
  }

  @Test
  public void partialKeyMaterialIsNeverOverwritten() {
    String[] keys = {"master_secret", "encryption_salt", "mac_salt",
            "asymmetric_master_secret_curve25519_public",
            "asymmetric_master_secret_curve25519_private"};
    for (String key : keys) {
      assertTrue(preferences.edit().clear().putString(key, "invalid").commit());
      Map<String, ?> before = preferences.getAll();
      assertNull(MasterSecretUtil.generateMasterSecret(context, "unencrypted"));
      assertEquals(before, preferences.getAll());
    }
    assertTrue(preferences.edit().clear().putBoolean("passphrase_initialized", true).commit());
    assertNull(MasterSecretUtil.generateMasterSecret(context, "unencrypted"));
    assertTrue(preferences.edit().clear().putInt("passphrase_iterations", 100).commit());
    assertNull(MasterSecretUtil.generateMasterSecret(context, "unencrypted"));
  }

  @Test
  public void restoredPreferencesRemainReadableAndUnchanged() throws Exception {
    MasterSecret original = MasterSecretUtil.generateMasterSecret(context, "backup password");
    assertNotNull(original);
    String body = new MasterCipher(original).encryptBody("restored message");
    Map<String, ?> backup = preferences.getAll();
    assertTrue(preferences.edit().clear().commit());
    SharedPreferences.Editor restore = preferences.edit();
    for (Map.Entry<String, ?> entry : backup.entrySet()) {
      Object value = entry.getValue();
      if (value instanceof String) restore.putString(entry.getKey(), (String) value);
      else if (value instanceof Integer) restore.putInt(entry.getKey(), (Integer) value);
      else if (value instanceof Boolean) restore.putBoolean(entry.getKey(), (Boolean) value);
      else fail("Unexpected key preference type");
    }
    assertTrue(restore.commit());
    assertNull(MasterSecretUtil.generateMasterSecret(context, "unencrypted"));
    assertEquals(backup, preferences.getAll());
    assertEquals("restored message", new MasterCipher(
            MasterSecretUtil.getMasterSecret(context, "backup password")).decryptBody(body));
  }

  @Test
  public void concurrentInitializationCreatesOnlyOneKey() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<MasterSecret> first = executor.submit(() -> {
        start.await();
        return MasterSecretUtil.generateMasterSecret(context, "unencrypted");
      });
      Future<MasterSecret> second = executor.submit(() -> {
        start.await();
        return MasterSecretUtil.generateMasterSecret(context, "unencrypted");
      });
      start.countDown();
      MasterSecret a = first.get(30, TimeUnit.SECONDS);
      MasterSecret b = second.get(30, TimeUnit.SECONDS);
      assertTrue((a == null) != (b == null));
      MasterSecret winner = a != null ? a : b;
      assertArrayEquals(winner.getEncryptionKey().getEncoded(),
              MasterSecretUtil.getMasterSecret(context, "unencrypted").getEncryptionKey().getEncoded());
    } finally {
      executor.shutdownNow();
    }
  }
}
