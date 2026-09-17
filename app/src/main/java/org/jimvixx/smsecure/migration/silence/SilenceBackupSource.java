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

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** A read-only source. Paths are relative; the empty path denotes the selected root. */
public interface SilenceBackupSource {
  List<Entry> list(String directory) throws IOException;
  InputStream open(String path) throws IOException;

  final class Entry {
    public final String name;
    public final boolean directory;
    public Entry(String name, boolean directory) {
      this.name = name;
      this.directory = directory;
    }
  }
}
