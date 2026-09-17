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

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.jimvixx.smsecure.database.DatabaseFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SilenceDatabaseMigratorTest {
  private SilenceTestBackup fixture;
  @Before public void setup() throws Exception { fixture = new SilenceTestBackup(); }
  @After public void cleanup() throws Exception { if (fixture != null) fixture.close(); }

  @Test public void convertsOnlyCopyAndPreservesEncryptedValues() throws Exception {
    File original = new File(fixture.input, "databases/messages.db");
    byte[] originalDigest = SilenceTestBackup.digest(original);
    try (SilenceBackupStager.Snapshot snapshot = stage()) {
      File stagedSource = new File(snapshot.root(), "databases/messages.db");
      byte[] stagedDigest = SilenceTestBackup.digest(stagedSource);
      File preparedFile;
      try (SilenceDatabaseMigrator.PreparedDatabase prepared = new SilenceDatabaseMigrator().prepare(snapshot)) {
        preparedFile = prepared.file();
        assertEquals(30, prepared.info().getSourceVersion());
        assertEquals(35, prepared.info().getTargetVersion());
        assertEquals(1, prepared.info().getSmsCount());
        assertEquals(1, prepared.info().getIdentityCount());
        try (SQLiteDatabase db = open(preparedFile)) {
          assertEquals(35, db.getVersion());
          SilenceDatabaseContract.validate(db, false);
          try (Cursor row = db.rawQuery("SELECT _id, recipient, identity_key, mac, name, verified FROM identities", null)) {
            assertTrue(row.moveToFirst());
            assertEquals(7, row.getLong(0));
            assertEquals(1, row.getLong(1));
            assertEquals("synthetic-identity", row.getString(2));
            assertEquals("synthetic-mac", row.getString(3));
            assertTrue(row.isNull(4));
            assertEquals(0, row.getInt(5));
          }
          try (Cursor row = db.rawQuery("SELECT body, type, date, date_sent FROM sms", null)) {
            assertTrue(row.moveToFirst());
            assertEquals("synthetic-ciphertext", row.getString(0));
            assertEquals(2147483668L, row.getLong(1));
            assertEquals(1000, row.getLong(2));
            assertEquals(1001, row.getLong(3));
          }
          try (Cursor row = db.rawQuery("SELECT archived, pinned_order, snippet FROM thread", null)) {
            assertTrue(row.moveToFirst());
            assertEquals(1, row.getInt(0));
            assertEquals(0, row.getInt(1));
            assertEquals("synthetic-snippet", row.getString(2));
          }
          try (Cursor row = db.rawQuery("SELECT _data FROM part", null)) {
            assertTrue(row.moveToFirst());
            assertEquals("synthetic-attachment-path", row.getString(0));
          }
        }
      }
      assertFalse(preparedFile.exists());
      assertArrayEquals(stagedDigest, SilenceTestBackup.digest(stagedSource));
    }
    assertArrayEquals(originalDigest, SilenceTestBackup.digest(original));
  }

  @Test public void rejectsTriggerBeforeAnyConversion() throws Exception {
    fixture.mutate("CREATE TRIGGER malicious AFTER INSERT ON identities BEGIN DELETE FROM sms; END");
    assertFailedCopyLeavesSourceIntact();
  }

  @Test public void discardsFailedTransactionOnConflictingRecipients() throws Exception {
    fixture.mutate("DROP TABLE identities",
        "CREATE TABLE identities (_id INTEGER PRIMARY KEY, recipient INTEGER, \"key\" TEXT, mac TEXT)",
        "INSERT INTO identities VALUES (1, 1, 'first', 'first-mac')",
        "INSERT INTO identities VALUES (2, 1, 'second', 'second-mac')");
    assertFailedCopyLeavesSourceIntact();
  }

  @Test public void rejectsIncompleteSchemaInsteadOfStampingVersion35() throws Exception {
    fixture.mutate("ALTER TABLE sms RENAME TO old_sms", "CREATE TABLE sms (_id INTEGER PRIMARY KEY, body TEXT)");
    assertFailedCopyLeavesSourceIntact();
  }

  @Test public void rejectsUnknownIdentityFieldsInsteadOfDroppingThem() throws Exception {
    fixture.mutate("ALTER TABLE identities ADD COLUMN unknown_secret TEXT");
    assertFailedCopyLeavesSourceIntact();
  }

  @Test public void preservesOptionalLegacyIdentityName() throws Exception {
    fixture.mutate("ALTER TABLE identities ADD COLUMN name TEXT", "UPDATE identities SET name = 'synthetic-name'");
    try (SilenceBackupStager.Snapshot snapshot = stage();
         SilenceDatabaseMigrator.PreparedDatabase prepared = new SilenceDatabaseMigrator().prepare(snapshot);
         SQLiteDatabase db = open(prepared.file());
         Cursor row = db.rawQuery("SELECT name FROM identities", null)) {
      assertTrue(row.moveToFirst());
      assertEquals("synthetic-name", row.getString(0));
    }
  }

  @Test public void cancellationRemovesPartialCandidate() throws Exception {
    try (SilenceBackupStager.Snapshot snapshot = stage()) {
      Thread.currentThread().interrupt();
      try { assertThrows(IOException.class, () -> new SilenceDatabaseMigrator().prepare(snapshot)); }
      finally { Thread.interrupted(); }
      assertFalse(new File(snapshot.root(), "database-preview").exists());
    }
  }

  @Test public void canRepeatAfterCleanupWithoutModifyingSnapshot() throws Exception {
    try (SilenceBackupStager.Snapshot snapshot = stage()) {
      for (int i = 0; i < 2; i++) {
        try (SilenceDatabaseMigrator.PreparedDatabase prepared = new SilenceDatabaseMigrator().prepare(snapshot)) {
          assertEquals(35, prepared.info().getTargetVersion());
          assertThrows(IOException.class, () -> new SilenceDatabaseMigrator().prepare(snapshot));
          assertTrue(prepared.file().isFile());
        }
      }
    }
  }

  @Test public void acceptsEmptyIdentityTable() throws Exception {
    fixture.mutate("DELETE FROM identities");
    try (SilenceBackupStager.Snapshot snapshot = stage();
         SilenceDatabaseMigrator.PreparedDatabase prepared = new SilenceDatabaseMigrator().prepare(snapshot)) {
      assertEquals(0, prepared.info().getIdentityCount());
    }
  }

  @Test public void targetVersionMatchesCurrentCoreDatabase() throws Exception {
    Field version = DatabaseFactory.class.getDeclaredField("DATABASE_VERSION");
    version.setAccessible(true);
    assertEquals(version.getInt(null), SilenceDatabaseMigrator.TARGET_VERSION);
  }

  private void assertFailedCopyLeavesSourceIntact() throws Exception {
    try (SilenceBackupStager.Snapshot snapshot = stage()) {
      File source = new File(snapshot.root(), "databases/messages.db");
      byte[] before = SilenceTestBackup.digest(source);
      assertThrows(IOException.class, () -> new SilenceDatabaseMigrator().prepare(snapshot));
      assertArrayEquals(before, SilenceTestBackup.digest(source));
      assertFalse(new File(snapshot.root(), "database-preview").exists());
      try (SQLiteDatabase db = open(source)) { assertEquals(30, db.getVersion()); }
    }
  }
  private SilenceBackupStager.Snapshot stage() throws IOException {
    return new SilenceBackupStager().stage(fixture.source(), fixture.output);
  }
  private static SQLiteDatabase open(File file) {
    return SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
        SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS, ignored -> { });
  }
}
