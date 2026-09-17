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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Copies a bounded snapshot into a dedicated private directory, never into restore_staging. */
public final class SilenceBackupStager {
  public static final int MAX_ENTRIES = 20000;
  private static final int MAX_DEPTH = 16;
  private static final long MAX_BYTES = 512L * 1024 * 1024;
  private final long byteLimit;
  private final int entryLimit;
  public SilenceBackupStager() { this(MAX_BYTES, MAX_ENTRIES); }
  SilenceBackupStager(long byteLimit, int entryLimit) {
    if (byteLimit <= 0 || entryLimit <= 0) throw new IllegalArgumentException("Invalid snapshot limits");
    this.byteLimit = byteLimit;
    this.entryLimit = entryLimit;
  }
  private int entries;
  private long bytes;

  public synchronized Snapshot stage(SilenceBackupSource source, File privateParent) throws IOException {
    entries = 0;
    bytes = 0;
    File root = new File(privateParent, "silence-preflight-" + UUID.randomUUID());
    if (!root.mkdirs()) throw new IOException("Cannot create private snapshot");
    Snapshot snapshot = new Snapshot(root);
    try {
      copy(source, "", root, 0);
      return snapshot;
    } catch (IOException | RuntimeException e) {
      try { snapshot.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
      throw e;
    }
  }

  private void copy(SilenceBackupSource source, String path, File target, int depth) throws IOException {
    if (depth > MAX_DEPTH) throw new IOException("Directory nesting limit exceeded");
    Set<String> names = new HashSet<>();
    for (SilenceBackupSource.Entry entry : source.list(path)) {
      checkInterrupted();
      validateName(entry.name);
      if (!names.add(entry.name) || ++entries > entryLimit)
        throw new IOException("Duplicate entry or entry limit exceeded");
      // Silence exports runtime caches too. They are not migration input.
      if (path.isEmpty() && !entry.name.equals("files") && !entry.name.equals("databases")
          && !entry.name.equals("shared_prefs")) continue;
      String child = path.isEmpty() ? entry.name : path + "/" + entry.name;
      File output = new File(target, entry.name);
      if (entry.directory) {
        if (!output.mkdir()) throw new IOException("Cannot create snapshot directory");
        copy(source, child, output, depth + 1);
      } else {
        try (InputStream in = source.open(child); FileOutputStream out = new FileOutputStream(output)) {
          byte[] buffer = new byte[32768];
          int length;
          while ((length = in.read(buffer)) != -1) {
            checkInterrupted();
            if (length == 0) throw new IOException("Source made no progress");
            bytes += length;
            if (bytes > byteLimit) throw new IOException("Snapshot size limit exceeded");
            out.write(buffer, 0, length);
          }
        }
      }
    }
  }

  private static void checkInterrupted() throws IOException {
    if (Thread.currentThread().isInterrupted()) throw new IOException("Analysis cancelled");
  }

  static void validateName(String name) throws IOException {
    if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.length() > 255
        || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0)
      throw new IOException("Unsafe document name");
    for (int i = 0; i < name.length(); i++)
      if (Character.isISOControl(name.charAt(i))) throw new IOException("Unsafe document name");
  }

  public static final class Snapshot implements AutoCloseable {
    private final File root;
    private Snapshot(File root) { this.root = root; }
    File root() { return root; }
    @Override public void close() throws IOException { delete(root); }
    static void delete(File file) throws IOException {
      if (!file.exists()) return;
      if (file.isDirectory()) {
        File[] children = file.listFiles();
        if (children == null) throw new IOException("Cannot enumerate snapshot for cleanup");
        for (File child : children) delete(child);
      }
      if (!file.delete()) throw new IOException("Cannot remove snapshot");
    }
  }
}
