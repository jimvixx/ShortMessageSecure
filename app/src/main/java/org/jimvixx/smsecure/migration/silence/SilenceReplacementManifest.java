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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Integrity inventory of prepared output only; neither prepares nor applies a replacement. */
final class SilenceReplacementManifest {
  private static final long MAX_BYTES = 512L * 1024 * 1024;
  private static final int MAX_FILES = 20000;
  private static final Set<String> REQUIRED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
      "databases/messages.db", "databases/canonical_address.db",
      "shared_prefs/SecureSMS-Preferences.xml", "shared_prefs/org.jimvixx.smsecure_preferences.xml")));
  private static final Set<String> DIRECTORIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
      "databases", "shared_prefs", "files", "files/sessions-v2", "files/prekeys", "files/signed_prekeys")));
  private final Map<String, String> digests;
  private final Map<String, Long> sizes;

  private SilenceReplacementManifest(Map<String, String> digests, Map<String, Long> sizes) {
    this.digests = Collections.unmodifiableMap(new TreeMap<>(digests));
    this.sizes = Collections.unmodifiableMap(new TreeMap<>(sizes));
  }

  static SilenceReplacementManifest capture(File preparedRoot) throws IOException {
    if (preparedRoot == null || !preparedRoot.isDirectory()) throw new IOException("Missing prepared output");
    File root = preparedRoot.getCanonicalFile();
    Map<String, String> hashes = new TreeMap<>();
    Map<String, Long> sizes = new TreeMap<>();
    walk(root, "", hashes, sizes, new long[]{0});
    if (!hashes.keySet().containsAll(REQUIRED)) throw new IOException("Incomplete prepared output");
    return new SilenceReplacementManifest(hashes, sizes);
  }

  /** Must be called against the exclusively owned staging directory before any future apply. */
  void verify(File preparedRoot) throws IOException {
    SilenceReplacementManifest current = capture(preparedRoot);
    if (!digests.equals(current.digests) || !sizes.equals(current.sizes))
      throw new IOException("Prepared output changed");
  }

  Map<String, String> getDigests() { return digests; }
  Map<String, Long> getSizes() { return sizes; }

  private static void walk(File directory, String prefix, Map<String, String> hashes,
                           Map<String, Long> sizes, long[] total) throws IOException {
    File[] entries = directory.listFiles();
    if (entries == null || entries.length > MAX_FILES) throw new IOException("Invalid prepared directory");
    for (File entry : entries) {
      if (!entry.getCanonicalFile().equals(entry.getAbsoluteFile())) throw new IOException("Linked prepared entry");
      String path = prefix + entry.getName();
      if (entry.isDirectory()) {
        if (!DIRECTORIES.contains(path)) throw new IOException("Unexpected prepared directory");
        walk(entry, path + "/", hashes, sizes, total);
      } else {
        if (!entry.isFile() || !allowed(path) || hashes.size() >= MAX_FILES)
          throw new IOException("Unexpected prepared file");
        long size = entry.length();
        if (size == 0 || size > MAX_BYTES - total[0]) throw new IOException("Invalid prepared size");
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        long read = 0;
        byte[] buffer = new byte[32768];
        try (FileInputStream input = new FileInputStream(entry)) {
          int count;
          while ((count = input.read(buffer)) != -1) {
            read += count;
            if (read > size) throw new IOException("Prepared file changed during inspection");
            digest.update(buffer, 0, count);
          }
        } finally { Arrays.fill(buffer, (byte) 0); }
        if (read != size || entry.length() != size) throw new IOException("Prepared file changed during inspection");
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(Character.forDigit((value >>> 4) & 15, 16))
            .append(Character.forDigit(value & 15, 16));
        hashes.put(path, hex.toString()); sizes.put(path, size); total[0] += size;
      }
    }
  }

  private static boolean allowed(String path) {
    if (REQUIRED.contains(path)) return true;
    if (!path.matches("files/(sessions-v2|prekeys|signed_prekeys)/(0|[1-9][0-9]{0,18})(\\.(0|[1-9][0-9]{0,9}))?")) return false;
    String[] parts = path.substring(path.lastIndexOf('/') + 1).split("\\.");
    try {
      Long.parseLong(parts[0]);
      if (parts.length == 2) Integer.parseInt(parts[1]);
      return true;
    } catch (NumberFormatException e) { return false; }
  }
}
