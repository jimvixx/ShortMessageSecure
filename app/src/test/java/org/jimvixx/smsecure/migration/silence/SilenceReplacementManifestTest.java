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

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class SilenceReplacementManifestTest {
  @Rule public TemporaryFolder folder = new TemporaryFolder();
  @Before public void setup() throws Exception {
    write("databases/messages.db", "db"); write("databases/canonical_address.db", "db");
    write("shared_prefs/SecureSMS-Preferences.xml", "synthetic");
    write("shared_prefs/org.jimvixx.smsecure_preferences.xml", "synthetic");
  }
  @Test public void capturesImmutableRelativeInventoryAndVerifies() throws Exception {
    write("files/sessions-v2/12.7", "record");
    SilenceReplacementManifest manifest = capture(); manifest.verify(folder.getRoot());
    assertEquals(5, manifest.getDigests().size());
    assertEquals(Long.valueOf(6), manifest.getSizes().get("files/sessions-v2/12.7"));
    assertThrows(UnsupportedOperationException.class, () -> manifest.getDigests().clear());
    assertThrows(UnsupportedOperationException.class, () -> manifest.getSizes().clear());
  }
  @Test public void detectsSameLengthContentChange() throws Exception {
    SilenceReplacementManifest manifest = capture(); write("databases/messages.db", "xx");
    assertThrows(IOException.class, () -> manifest.verify(folder.getRoot()));
  }
  @Test public void detectsAddedAndRemovedCryptoRecords() throws Exception {
    SilenceReplacementManifest manifest = capture(); write("files/prekeys/1.7", "record");
    assertThrows(IOException.class, () -> manifest.verify(folder.getRoot()));
    SilenceReplacementManifest next = capture(); assertTrue(new File(folder.getRoot(), "files/prekeys/1.7").delete());
    assertThrows(IOException.class, () -> next.verify(folder.getRoot()));
  }
  @Test public void refusesMissingAndEmptyRequiredOutput() throws Exception {
    write("databases/messages.db", ""); assertThrows(IOException.class, this::capture);
    assertTrue(new File(folder.getRoot(), "databases/messages.db").delete());
    assertThrows(IOException.class, this::capture);
  }
  @Test public void rejectsLegacyPreferencesSidecarsAndUnexpectedFiles() throws Exception {
    for (String path : new String[]{"shared_prefs/org.smssecure.smssecure_preferences.xml", "databases/messages.db-wal",
        "files/sessions-v2/12.7.tmp", "files/prekeys/1.2147483648", "files/logs/diagnostic.log"}) {
      File file = write(path, "x"); assertThrows(IOException.class, this::capture);
      assertTrue(file.delete());
      if (file.getParentFile().getName().equals("logs")) assertTrue(file.getParentFile().delete());
    }
  }
  @Test public void rejectsSymlinkAndOversizedOutput() throws Exception {
    File file = new File(folder.getRoot(), "databases/alias");
    Files.createSymbolicLink(file.toPath(), new File(folder.getRoot(), "databases/messages.db").toPath());
    assertThrows(IOException.class, this::capture); assertTrue(file.delete());
    try (RandomAccessFile large = new RandomAccessFile(new File(folder.getRoot(), "databases/messages.db"), "rw")) {
      large.setLength(512L * 1024 * 1024 + 1);
    }
    assertThrows(IOException.class, this::capture);
  }
  private SilenceReplacementManifest capture() throws IOException { return SilenceReplacementManifest.capture(folder.getRoot()); }
  private File write(String path, String content) throws IOException {
    File file = new File(folder.getRoot(), path); file.getParentFile().mkdirs();
    try (FileOutputStream out = new FileOutputStream(file)) { out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    return file;
  }
}
