/*
 * Copyright (C) 2015 Open Whisper Systems
 * Copyright (C) 2025 Jimvixx
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

package org.jimvixx.smsecure.database.loaders;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;

import androidx.annotation.Nullable;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.ThreadDatabase;
import org.jimvixx.smsecure.util.AbstractCursorLoader;

import java.util.LinkedList;
import java.util.List;

/**
 * Loader for conversation list.
 * Handles:
 *  - normal (unarchived) list
 *  - archived list
 *  - filtered (search) list
 *
 * Search is delegated to ThreadDatabase and is performed against chats as represented
 * by the application itself, not against Android contacts.
 */
public class ConversationListLoader extends AbstractCursorLoader {

  private final String       filter;
  private final boolean      archived;
  private final MasterSecret masterSecret;

  public ConversationListLoader(Context context,
                                @Nullable MasterSecret masterSecret,
                                @Nullable String filter,
                                boolean archived)
  {
    super(context);
    this.masterSecret = masterSecret;
    this.filter       = filter;
    this.archived     = archived;
  }

  @Override
  public Cursor getCursor() {
    if (filter != null && !filter.trim().isEmpty()) {
      return getFilteredConversationList(filter);
    } else if (!archived) {
      return getUnarchivedConversationList();
    } else {
      return getArchivedConversationList();
    }
  }

  private Cursor getUnarchivedConversationList() {
    List<Cursor> cursorList = new LinkedList<>();
    cursorList.add(DatabaseFactory.getThreadDatabase(context).getConversationList());

    int archivedCount =
            DatabaseFactory.getThreadDatabase(context)
                    .getArchivedConversationListCount();

    if (archivedCount > 0) {
      MatrixCursor switchToArchiveCursor =
              new MatrixCursor(new String[] {
                      ThreadDatabase.ID,
                      ThreadDatabase.DATE,
                      ThreadDatabase.MESSAGE_COUNT,
                      ThreadDatabase.RECIPIENT_IDS,
                      ThreadDatabase.SNIPPET,
                      ThreadDatabase.READ,
                      ThreadDatabase.TYPE,
                      ThreadDatabase.SNIPPET_TYPE,
                      ThreadDatabase.SNIPPET_URI,
                      ThreadDatabase.ARCHIVED,
                      ThreadDatabase.STATUS,
                      ThreadDatabase.LAST_SEEN
              }, 1);

      switchToArchiveCursor.addRow(new Object[] {
              -1L,
              System.currentTimeMillis(),
              archivedCount,
              "-1",
              null,
              1,
              ThreadDatabase.DistributionTypes.ARCHIVE,
              0,
              null,
              0,
              -1,
              0
      });

      cursorList.add(switchToArchiveCursor);
    }

    return new MergeCursor(cursorList.toArray(new Cursor[0]));
  }

  private Cursor getArchivedConversationList() {
    return DatabaseFactory.getThreadDatabase(context).getArchivedConversationList();
  }

  private Cursor getFilteredConversationList(@Nullable String rawFilter) {
    final String query = rawFilter == null ? "" : rawFilter.trim();

    return DatabaseFactory.getThreadDatabase(context)
            .getFilteredConversationList(query, archived, masterSecret);
  }
}