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

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public class SilenceTargetCryptoFilesTest {
  @Rule public TemporaryFolder storage = new TemporaryFolder();
  @Test public void missingDirectoriesAreNotCreated() throws Exception {
    assertTrue(SilenceTargetCryptoFiles.occupied(storage.getRoot(), slots()).isEmpty());
    assertEquals(0, storage.getRoot().list().length);
  }
  @Test public void scopesSessionsPrekeysAndTemporaryRecords() throws Exception {
    storage.newFolder("sessions-v2"); storage.newFile("sessions-v2/42.7");
    storage.newFolder("prekeys"); storage.newFile("prekeys/0.8.tmp");
    storage.newFolder("signed_prekeys"); storage.newFile("signed_prekeys/3.99");
    assertEquals(slots(), SilenceTargetCryptoFiles.occupied(storage.getRoot(), slots()));
    assertTrue(new File(storage.getRoot(), "prekeys/0.8.tmp").exists());
  }
  @Test public void unrelatedSlotDoesNotBlockCandidates() throws Exception {
    storage.newFolder("sessions-v2"); storage.newFile("sessions-v2/42.99");
    assertTrue(SilenceTargetCryptoFiles.occupied(storage.getRoot(), slots()).isEmpty());
  }
  @Test public void unscopedAndUnknownNamesBlockAll() throws Exception {
    File dir = storage.newFolder("prekeys");
    for (String name : Arrays.asList("42", "index.dat", "3.bad", "3.07", "3.2147483648", "9999999999999999999.7")) {
      File file = new File(dir, name); assertTrue(file.createNewFile());
      assertEquals(slots(), SilenceTargetCryptoFiles.occupied(storage.getRoot(), slots()));
      assertTrue(file.delete());
    }
  }
  @Test public void rejectsUnexpectedDirectoriesOrUnavailableStorage() throws Exception {
    storage.newFolder("sessions-v2", "unexpected");
    assertThrows(IOException.class, () -> SilenceTargetCryptoFiles.occupied(storage.getRoot(), slots()));
    assertThrows(IOException.class, () -> SilenceTargetCryptoFiles.occupied(null, slots()));
  }
  @Test public void rejectsFileInPlaceOfDirectory() throws Exception {
    storage.newFile("signed_prekeys");
    assertThrows(IOException.class, () -> SilenceTargetCryptoFiles.occupied(storage.getRoot(), slots()));
  }
  @Test public void allowsRootAliasButRejectsLinkedCryptoDirectory() throws Exception {
    File real = storage.newFolder("real");
    File alias = new File(storage.getRoot(), "alias");
    java.nio.file.Files.createSymbolicLink(alias.toPath(), real.toPath());
    assertTrue(SilenceTargetCryptoFiles.occupied(alias, slots()).isEmpty());
    File other = storage.newFolder("other");
    java.nio.file.Files.createSymbolicLink(new File(real, "prekeys").toPath(), other.toPath());
    assertThrows(IOException.class, () -> SilenceTargetCryptoFiles.occupied(alias, slots()));
  }
  private static Set<Integer> slots() { return new HashSet<>(Arrays.asList(7, 8)); }
}
