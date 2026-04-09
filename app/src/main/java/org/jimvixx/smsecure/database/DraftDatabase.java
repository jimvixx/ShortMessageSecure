/*
 * Copyright (C) 2013 Open Whisper Systems
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

package org.jimvixx.smsecure.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.jimvixx.smsecure.crypto.MasterCipher;
import org.jimvixx.smsecure.logging.Log;
import org.whispersystems.libsignal.InvalidMessageException;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class DraftDatabase extends Database {

  public static final String ID = "_id";
  public static final String THREAD_ID = "thread_id";
  public static final String DRAFT_TYPE = "type";
  public static final String DRAFT_VALUE = "value";
  private static final String TABLE_NAME = "drafts";
  public static final String CREATE_TABLE =
          "CREATE TABLE " + TABLE_NAME + " (" +
                  ID + " INTEGER PRIMARY KEY, " +
                  THREAD_ID + " INTEGER, " +
                  DRAFT_TYPE + " TEXT, " +
                  DRAFT_VALUE + " TEXT);";

  public static final String[] CREATE_INDEXS = {
          "CREATE INDEX IF NOT EXISTS draft_thread_index ON " + TABLE_NAME + " (" + THREAD_ID + ");",
  };

  public DraftDatabase(Context context, SQLiteOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  public void insertDrafts(MasterCipher masterCipher, long threadId, List<Draft> drafts) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();

    for (Draft draft : drafts) {
      ContentValues values = new ContentValues(3);
      values.put(THREAD_ID, threadId);
      values.put(DRAFT_TYPE, masterCipher.encryptBody(draft.getType()));
      values.put(DRAFT_VALUE, masterCipher.encryptBody(draft.getValue()));
      db.insert(TABLE_NAME, null, values);
    }
  }

  public void clearDrafts(long threadId) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.delete(TABLE_NAME, THREAD_ID + " = ?", new String[]{String.valueOf(threadId)});
  }

  public void clearDrafts(Set<Long> threadIds) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    StringBuilder where = new StringBuilder();
    List<String> arguments = new LinkedList<>();

    for (long threadId : threadIds) {
      where.append(" OR ")
              .append(THREAD_ID)
              .append(" = ?");
      arguments.add(String.valueOf(threadId));
    }

    db.delete(TABLE_NAME, where.toString().substring(4), arguments.toArray(new String[0]));
  }

  public void clearAllDrafts() {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.delete(TABLE_NAME, null, null);
  }

  public List<Draft> getDrafts(MasterCipher masterCipher, long threadId) {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    List<Draft> results = new LinkedList<>();

    try (Cursor cursor = db.query(TABLE_NAME, null, THREAD_ID + " = ?",
            new String[]{String.valueOf(threadId)},
            null, null, null)) {

      while (cursor.moveToNext()) {
        try {
          String encryptedType = cursor.getString(cursor.getColumnIndexOrThrow(DRAFT_TYPE));
          String encryptedValue = cursor.getString(cursor.getColumnIndexOrThrow(DRAFT_VALUE));

          results.add(new Draft(
                  masterCipher.decryptBody(encryptedType),
                  masterCipher.decryptBody(encryptedValue)
          ));
        } catch (InvalidMessageException e) {
          Log.w("DraftDatabase", e);
        }
      }

      return results;
    }
  }

  public static class Draft {
    public static final String TEXT = "text";

    private final String type;
    private final String value;

    public Draft(String type, String value) {
      this.type = type;
      this.value = value;
    }

    public String getType() {
      return type;
    }

    public String getValue() {
      return value;
    }

    public String getSnippet() {
      return TEXT.equals(type) && value != null ? value : "";
    }
  }

  public static class Drafts extends LinkedList<Draft> {

    private Draft getDraftOfType() {
      for (Draft draft : this) {
        if (Draft.TEXT.equals(draft.getType())) {
          return draft;
        }
      }
      return null;
    }

    public String getSnippet() {
      Draft textDraft = getDraftOfType();
      return textDraft != null ? textDraft.getSnippet() : "";
    }
  }
}