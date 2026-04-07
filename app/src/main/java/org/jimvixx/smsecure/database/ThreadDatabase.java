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

package org.jimvixx.smsecure.database;

import static org.jimvixx.smsecure.util.Util.partition;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.crypto.MasterCipher;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.model.DisplayRecord;
import org.jimvixx.smsecure.database.model.MessageRecord;
import org.jimvixx.smsecure.database.model.ThreadRecord;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.whispersystems.libsignal.InvalidMessageException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ThreadDatabase extends Database {

  public static final String ID = "_id";
  public static final String DATE = "date";
  public static final String MESSAGE_COUNT = "message_count";
  public static final String RECIPIENT_IDS = "recipient_ids";
  public static final String SNIPPET = "snippet";
  public static final String READ = "read";
  public static final String TYPE = "type";
  public static final String SNIPPET_TYPE = "snippet_type";
  public static final String SNIPPET_URI = "snippet_uri";
  public static final String ARCHIVED = "archived";
  public static final String STATUS = "status";
  public static final String LAST_SEEN = "last_seen";
  static final String TABLE_NAME = "thread";
  public static final String[] CREATE_INDEXS = {
          "CREATE INDEX IF NOT EXISTS thread_recipient_ids_index ON " + TABLE_NAME + " (" + RECIPIENT_IDS + ");",
          "CREATE INDEX IF NOT EXISTS archived_index ON " + TABLE_NAME + " (" + ARCHIVED + ");",
  };
  private static final String TAG = ThreadDatabase.class.getSimpleName();
  private static final String SNIPPET_CHARSET = "snippet_cs";
  private static final String ERROR = "error";
  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + " (" +
          ID + " INTEGER PRIMARY KEY, " + DATE + " INTEGER DEFAULT 0, " +
          MESSAGE_COUNT + " INTEGER DEFAULT 0, " + RECIPIENT_IDS + " TEXT, " + SNIPPET + " TEXT, " +
          SNIPPET_CHARSET + " INTEGER DEFAULT 0, " + READ + " INTEGER DEFAULT 1, " +
          TYPE + " INTEGER DEFAULT 0, " + ERROR + " INTEGER DEFAULT 0, " +
          SNIPPET_TYPE + " INTEGER DEFAULT 0, " + SNIPPET_URI + " TEXT DEFAULT NULL, " +
          ARCHIVED + " INTEGER DEFAULT 0, " + STATUS + " INTEGER DEFAULT 0, " +
          LAST_SEEN + " INTEGER DEFAULT 0);";

  public ThreadDatabase(Context context, SQLiteOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  private static void appendSearchPart(@NonNull StringBuilder sb, @Nullable String value) {
    if (TextUtils.isEmpty(value)) {
      return;
    }

    if (sb.length() > 0) {
      sb.append('\n');
    }

    sb.append(value);
  }

  private static boolean containsNormalized(@Nullable String candidate, @NonNull String normalizedQuery) {
    if (TextUtils.isEmpty(candidate) || normalizedQuery.isEmpty()) {
      return false;
    }

    return normalizeSearchText(candidate).contains(normalizedQuery);
  }

  private static String normalizeSearchText(@Nullable String value) {
    if (value == null) {
      return "";
    }

    String normalized = value
            .toLowerCase(Locale.ROOT)
            .replace('\u00A0', ' ')
            .trim();

    return normalized.replaceAll("\\s+", " ");
  }

  private long[] getRecipientIds(Recipients recipients) {
    Set<Long> recipientSet = new HashSet<>();
    List<Recipient> recipientList = recipients.getRecipientsList();

    for (Recipient recipient : recipientList) {
      recipientSet.add(recipient.getRecipientId());
    }

    long[] recipientArray = new long[recipientSet.size()];
    int i = 0;

    for (Long recipientId : recipientSet) {
      recipientArray[i++] = recipientId;
    }

    Arrays.sort(recipientArray);

    return recipientArray;
  }

  private String getRecipientsAsString(long[] recipientIds) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < recipientIds.length; i++) {
      if (i != 0) sb.append(' ');
      sb.append(recipientIds[i]);
    }

    return sb.toString();
  }

  private long createThreadForRecipients(String recipients, int recipientCount, int distributionType) {
    ContentValues contentValues = new ContentValues(4);
    long date = System.currentTimeMillis();

    contentValues.put(DATE, date - date % 1000);
    contentValues.put(RECIPIENT_IDS, recipients);

    if (recipientCount > 1) {
      contentValues.put(TYPE, distributionType);
    }

    contentValues.put(MESSAGE_COUNT, 0);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    return db.insert(TABLE_NAME, null, contentValues);
  }

  private void updateThread(long threadId, long count, String body,
                            long date, int status, long type, boolean unarchive) {
    ContentValues contentValues = new ContentValues(6);
    contentValues.put(DATE, date - date % 1000);
    contentValues.put(MESSAGE_COUNT, count);
    contentValues.put(SNIPPET, body);
    contentValues.put(SNIPPET_URI, (byte[]) null);
    contentValues.put(SNIPPET_TYPE, type);
    contentValues.put(STATUS, status);

    if (unarchive) {
      contentValues.put(ARCHIVED, 0);
    }

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID + " = ?", new String[]{threadId + ""});
    notifyConversationListListeners();
  }

  public void updateSnippet(long threadId, String snippet, @Nullable Uri attachment, long date, long type, boolean unarchive) {
    ContentValues contentValues = new ContentValues(4);

    contentValues.put(DATE, date - date % 1000);
    contentValues.put(SNIPPET, snippet);
    contentValues.put(SNIPPET_TYPE, type);
    contentValues.put(SNIPPET_URI, attachment == null ? null : attachment.toString());

    if (unarchive) {
      contentValues.put(ARCHIVED, 0);
    }

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID + " = ?", new String[]{threadId + ""});
    notifyConversationListListeners();
  }

  private void deleteThread(long threadId) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.delete(TABLE_NAME, ID_WHERE, new String[]{threadId + ""});
    notifyConversationListListeners();
  }

  private void deleteThreads(Set<Long> threadIds) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    StringBuilder where = new StringBuilder();

    for (long threadId : threadIds) {
      where.append(ID).append(" = '").append(threadId).append("' OR ");
    }

    where = new StringBuilder(where.substring(0, where.length() - 4));

    db.delete(TABLE_NAME, where.toString(), null);
    notifyConversationListListeners();
  }

  private void deleteAllThreads() {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.delete(TABLE_NAME, null, null);
    notifyConversationListListeners();
  }

  public void trimAllThreads(int length, @NonNull ProgressListener listener) {
    int threadCount;
    int complete = 0;

    Cursor cursor = this.getConversationList();
    if (cursor == null) {
      return;
    }

    try (cursor) {
      threadCount = cursor.getCount();

      while (cursor.moveToNext()) {
        long threadId = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
        trimThread(threadId, length);

        listener.onProgress(++complete, threadCount);
      }
    }
  }

  public void trimThread(long threadId, int length) {
    Log.w("ThreadDatabase", "Trimming thread: " + threadId + " to: " + length);

    if (length <= 0) {
      return;
    }

    Cursor cursor = DatabaseFactory.getMessageDatabase(context).getConversation(threadId);
    if (cursor == null) {
      return;
    }

    try (cursor) {
      if (cursor.getCount() <= length) {
        return;
      }

      Log.w("ThreadDatabase", "Cursor count is greater than length!");

      if (!cursor.moveToPosition(length - 1)) {
        return;
      }

      long cutoffDate = cursor.getLong(
              cursor.getColumnIndexOrThrow(MessageColumns.NORMALIZED_DATE_RECEIVED)
      );

      Log.w("ThreadDatabase", "Cut off message date: " + cutoffDate);

      DatabaseFactory.getSmsDatabase(context).deleteMessagesInThreadBeforeDate(threadId, cutoffDate);

      update(threadId, false);
      notifyConversationListeners(threadId);
    }
  }

  public void setAllThreadsRead() {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(READ, 1);

    db.update(TABLE_NAME, contentValues, null, null);

    DatabaseFactory.getSmsDatabase(context).setAllMessagesRead();
    notifyConversationListListeners();
  }

  public void setRead(long threadId) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(READ, 1);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[]{threadId + ""});

    DatabaseFactory.getSmsDatabase(context).setMessagesRead(threadId);
    notifyConversationListListeners();
  }

  public void setUnread(long threadId) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(READ, 0);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[]{threadId + ""});
    notifyConversationListListeners();
  }

  /**
   * Returns an empty cursor with the same projection as thread table queries used by the UI.
   * Returning an empty cursor is preferred over null because null can keep stale adapter data.
   */
  private Cursor createEmptyThreadCursor() {
    return new MatrixCursor(new String[]{
            ID, DATE, MESSAGE_COUNT, RECIPIENT_IDS, SNIPPET, READ,
            TYPE, SNIPPET_TYPE, SNIPPET_URI, ARCHIVED, STATUS, LAST_SEEN
    }, 0);
  }

  /**
   * Thread-centric search for the current view.
   *
   * Search sources:
   * - app-resolved participant labels for each thread
   * - thread snippet
   * - full conversation message bodies through MessageDatabase.Reader
   *
   * This intentionally does not use Android contacts.
   */
  public Cursor getFilteredConversationList(@NonNull String rawQuery,
                                            boolean archivedView,
                                            @Nullable MasterSecret masterSecret) {
    final String normalizedQuery = normalizeSearchText(rawQuery);

    if (normalizedQuery.isEmpty()) {
      return createEmptyThreadCursor();
    }

    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    Set<Long> matchingThreadIds = new HashSet<>();

    try (Cursor threadCursor = db.query(
            TABLE_NAME,
            new String[]{ID, RECIPIENT_IDS, SNIPPET},
            ARCHIVED + " = ?",
            new String[]{archivedView ? "1" : "0"},
            null,
            null,
            DATE + " DESC"
    )) {
      while (threadCursor.moveToNext()) {
        long threadId = threadCursor.getLong(threadCursor.getColumnIndexOrThrow(ID));
        String recipientIds = threadCursor.getString(threadCursor.getColumnIndexOrThrow(RECIPIENT_IDS));
        String snippet = threadCursor.getString(threadCursor.getColumnIndexOrThrow(SNIPPET));

        if (matchesParticipants(recipientIds, normalizedQuery)) {
          matchingThreadIds.add(threadId);
          continue;
        }

        if (containsNormalized(snippet, normalizedQuery)) {
          matchingThreadIds.add(threadId);
          continue;
        }

        if (matchesConversationBody(threadId, normalizedQuery, masterSecret)) {
          matchingThreadIds.add(threadId);
        }
      }
    }

    if (matchingThreadIds.isEmpty()) {
      return createEmptyThreadCursor();
    }

    return getThreadsByIds(matchingThreadIds, archivedView);
  }

  private boolean matchesParticipants(@Nullable String recipientIds, @NonNull String normalizedQuery) {
    if (TextUtils.isEmpty(recipientIds)) {
      return false;
    }

    Recipients recipients = RecipientFactory.getRecipientsForIds(context, recipientIds, true);
    String searchableText = buildParticipantsSearchText(recipients);

    return containsNormalized(searchableText, normalizedQuery);
  }

  private boolean matchesConversationBody(long threadId,
                                          @NonNull String normalizedQuery,
                                          @Nullable MasterSecret masterSecret) {
    Cursor cursor = DatabaseFactory.getMessageDatabase(context).getConversation(threadId);
    if (cursor == null) {
      return false;
    }

    MessageDatabase.Reader reader = null;

    try {
      reader = DatabaseFactory.getMessageDatabase(context).readerFor(cursor, masterSecret);
      MessageRecord record;

      while ((record = reader.getNext()) != null) {
        if (record.getBody() != null && containsNormalized(record.getBody().getBody(), normalizedQuery)) {
          return true;
        }
      }

      return false;
    } finally {
      if (reader != null) {
        reader.close();
      } else {
        cursor.close();
      }
    }
  }

  private Cursor getThreadsByIds(@NonNull Set<Long> threadIds, boolean archivedView) {
    if (threadIds.isEmpty()) {
      return createEmptyThreadCursor();
    }

    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    List<Long> ids = new ArrayList<>(threadIds);
    List<List<Long>> partitionedIds = partition(ids, 900);
    List<Cursor> cursors = new LinkedList<>();

    for (List<Long> chunk : partitionedIds) {
      StringBuilder selection = new StringBuilder();
      List<String> args = new LinkedList<>();

      selection.append(ARCHIVED).append(" = ?");
      args.add(archivedView ? "1" : "0");

      selection.append(" AND ").append(ID).append(" IN (");
      for (int i = 0; i < chunk.size(); i++) {
        if (i > 0) {
          selection.append(", ");
        }
        selection.append("?");
        args.add(String.valueOf(chunk.get(i)));
      }
      selection.append(")");

      Cursor cursor = db.query(
              TABLE_NAME,
              null,
              selection.toString(),
              args.toArray(new String[0]),
              null,
              null,
              DATE + " DESC"
      );

      cursors.add(cursor);
    }

    Cursor cursor = cursors.size() > 1
            ? new MergeCursor(cursors.toArray(new Cursor[0]))
            : cursors.get(0);

    setNotifyConverationListListeners(cursor);
    return cursor;
  }

  /**
   * Builds searchable text from app-resolved conversation participants.
   * This method intentionally avoids Android contacts lookup.
   */
  private String buildParticipantsSearchText(@Nullable Recipients recipients) {
    if (recipients == null) {
      return "";
    }

    StringBuilder sb = new StringBuilder();

    try {
      appendSearchPart(sb, recipients.toShortString());
    } catch (Throwable t) {
      Log.w(TAG, "Unable to read recipients short string for search", t);
    }

    List<Recipient> recipientList = recipients.getRecipientsList();

    if (recipientList == null) {
      return sb.toString();
    }

    for (Recipient recipient : recipientList) {
      if (recipient == null) {
        continue;
      }

      try {
        appendSearchPart(sb, recipient.getName());
      } catch (Throwable t) {
        Log.w(TAG, "Unable to read recipient name for search", t);
      }

      try {
        appendSearchPart(sb, recipient.getNumber());
      } catch (Throwable t) {
        Log.w(TAG, "Unable to read recipient number for search", t);
      }

      try {
        appendSearchPart(sb, recipient.toShortString());
      } catch (Throwable t) {
        Log.w(TAG, "Unable to read recipient short string for search", t);
      }
    }

    return sb.toString();
  }

  public Cursor getConversationList() {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    Cursor cursor = db.query(TABLE_NAME, null, ARCHIVED + " = ?", new String[]{"0"}, null, null, DATE + " DESC");

    setNotifyConverationListListeners(cursor);

    return cursor;
  }

  public Cursor getArchivedConversationList() {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    Cursor cursor = db.query(TABLE_NAME, null, ARCHIVED + " = ?", new String[]{"1"}, null, null, DATE + " DESC");

    setNotifyConverationListListeners(cursor);

    return cursor;
  }

  public Cursor getDirectShareList() {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    return db.query(TABLE_NAME, null, null, null, null, null, DATE + " DESC");
  }

  public int getArchivedConversationListCount() {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();

    try (Cursor cursor = db.query(
            TABLE_NAME,
            new String[]{"COUNT(*)"},
            ARCHIVED + " = ?",
            new String[]{"1"},
            null,
            null,
            null
    )) {
      return cursor.moveToFirst() ? cursor.getInt(0) : 0;
    }
  }

  public void archiveConversation(long threadId) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(ARCHIVED, 1);

    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[]{threadId + ""});
    notifyConversationListListeners();
  }

  public void unarchiveConversation(long threadId) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(ARCHIVED, 0);

    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[]{threadId + ""});
    notifyConversationListListeners();
  }

  public void setLastSeen(long threadId) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(LAST_SEEN, System.currentTimeMillis());

    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[]{String.valueOf(threadId)});
    notifyConversationListListeners();
  }

  public long getLastSeen(long threadId) {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();

    try (Cursor cursor = db.query(
            TABLE_NAME,
            new String[]{LAST_SEEN},
            ID_WHERE,
            new String[]{String.valueOf(threadId)},
            null,
            null,
            null
    )) {
      return cursor.moveToFirst() ? cursor.getLong(0) : -1;
    }
  }

  public void deleteConversation(long threadId) {
    DatabaseFactory.getSmsDatabase(context).deleteThread(threadId);
    DatabaseFactory.getDraftDatabase(context).clearDrafts(threadId);
    deleteThread(threadId);
    notifyConversationListeners(threadId);
    notifyConversationListListeners();
  }

  public void deleteConversations(Set<Long> selectedConversations) {
    DatabaseFactory.getSmsDatabase(context).deleteThreads(selectedConversations);
    DatabaseFactory.getDraftDatabase(context).clearDrafts(selectedConversations);
    deleteThreads(selectedConversations);
    notifyConversationListeners(selectedConversations);
    notifyConversationListListeners();
  }

  public void deleteAllConversations() {
    DatabaseFactory.getSmsDatabase(context).deleteAllThreads();
    DatabaseFactory.getDraftDatabase(context).clearAllDrafts();
    deleteAllThreads();
  }

  public long getThreadIdIfExistsFor(Recipients recipients) {
    long[] recipientIds = getRecipientIds(recipients);
    String recipientsList = getRecipientsAsString(recipientIds);

    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    String where = RECIPIENT_IDS + " = ?";
    String[] recipientsArg = new String[]{recipientsList};

    try (Cursor cursor = db.query(TABLE_NAME, new String[]{ID}, where, recipientsArg, null, null, null)) {
      return cursor.moveToFirst()
              ? cursor.getLong(cursor.getColumnIndexOrThrow(ID))
              : -1L;
    }
  }

  public long getThreadIdFor(Recipients recipients) {
    return getThreadIdFor(recipients, DistributionTypes.DEFAULT);
  }

  public long getThreadIdFor(Recipients recipients, int distributionType) {
    long[] recipientIds = getRecipientIds(recipients);
    String recipientsList = getRecipientsAsString(recipientIds);

    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    String where = RECIPIENT_IDS + " = ?";
    String[] recipientsArg = new String[]{recipientsList};

    try (Cursor cursor = db.query(
            TABLE_NAME,
            new String[]{ID},
            where,
            recipientsArg,
            null,
            null,
            null
    )) {
      if (cursor.moveToFirst()) {
        return cursor.getLong(0);
      }
    }

    return createThreadForRecipients(
            recipientsList,
            recipientIds.length,
            distributionType
    );
  }

  @Nullable
  public Recipients getRecipientsForThreadId(long threadId) {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();

    try (Cursor cursor = db.query(
            TABLE_NAME,
            null,
            ID + " = ?",
            new String[]{String.valueOf(threadId)},
            null,
            null,
            null
    )) {
      if (!cursor.moveToFirst()) {
        return null;
      }

      String recipientIds = cursor.getString(
              cursor.getColumnIndexOrThrow(RECIPIENT_IDS)
      );

      return RecipientFactory.getRecipientsForIds(
              context,
              recipientIds,
              false
      );
    }
  }

  public boolean update(long threadId, boolean unarchive) {
    MessageDatabase messageDatabase = DatabaseFactory.getMessageDatabase(context);
    long count = messageDatabase.getConversationCount(threadId);

    if (count == 0) {
      deleteThread(threadId);
      notifyConversationListListeners();
      return true;
    }

    MessageDatabase.Reader reader = null;

    try {
      reader = messageDatabase.readerFor(messageDatabase.getConversationSnippet(threadId));
      MessageRecord record;

      if (reader != null && (record = reader.getNext()) != null) {
        updateThread(threadId,
                count,
                record.getBody().getBody(),
                record.getTimestamp(),
                record.getDeliveryStatus(),
                record.getType(),
                unarchive);
        notifyConversationListListeners();
        return false;
      } else {
        deleteThread(threadId);
        notifyConversationListListeners();
        return true;
      }
    } finally {
      if (reader != null) {
        reader.close();
      }
    }
  }
  public Reader readerFor(Cursor cursor, MasterCipher masterCipher) {
    return new Reader(cursor, masterCipher);
  }

  public interface ProgressListener {
    void onProgress(int complete, int total);
  }

  public static class DistributionTypes {
    public static final int BROADCAST = 1;
    public static final int DEFAULT = BROADCAST;
    public static final int ARCHIVE = 3;
  }
  public class Reader {

    private final Cursor cursor;
    private final MasterCipher masterCipher;

    public Reader(Cursor cursor, MasterCipher masterCipher) {
      this.cursor = cursor;
      this.masterCipher = masterCipher;
    }

    public ThreadRecord getNext() {
      if (cursor == null || !cursor.moveToNext()) {
        return null;
      }

      return getCurrent();
    }

    public ThreadRecord getCurrent() {
      long threadId = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.ID));
      String recipientId = cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.RECIPIENT_IDS));
      Recipients recipients = RecipientFactory.getRecipientsForIds(context, recipientId, true);

      DisplayRecord.Body body = getPlaintextBody(cursor);
      long date = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.DATE));
      long count = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.MESSAGE_COUNT));
      long read = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.READ));
      long type = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_TYPE));
      int distributionType = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.TYPE));
      int archived = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.ARCHIVED));
      int status = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.STATUS));
      long lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.LAST_SEEN));
      Uri snippetUri = getSnippetUri(cursor);

      return new ThreadRecord(context, body, snippetUri, recipients, date, count, read == 1,
              threadId, status, type, distributionType, (archived != 0), lastSeen);
    }

    private DisplayRecord.Body getPlaintextBody(Cursor cursor) {
      try {
        long type = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_TYPE));
        String body = cursor.getString(cursor.getColumnIndexOrThrow(SNIPPET));

        if (!TextUtils.isEmpty(body) && masterCipher != null && MessageColumns.Types.isSymmetricEncryption(type)) {
          return new DisplayRecord.Body(masterCipher.decryptBody(body), true);
        } else if (!TextUtils.isEmpty(body) && masterCipher == null && MessageColumns.Types.isSymmetricEncryption(type)) {
          return new DisplayRecord.Body(body, false);
        } else {
          return new DisplayRecord.Body(body, true);
        }
      } catch (InvalidMessageException e) {
        Log.w("ThreadDatabase", e);
        return new DisplayRecord.Body(context.getString(R.string.EncryptingSmsDatabase_error_decrypting_message), true);
      }
    }

    private @Nullable Uri getSnippetUri(Cursor cursor) {
      if (cursor.isNull(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_URI))) {
        return null;
      }

      try {
        return Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_URI)));
      } catch (IllegalArgumentException e) {
        Log.w(TAG, e);
        return null;
      }
    }

    public void close() {
      cursor.close();
    }
  }
}