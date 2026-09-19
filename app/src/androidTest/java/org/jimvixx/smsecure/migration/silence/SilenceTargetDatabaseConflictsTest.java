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
import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SilenceTargetDatabaseConflictsTest {
  private File snapshot;
  @Before public void setup() throws Exception {
    snapshot = File.createTempFile("silence-target-fixture", ".db",
        InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
    try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(snapshot, null)) {
      db.disableWriteAheadLogging();
      try (android.database.Cursor mode = db.rawQuery("PRAGMA journal_mode=DELETE", null)) { assertTrue(mode.moveToFirst()); }
      db.execSQL("CREATE TABLE sms (subscription_id INTEGER)");
      db.execSQL("CREATE TABLE recipient_preferences (default_subscription_id INTEGER)");
      db.setVersion(35);
    }
  }
  @After public void cleanup() { if (snapshot != null) SQLiteDatabase.deleteDatabase(snapshot); }
  @Test public void emptySnapshotHasNoBindingConflictsAndIsUnchanged() throws Exception {
    byte[] before = snapshotBytes();
    assertTrue(check().isEmpty()); assertArrayEquals(before, snapshotBytes());
  }
  @Test public void combinesMessagesAndRecipientDefaults() throws Exception {
    mutate("INSERT INTO sms VALUES (7)", "INSERT INTO recipient_preferences VALUES (8)", "INSERT INTO sms VALUES (99)");
    assertEquals(slots(), check());
    assertThrows(UnsupportedOperationException.class, () -> check().clear());
  }
  @Test public void unscopedMessagesBlockAllButUnsetRecipientDefaultDoesNot() throws Exception {
    mutate("INSERT INTO recipient_preferences VALUES (-1)", "INSERT INTO recipient_preferences VALUES (NULL)");
    assertTrue(check().isEmpty());
    mutate("INSERT INTO sms VALUES (-1)"); assertEquals(slots(), check());
    mutate("DELETE FROM sms", "INSERT INTO sms VALUES (NULL)"); assertEquals(slots(), check());
  }
  @Test public void rejectsWrongTypesAndOutOfRangeBindings() throws Exception {
    for (String value : new String[]{"'bad'", "1.5", "-2", "2147483648"}) {
      mutate("DELETE FROM sms", "INSERT INTO sms VALUES (" + value + ")");
      assertThrows(IOException.class, this::check);
    }
  }
  @Test public void rejectsMissingTablesAndWrongVersion() throws Exception {
    mutate("PRAGMA user_version=34"); assertThrows(IOException.class, this::check);
    mutate("PRAGMA user_version=35", "DROP TABLE sms"); assertThrows(IOException.class, this::check);
  }
  @Test public void rejectsUnsettledSnapshot() throws Exception {
    File wal = new File(snapshot.getPath() + "-wal"); assertTrue(wal.createNewFile());
    try { assertThrows(IOException.class, this::check); } finally { assertTrue(wal.delete()); }
  }
  @Test public void readsCommittedWalWithoutCheckpointOrSeeingUncommittedRows() throws Exception {
    try (SQLiteDatabase writer = SQLiteDatabase.openOrCreateDatabase(snapshot, null)) {
      assertTrue(writer.enableWriteAheadLogging());
      writer.execSQL("INSERT INTO sms VALUES (7)");
      File wal = new File(snapshot.getPath() + "-wal");
      assertTrue(wal.isFile()); assertTrue(wal.length() > 0);
      byte[] mainBefore = snapshotBytes();
      byte[] walBefore = fileBytes(wal);
      assertEquals(Collections.singleton(7), SilenceTargetDatabaseConflicts.occupiedCurrent(snapshot, slots()));
      assertArrayEquals(mainBefore, snapshotBytes()); assertArrayEquals(walBefore, fileBytes(wal));
      writer.beginTransaction();
      try {
        writer.execSQL("INSERT INTO recipient_preferences VALUES (8)");
        assertEquals(Collections.singleton(7), SilenceTargetDatabaseConflicts.occupiedCurrent(snapshot, slots()));
        writer.setTransactionSuccessful();
      } finally { writer.endTransaction(); }
      assertEquals(slots(), SilenceTargetDatabaseConflicts.occupiedCurrent(snapshot, slots()));
    }
  }
  @Test public void refusesMissingCurrentDatabaseWithoutCreatingIt() throws Exception {
    File missing = new File(snapshot.getParentFile(), "missing-target-" + snapshot.getName());
    assertThrows(IOException.class, () -> SilenceTargetDatabaseConflicts.occupiedCurrent(missing, slots()));
    assertFalse(missing.exists());
  }
  @Test public void rejectsOversizedReferenceInventory() throws Exception {
    try (SQLiteDatabase writer = SQLiteDatabase.openOrCreateDatabase(snapshot, null)) {
      writer.beginTransaction();
      try {
        for (int i = 0; i < 1025; i++) writer.execSQL("INSERT INTO sms VALUES (?)", new Object[]{i});
        writer.setTransactionSuccessful();
      } finally { writer.endTransaction(); }
    }
    assertThrows(IOException.class, () -> SilenceTargetDatabaseConflicts.occupiedCurrent(snapshot, slots()));
  }
  private byte[] snapshotBytes() throws IOException { return fileBytes(snapshot); }
  private byte[] fileBytes(File file) throws IOException {
    try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096]; int count;
      while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
      return out.toByteArray();
    }
  }
  private Set<Integer> check() throws IOException { return SilenceTargetDatabaseConflicts.occupied(snapshot, slots()); }
  private static Set<Integer> slots() { return new HashSet<>(Arrays.asList(7, 8)); }
  private void mutate(String... sql) {
    try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(snapshot, null)) {
      db.disableWriteAheadLogging();
      try (android.database.Cursor mode = db.rawQuery("PRAGMA journal_mode=DELETE", null)) { assertTrue(mode.moveToFirst()); }
      for (String statement : sql) db.execSQL(statement);
    }
  }
}
