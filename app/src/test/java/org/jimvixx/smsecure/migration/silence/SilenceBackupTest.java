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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class SilenceBackupTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  private SilenceBackupSource source(Map<String, byte[]> files) {
    return new SilenceBackupSource() {
      public List<Entry> list(String directory) {
        String prefix = directory.isEmpty() ? "" : directory + "/";
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (String path : files.keySet()) if (path.startsWith(prefix)) {
          String rest = path.substring(prefix.length());
          int slash = rest.indexOf('/');
          String name = slash < 0 ? rest : rest.substring(0, slash);
          entries.put(name, new Entry(name, slash >= 0));
        }
        return new ArrayList<>(entries.values());
      }
      public InputStream open(String path) { return new ByteArrayInputStream(files.get(path)); }
    };
  }

  @Test public void stagesOnlyMigrationRootsAndCleansUp() throws Exception {
    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put("files/sessions-v2/1", new byte[]{1, 2});
    files.put("cache/private", new byte[]{3});
    File root;
    try (SilenceBackupStager.Snapshot snapshot = new SilenceBackupStager().stage(source(files), temp.getRoot())) {
      root = snapshot.root();
      assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(new File(root, "files/sessions-v2/1").toPath()));
      assertFalse(new File(root, "cache").exists());
    }
    assertFalse(root.exists());
  }

  @Test public void enforcesActualByteAndEntryLimits() throws Exception {
    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put("files/record", new byte[9]);
    assertThrows(IOException.class, () -> new SilenceBackupStager(8, 10).stage(source(files), temp.getRoot()));
    assertEquals(0, temp.getRoot().list().length);
    assertThrows(IOException.class, () -> new SilenceBackupStager(100, 1).stage(source(files), temp.getRoot()));
    assertEquals(0, temp.getRoot().list().length);
  }

  @Test public void rejectsUnsafeNames() throws Exception {
    for (String name : Arrays.asList("", ".", "..", "../escape", "/absolute", "a\\b", "a\u0000b")) {
      assertThrows(IOException.class, () -> SilenceBackupStager.validateName(name));
    }
  }

  @Test public void deletesPartialSnapshotOnReadFailure() throws Exception {
    SilenceBackupSource failing = new SilenceBackupSource() {
      public List<Entry> list(String directory) {
        return Collections.singletonList(new Entry(directory.isEmpty() ? "files" : "record", directory.isEmpty()));
      }
      public InputStream open(String path) throws IOException { throw new IOException("Offline provider"); }
    };
    assertThrows(IOException.class, () -> new SilenceBackupStager().stage(failing, temp.getRoot()));
    assertEquals(0, temp.getRoot().list().length);
  }

  @Test public void rejectsDuplicateNamesAndCleansUp() throws Exception {
    SilenceBackupSource duplicate = new SilenceBackupSource() {
      public List<Entry> list(String directory) {
        return Arrays.asList(new Entry("files", false), new Entry("files", false));
      }
      public InputStream open(String path) { return new ByteArrayInputStream(new byte[0]); }
    };
    assertThrows(IOException.class, () -> new SilenceBackupStager().stage(duplicate, temp.getRoot()));
    assertEquals(0, temp.getRoot().list().length);
  }

  @Test public void rejectsExcessiveNesting() throws Exception {
    SilenceBackupSource deep = new SilenceBackupSource() {
      public List<Entry> list(String directory) { return Collections.singletonList(new Entry("files", true)); }
      public InputStream open(String path) { throw new AssertionError(); }
    };
    assertThrows(IOException.class, () -> new SilenceBackupStager().stage(deep, temp.getRoot()));
    assertEquals(0, temp.getRoot().list().length);
  }

  @Test public void cancellationCleansUp() throws Exception {
    Thread.currentThread().interrupt();
    try {
      assertThrows(IOException.class, () -> new SilenceBackupStager().stage(
          source(Collections.singletonMap("files/key", new byte[]{1})), temp.getRoot()));
    } finally { Thread.interrupted(); }
    assertEquals(0, temp.getRoot().list().length);
  }

  @Test public void rejectsMissingAndMixedBackups() throws Exception {
    File root = temp.getRoot();
    assertThrows(IOException.class, () -> new SilenceBackupDetector().validate(root));
    makeStructure(root);
    new SilenceBackupDetector().validate(root);
    Files.write(new File(root, "shared_prefs/org.jimvixx.smsecure_preferences.xml").toPath(), new byte[]{1});
    assertThrows(IOException.class, () -> new SilenceBackupDetector().validate(root));
  }

  @Test public void refusesNonemptyJournal() throws Exception {
    makeStructure(temp.getRoot());
    Files.write(new File(temp.getRoot(), "databases/messages.db-wal").toPath(), new byte[]{1});
    assertThrows(IOException.class, () -> new SilenceBackupDetector().validate(temp.getRoot()));
  }

  @Test public void readsSyntheticLegacyPreferences() throws Exception {
    makeStructure(temp.getRoot());
    fixture("legacy-default.xml", SilenceBackupDetector.DEFAULT_PREFS);
    fixture("legacy-secret.xml", SilenceBackupDetector.SECRET_PREFS);
    assertTrue(SilencePreferencesReader.validate(temp.getRoot()));
  }

  @Test public void rejectsDtdDuplicateKeysAndMalformedXml() throws Exception {
    for (String xml : Arrays.asList(
        "<!DOCTYPE map [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><map><string name='x'>&x;</string></map>",
        "<map><string name='x'>a</string><string name='x'>b</string></map>",
        "<map><boolean name='x' value='maybe'/></map>", "<map>")) {
      File file = temp.newFile();
      Files.write(file.toPath(), xml.getBytes(StandardCharsets.UTF_8));
      assertThrows(IOException.class, () -> SilencePreferencesReader.read(file));
    }
  }

  @Test public void rejectsInvalidSecretMaterial() throws Exception {
    makeStructure(temp.getRoot());
    fixture("legacy-default.xml", SilenceBackupDetector.DEFAULT_PREFS);
    fixture("legacy-secret.xml", SilenceBackupDetector.SECRET_PREFS);
    File secret = new File(temp.getRoot(), SilenceBackupDetector.SECRET_PREFS);
    String xml = new String(Files.readAllBytes(secret.toPath()), StandardCharsets.UTF_8);
    Files.write(secret.toPath(), xml.replace("boolean name='passphrase_initialized' value='true'",
        "boolean name='passphrase_initialized' value='false'").getBytes(StandardCharsets.UTF_8));
    assertThrows(IOException.class, () -> SilencePreferencesReader.validate(temp.getRoot()));
  }

  @Test public void resultIsImmutableAndNeverImportReady() {
    SilencePreflightResult result = SilencePreflightResult.valid(new SilenceBackupInfo(30, 2, 1, true, 0));
    assertEquals(SilencePreflightResult.Status.STRUCTURALLY_VALID, result.getStatus());
    assertFalse(result.isReadyToImport());
    assertThrows(UnsupportedOperationException.class, () -> result.getFindings().clear());
    assertFalse(SilencePreflightResult.rejected().isReadyToImport());
  }

  private void fixture(String name, String path) throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/silence/" + name)) {
      assertNotNull(in);
      Files.copy(in, new File(temp.getRoot(), path).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
  }
  private static void makeStructure(File root) throws Exception {
    for (String directory : Arrays.asList("files", "databases", "shared_prefs"))
      assertTrue(new File(root, directory).mkdir());
    for (String path : Arrays.asList("databases/messages.db", "databases/canonical_address.db",
        SilenceBackupDetector.DEFAULT_PREFS, SilenceBackupDetector.SECRET_PREFS))
      Files.write(new File(root, path).toPath(), new byte[]{1});
  }
}
