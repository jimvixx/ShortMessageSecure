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

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads only the user-selected SAF tree. No persistent or write grants are requested. */
public final class SilenceDirectoryBackupSource implements SilenceBackupSource {
  private final ContentResolver resolver;
  private final Uri tree;
  private final Map<String, String> ids = new HashMap<>();
  private final Map<String, List<Entry>> listings = new HashMap<>();
  private final java.util.Set<String> seenIds = new java.util.HashSet<>();

  public SilenceDirectoryBackupSource(ContentResolver resolver, Uri tree) throws IOException {
    if (!"content".equals(tree.getScheme()) || !DocumentsContract.isTreeUri(tree))
      throw new IOException("A document tree is required");
    this.resolver = resolver;
    this.tree = tree;
    String root = DocumentsContract.getTreeDocumentId(tree);
    ids.put("", root);
    seenIds.add(root);
  }

  @Override public List<Entry> list(String directory) throws IOException {
    if (listings.containsKey(directory)) return listings.get(directory);
    String id = ids.get(directory);
    if (id == null) throw new IOException("Unknown directory");
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
    List<Entry> result = new ArrayList<>();
    try (Cursor cursor = resolver.query(children, new String[]{
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
      if (cursor == null) throw new IOException("Cannot enumerate directory");
      java.util.Set<String> names = new java.util.HashSet<>();
      while (cursor.moveToNext()) {
        String childId = cursor.getString(0);
        String name = cursor.getString(1);
        SilenceBackupStager.validateName(name);
        if (!names.add(name) || childId == null || !seenIds.add(childId))
          throw new IOException("Duplicate name or cyclic document tree");
        if (ids.size() > SilenceBackupStager.MAX_ENTRIES)
          throw new IOException("Too many documents");
        ids.put(directory.isEmpty() ? name : directory + "/" + name, childId);
        result.add(new Entry(name, DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))));
      }
    } catch (RuntimeException e) {
      throw new IOException("Cannot read selected directory", e);
    }
    List<Entry> immutable = java.util.Collections.unmodifiableList(result);
    listings.put(directory, immutable);
    return immutable;
  }

  @Override public InputStream open(String path) throws IOException {
    String id = ids.get(path);
    if (id == null) throw new IOException("Unknown document");
    try {
      InputStream input = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id));
      if (input == null) throw new IOException("Cannot open document");
      return input;
    } catch (RuntimeException e) {
      throw new IOException("Cannot read document", e);
    }
  }
}
