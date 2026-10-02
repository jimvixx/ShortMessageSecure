/*
 * Copyright (C) 2014 Open Whisper Systems
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

package org.whispersystems.jobqueue.persistence;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.whispersystems.jobqueue.EncryptionKeys;
import org.whispersystems.jobqueue.Job;
import org.whispersystems.jobqueue.dependencies.AggregateDependencyInjector;
import org.whispersystems.jobqueue.logging.Log;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

public class PersistentStorage {

  private static final int DATABASE_VERSION = 2;

  private static final String TABLE_NAME = "queue";
  private static final String ID = "_id";
  private static final String ITEM = "item";
  private static final String ENCRYPTED = "encrypted";
  private static final String QUARANTINE = "queue_unreadable";
  private static final String CREATE_QUARANTINE =
          "CREATE TABLE IF NOT EXISTS queue_unreadable (_id INTEGER PRIMARY KEY, item TEXT NOT NULL, encrypted INTEGER DEFAULT 0);";

  private static final String DATABASE_CREATE = String.format("CREATE TABLE %s (%s INTEGER PRIMARY KEY, %s TEXT NOT NULL, %s INTEGER DEFAULT 0);",
          TABLE_NAME, ID, ITEM, ENCRYPTED);

  private final Context context;
  private final SQLiteOpenHelper databaseHelper;
  private final JobSerializer jobSerializer;
  private final AggregateDependencyInjector dependencyInjector;

  public PersistentStorage(Context context, String name,
                           JobSerializer serializer,
                           AggregateDependencyInjector dependencyInjector) {
    this(context, new DatabaseHelper(context, "_jobqueue-" + name), serializer, dependencyInjector);
  }

  PersistentStorage(Context context, SQLiteOpenHelper databaseHelper,
                    JobSerializer serializer, AggregateDependencyInjector dependencyInjector) {
    this.databaseHelper = databaseHelper;
    this.context = context;
    this.jobSerializer = serializer;
    this.dependencyInjector = dependencyInjector;
  }

  public void store(Job job) throws IOException {
    ContentValues contentValues = new ContentValues();
    contentValues.put(ITEM, jobSerializer.serialize(job));
    contentValues.put(ENCRYPTED, job.getEncryptionKeys() != null);

    long id = databaseHelper.getWritableDatabase().insertOrThrow(TABLE_NAME, null, contentValues);
    job.setPersistentId(id);
  }

  public List<Job> getAllUnencrypted() {
    return getJobs(null, ENCRYPTED + " = 0");
  }

  public List<Job> getAllEncrypted(EncryptionKeys keys) {
    return getJobs(keys, ENCRYPTED + " = 1");
  }

  private List<Job> getJobs(EncryptionKeys keys, String where) {
    List<Job> results = new LinkedList<>();
    SQLiteDatabase database = databaseHelper.getWritableDatabase();

    try (Cursor cursor = database.query(TABLE_NAME, null, where, null, null, null, ID + " ASC", null)) {

      while (cursor.moveToNext()) {
        long id = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
        String item = cursor.getString(cursor.getColumnIndexOrThrow(ITEM));
        boolean encrypted = cursor.getInt(cursor.getColumnIndexOrThrow(ENCRYPTED)) == 1;

        try {
          Job job = jobSerializer.deserialize(keys, encrypted, item);

          job.setPersistentId(id);
          job.setEncryptionKeys(keys);
          dependencyInjector.injectDependencies(context, job);

          // Rewrite readable legacy data in the current format before handing it to workers.
          ContentValues migrated = new ContentValues();
          migrated.put(ITEM, jobSerializer.serialize(job));
          database.update(TABLE_NAME, migrated, ID + " = ?", new String[]{String.valueOf(id)});
          results.add(job);
        } catch (IOException e) {
          // Keep the original payload (including encryption) for recovery, without retry loops.
          quarantine(database, id, item, encrypted);
          Log.w("PersistentStore", "Unreadable job quarantined: " + id + " (" + e.getClass().getSimpleName() + ")");
        }
      }
    }

    return results;
  }

  private void quarantine(SQLiteDatabase database, long id, String item, boolean encrypted) {
    database.beginTransaction();
    try {
      ContentValues values = new ContentValues();
      // Use a fresh quarantine ID: SQLite may reuse IDs after the live queue becomes empty.
      values.put(ITEM, item);
      values.put(ENCRYPTED, encrypted);
      database.insertOrThrow(QUARANTINE, null, values);
      database.delete(TABLE_NAME, ID + " = ?", new String[]{String.valueOf(id)});
      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }
  }

  public void remove(long id) {
    databaseHelper.getWritableDatabase()
            .delete(TABLE_NAME, ID + " = ?", new String[]{String.valueOf(id)});
  }

  private static class DatabaseHelper extends SQLiteOpenHelper {

    public DatabaseHelper(Context context, String name) {
      super(context, name, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
      db.execSQL(DATABASE_CREATE);
      db.execSQL(CREATE_QUARANTINE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
      if (oldVersion < 2) db.execSQL(CREATE_QUARANTINE);
    }
  }
}
