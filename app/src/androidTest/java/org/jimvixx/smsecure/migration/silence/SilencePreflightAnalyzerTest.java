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

import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Synthetic fixtures only; no application databases, preferences, or crypto stores are opened. */
@RunWith(AndroidJUnit4.class)
public class SilencePreflightAnalyzerTest {
  private File input;
  private File output;
  private SilenceTestBackup fixture;
  @Before public void setup() throws Exception {
    fixture = new SilenceTestBackup();
    input = fixture.input;
    output = fixture.output;
  }
  @After public void cleanup() throws Exception {
    if (fixture != null) fixture.close();
  }

  @Test public void inspectsSnapshotAndPreservesSource() throws Exception {
    File sourceDb = new File(input, "databases/messages.db");
    byte[] before = digest(sourceDb);
    SilencePreflightResult result = SilenceImportCoordinator.inspect(source(), output);
    assertEquals(SilencePreflightResult.Status.STRUCTURALLY_VALID, result.getStatus());
    assertEquals(1, result.getInfo().getSmsCount());
    assertEquals(1, result.getInfo().getMmsCount());
    assertEquals(1, result.getInfo().getCryptoFileCount());
    assertTrue(result.getInfo().isPassphraseDisabled());
    assertNotNull(result.getDatabaseMigration());
    assertEquals(35, result.getDatabaseMigration().getTargetVersion());
    assertFalse(result.isReadyToImport());
    assertArrayEquals(before, digest(sourceDb));
    assertEquals(0, new File(output, "silence-preflight").list().length);
  }

  @Test public void rejectsUnsupportedSchemaAndCleansUp() throws Exception {
    mutate("PRAGMA user_version = 35");
    assertThrows(IOException.class, () -> SilenceImportCoordinator.inspect(source(), output));
    assertEquals(0, new File(output, "silence-preflight").list().length);
  }
  @Test public void rejectsMissingColumns() throws Exception {
    mutate("ALTER TABLE sms RENAME TO old_sms", "CREATE TABLE sms (_id INTEGER)");
    assertThrows(IOException.class, () -> SilenceImportCoordinator.inspect(source(), output));
  }
  @Test public void rejectsViewsMasqueradingAsTables() throws Exception {
    mutate("ALTER TABLE sms RENAME TO old_sms", "CREATE VIEW sms AS SELECT * FROM old_sms");
    assertThrows(IOException.class, () -> SilenceImportCoordinator.inspect(source(), output));
  }
  @Test public void rejectsOrphanedSms() throws Exception {
    mutate("UPDATE sms SET thread_id = 99");
    assertThrows(IOException.class, () -> SilenceImportCoordinator.inspect(source(), output));
  }
  @Test public void rejectsCorruptDatabaseWithoutDeletingSource() throws Exception {
    File db = new File(input, "databases/messages.db");
    try (OutputStream out = new FileOutputStream(db)) { out.write(new byte[]{1, 2, 3}); }
    assertThrows(IOException.class, () -> SilenceImportCoordinator.inspect(source(), output));
    assertEquals(3, db.length());
  }
  @Test public void rejectsExternalEntitiesOnAndroid() throws Exception {
    File prefs = new File(input, SilenceBackupDetector.DEFAULT_PREFS);
    try (Writer out = new OutputStreamWriter(new FileOutputStream(prefs), java.nio.charset.StandardCharsets.UTF_8)) {
      out.write("<!DOCTYPE map [<!ENTITY x SYSTEM 'file:///does-not-exist'>]><map><string name='x'>&x;</string></map>");
    }
    assertThrows(IOException.class, () -> SilenceImportCoordinator.inspect(source(), output));
  }

  private void mutate(String... sql) {
    try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(input, "databases/messages.db"), null)) {
      for (String statement : sql) db.execSQL(statement);
    }
  }
  private SilenceBackupSource source() {
    return new SilenceBackupSource() {
      public List<Entry> list(String path) throws IOException {
        File[] files = new File(input, path).listFiles();
        if (files == null) throw new IOException("Missing test directory");
        List<Entry> result = new ArrayList<>();
        for (File file : files) result.add(new Entry(file.getName(), file.isDirectory()));
        return result;
      }
      public InputStream open(String path) throws IOException { return new FileInputStream(new File(input, path)); }
    };
  }
  private static byte[] digest(File file) throws Exception {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] buffer = new byte[4096];
      int length;
      while ((length = in.read(buffer)) != -1) digest.update(buffer, 0, length);
    }
    return digest.digest();
  }
}
