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
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Synthetic input owned exclusively by each test and cleaned after use. */
final class SilenceTestBackup implements AutoCloseable {
  final File input;
  final File output;
  SilenceTestBackup() throws Exception {
    File cache = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
    input = new File(cache, "silence-test-input-" + UUID.randomUUID());
    output = new File(cache, "silence-test-output-" + UUID.randomUUID());
    assertTrue(input.mkdirs());
    assertTrue(output.mkdirs());
    for (String directory : Arrays.asList("files/signed_prekeys", "databases", "shared_prefs"))
      assertTrue(new File(input, directory).mkdirs());
    for (String[] entry : new String[][]{{"legacy-default.xml", SilenceBackupDetector.DEFAULT_PREFS},
        {"legacy-secret.xml", SilenceBackupDetector.SECRET_PREFS}}) {
      try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("silence/" + entry[0]);
           OutputStream out = new FileOutputStream(new File(input, entry[1]))) {
        byte[] buffer = new byte[4096];
        int length;
        while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
      }
    }
    try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(input, "databases/messages.db"), null)) {
      db.setVersion(30);
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(
          InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("silence/schema-30.sql"),
          java.nio.charset.StandardCharsets.UTF_8))) {
        String statement;
        while ((statement = reader.readLine()) != null) if (!statement.trim().isEmpty()) db.execSQL(statement);
      }
    }
    try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(input, "databases/canonical_address.db"), null)) {
      db.setVersion(1);
      db.execSQL("CREATE TABLE canonical_addresses (_id INTEGER PRIMARY KEY, address TEXT NOT NULL)");
      db.execSQL("INSERT INTO canonical_addresses VALUES (1, 'synthetic')");
    }
    try (OutputStream out = new FileOutputStream(new File(input, "files/signed_prekeys/1"))) { out.write(1); }
  }
  @Override public void close() throws IOException {
    SilenceBackupStager.Snapshot.delete(input);
    SilenceBackupStager.Snapshot.delete(output);
  }
  void mutate(String... sql) {
    try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(input, "databases/messages.db"), null)) {
      for (String statement : sql) db.execSQL(statement);
    }
  }
  SilenceBackupSource source() {
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
  static byte[] digest(File file) throws Exception {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] buffer = new byte[4096];
      int length;
      while ((length = in.read(buffer)) != -1) digest.update(buffer, 0, length);
    }
    return digest.digest();
  }
}
