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

package org.jimvixx.smsecure.migration.silence;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SilenceMigrationPlannerTest {
  private SilenceTestBackup fixture;
  @Before public void setup() throws Exception { fixture = new SilenceTestBackup(); }
  @After public void cleanup() throws Exception { if (fixture != null) fixture.close(); }
  @Test public void collectsIndependentSourceEvidenceWithoutRetainingPhoneNumbers() throws Exception {
    append(SilenceBackupDetector.SECRET_PREFS, "<string name='pref_identity_public_curve25519_5'>synthetic</string>");
    append(SilenceBackupDetector.DEFAULT_PREFS, "<string name='number_for_app_subscription_id_10'>synthetic-number</string>"
        + "<boolean name='pref_show_sent_time' value='true'/>");
    File session = new File(fixture.input, "files/sessions-v2/1.9"); assertTrue(session.getParentFile().mkdirs());
    try (OutputStream out = new FileOutputStream(session)) { out.write(1); }
    fixture.mutate("UPDATE sms SET subscription_id = 7", "INSERT INTO recipient_preferences (_id, recipient_ids, default_subscription_id) VALUES (1, '1', 8)");
    SilenceMigrationPlan plan = plan();
    assertEquals(5, plan.getSubscriptions().getSources().size());
    assertTrue(plan.getSubscriptions().getSources().get(5).contains(SilenceSubscriptionPlan.Origin.IDENTITY));
    assertTrue(plan.getSubscriptions().getSources().get(7).contains(SilenceSubscriptionPlan.Origin.SMS));
    assertTrue(plan.getSubscriptions().getSources().get(8).contains(SilenceSubscriptionPlan.Origin.RECIPIENT_DEFAULT));
    assertTrue(plan.getSubscriptions().getSources().get(9).contains(SilenceSubscriptionPlan.Origin.SESSION));
    assertTrue(plan.getSubscriptions().getSources().get(10).contains(SilenceSubscriptionPlan.Origin.SOURCE_METADATA));
    assertEquals(1, plan.getPreferences().getCandidates().size());
    assertTrue(plan.getSubscriptions().getAssignments().isEmpty()); assertFalse(plan.isCryptoInventoryChecked());
  }
  @Test public void unknownSmsSimIsNotAnImplicitCryptoSlot() throws Exception {
    fixture.mutate("INSERT INTO sms (_id, thread_id, subscription_id) VALUES (2, 1, NULL)");
    SilenceMigrationPlan plan = plan();
    assertEquals(2, plan.getSubscriptions().getSmsWithoutSubscription());
    assertTrue(plan.getSubscriptions().getSources().isEmpty());
  }
  @Test public void unscopedIdentityNeedsAnExplicitMapping() throws Exception {
    append(SilenceBackupDetector.SECRET_PREFS, "<string name='pref_identity_public_curve25519'>synthetic</string>");
    assertTrue(plan().getSubscriptions().getSources().containsKey(-1));
    assertFalse(plan().getSubscriptions().hasCompleteAssignments());
  }
  @Test public void combinesOriginsForTheSameSourceSlot() throws Exception {
    append(SilenceBackupDetector.SECRET_PREFS, "<string name='pref_identity_public_curve25519_3'>synthetic</string>");
    fixture.mutate("UPDATE sms SET subscription_id = 3");
    assertEquals(2, plan().getSubscriptions().getSources().get(3).size());
  }
  @Test public void rejectsInvalidDatabaseSubscriptionIdentifiers() throws Exception {
    for (String value : new String[]{"-2", "2147483648", "'unsupported'", "1.5"}) {
      fixture.mutate("UPDATE sms SET subscription_id = " + value);
      assertThrows(IOException.class, this::plan);
    }
  }
  @Test public void rejectsMalformedMetadataSlotNames() throws Exception {
    append(SilenceBackupDetector.DEFAULT_PREFS, "<string name='icc_id_for_app_subscription_id_01'>synthetic</string>");
    assertThrows(IOException.class, this::plan);
  }
  @Test public void cancellationPropagates() throws Exception {
    try (SilenceBackupStager.Snapshot snapshot = new SilenceBackupStager().stage(fixture.source(), fixture.output)) {
      Thread.currentThread().interrupt();
      try { assertThrows(IOException.class, () -> new SilenceMigrationPlanner().plan(snapshot, null)); }
      finally { Thread.interrupted(); }
    }
  }
  @Test public void coordinatorAttachesDraftWithoutReadinessAndCleansSnapshot() throws Exception {
    fixture.mutate("UPDATE sms SET type = 20, body = 'synthetic plaintext'");
    assertTrue(new File(fixture.input, "files/signed_prekeys/1").delete());
    try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("silence/crypto-disabled.xml");
         OutputStream out = new FileOutputStream(new File(fixture.input, SilenceBackupDetector.SECRET_PREFS))) {
      byte[] buffer = new byte[4096]; int length;
      while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
    }
    SilencePreflightResult result = SilenceImportCoordinator.inspect(fixture.source(), fixture.output);
    assertNotNull(result.getMigrationPlan()); assertTrue(result.getMigrationPlan().isCryptoInventoryChecked());
    assertTrue(result.getMigrationPlan().getSubscriptions().hasCompleteAssignments());
    assertFalse(result.isReadyToImport()); assertEquals(0, new File(fixture.output, "silence-preflight").list().length);
  }
  @Test public void preservesSourceDatabaseAndPreferenceFiles() throws Exception {
    for (String name : new String[]{"databases/messages.db", SilenceBackupDetector.DEFAULT_PREFS, SilenceBackupDetector.SECRET_PREFS}) {
      File file = new File(fixture.input, name); byte[] before = SilenceTestBackup.digest(file);
      plan(); assertArrayEquals(before, SilenceTestBackup.digest(file));
    }
  }
  private SilenceMigrationPlan plan() throws Exception {
    try (SilenceBackupStager.Snapshot snapshot = new SilenceBackupStager().stage(fixture.source(), fixture.output)) {
      return new SilenceMigrationPlanner().plan(snapshot, null);
    }
  }
  private void append(String name, String entries) throws IOException {
    File file = new File(fixture.input, name); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (InputStream in = new FileInputStream(file)) {
      byte[] buffer = new byte[4096]; int length;
      while ((length = in.read(buffer)) != -1) bytes.write(buffer, 0, length);
    }
    String xml = new String(bytes.toByteArray(), StandardCharsets.UTF_8).replace("</map>", entries + "</map>");
    try (Writer out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) { out.write(xml); }
  }
}
