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

package org.jimvixx.smsecure.database;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.jimvixx.smsecure.ConversationItem;
import org.jimvixx.smsecure.ConversationListItem;
import org.jimvixx.smsecure.ConversationAdapter;
import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.model.DisplayRecord;
import org.jimvixx.smsecure.database.model.MessageRecord;
import org.jimvixx.smsecure.database.model.MmsMessageRecord;
import org.jimvixx.smsecure.database.model.SmsMessageRecord;
import org.jimvixx.smsecure.database.model.ThreadRecord;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.Recipients;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class MmsConversationUiSmokeTest {
  private Context context;
  private Context themedContext;
  private File databaseFile;
  private SQLiteOpenHelper helper;
  private SQLiteDatabase database;
  private Recipients recipients;
  private Recipient recipient;
  private MasterSecret masterSecret;

  @Before public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    themedContext = new ContextThemeWrapper(context, R.style.Theme_SMSecure);
    databaseFile = new File(context.getCacheDir(), "mms-ui-smoke-" + System.nanoTime() + ".db");
    helper = isolatedHelper(databaseFile);
    database = helper.getWritableDatabase();
    recipient = Recipient.getUnknownRecipient();
    recipients = syntheticRecipients(recipient);
    masterSecret = new MasterSecret(
        new SecretKeySpec(new byte[16], "AES"),
        new SecretKeySpec(new byte[20], "HmacSHA1"));
    seedFixture(database);
  }

  @After public void tearDown() {
    if (helper != null) helper.close();
    if (databaseFile != null) {
      deleteIfPresent(databaseFile);
      deleteIfPresent(new File(databaseFile.getPath() + "-journal"));
      deleteIfPresent(new File(databaseFile.getPath() + "-wal"));
      deleteIfPresent(new File(databaseFile.getPath() + "-shm"));
    }
  }

  @Test public void syntheticConversationsShowSnippetsAndMixedMessagesInDateOrder() throws Exception {
    assertTrue(databaseFile.getAbsolutePath().startsWith(context.getCacheDir().getAbsolutePath()));
    assertFalse(databaseFile.getName().equals("messages.db"));
    assertEquals(1, scalar("SELECT COUNT(*) FROM sms WHERE thread_id = 101"));
    assertEquals(2, scalar("SELECT COUNT(*) FROM mms WHERE thread_id = 102"));
    assertEquals(1, scalar("SELECT COUNT(*) FROM sms WHERE _id = 5 AND thread_id = 103"));
    assertEquals(1, scalar("SELECT COUNT(*) FROM mms WHERE _id = 5 AND thread_id = 103"));

    try (Cursor cursor = new MessageDatabase(context, helper).getUnifiedConversation(103)) {
      assertTrue(cursor.moveToNext());
      assertEquals(MessageDatabase.MMS_TRANSPORT,
          cursor.getString(cursor.getColumnIndexOrThrow(MessageDatabase.TRANSPORT)));
      assertEquals(5, cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
      assertEquals(7000, cursor.getLong(cursor.getColumnIndexOrThrow("date_received")));
      assertTrue(cursor.moveToNext());
      assertEquals(MessageDatabase.SMS_TRANSPORT,
          cursor.getString(cursor.getColumnIndexOrThrow(MessageDatabase.TRANSPORT)));
      assertEquals(5, cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
      assertEquals(6000, cursor.getLong(cursor.getColumnIndexOrThrow("date_received")));
      assertFalse(cursor.moveToNext());
    }

    assertEquals("SMS-only preview", bindListRow(101).toString());
    assertEquals("Outgoing MMS text", bindListRow(102).toString());
    assertEquals("Newest mixed MMS", bindListRow(103).toString());
  }

  @Test public void mmsBubblesRenderDirectionAttachmentAndEncryptedPlaceholder() throws Exception {
    assertEquals(1, scalar("SELECT COUNT(*) FROM part WHERE mid = 30 AND ct = 'image/jpeg'"));
    try (Cursor cursor = new MessageDatabase(context, helper).getUnifiedConversation(104)) {
      assertTrue(cursor.moveToFirst());
      assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("mms_has_attachments")));
    }

    runOnMainSync(() -> {
      SmsMessageRecord sms = new SmsMessageRecord(context, 1,
          new DisplayRecord.Body("SMS-only message", true), recipients, recipient, 1,
          3000, 3000, 0, 23, 101, SmsDatabase.Status.STATUS_NONE,
          Collections.emptyList(), -1);
      ConversationItem smsBubble = bindBubble(sms);
      assertEquals("SMS-only message", bodyText(smsBubble));
      assertEquals(View.GONE, smsBubble.findViewById(R.id.mms_image_previews).getVisibility());
      assertTrue(sms.isOutgoing());
      assertTrue(isAlignedToEnd(smsBubble));
      AtomicInteger smsClicks = new AtomicInteger();
      AtomicInteger smsLongClicks = new AtomicInteger();
      smsBubble.setOnClickListener(view -> smsClicks.incrementAndGet());
      smsBubble.setOnLongClickListener(view -> {
        smsLongClicks.incrementAndGet();
        return true;
      });
      attachToParent(smsBubble);
      smsBubble.findViewById(R.id.conversation_item_body).performClick();
      assertTrue(smsBubble.findViewById(R.id.conversation_item_body).performLongClick());
      assertEquals(1, smsClicks.get());
      assertEquals(1, smsLongClicks.get());

      MmsMessageRecord incoming = mmsRecord(20, 102, 3000, "Incoming MMS text", 20, false,
          new DisplayRecord.Body("Incoming MMS text", true));
      ConversationItem incomingBubble = bindBubble(incoming);
      assertEquals("Incoming MMS text", bodyText(incomingBubble));
      assertFalse(incoming.isOutgoing());
      assertFalse(isAlignedToEnd(incomingBubble));

      MmsMessageRecord outgoing = mmsRecord(21, 102, 4000, "Outgoing MMS text", 23, false,
          new DisplayRecord.Body("Outgoing MMS text", true));
      ConversationItem outgoingBubble = bindBubble(outgoing);
      assertEquals("Outgoing MMS text", bodyText(outgoingBubble));
      assertTrue(outgoing.isOutgoing());
      assertTrue(isAlignedToEnd(outgoingBubble));

      MmsMessageRecord withAttachment = mmsRecord(30, 104, 5000, "Photo attached", 20, true,
          new DisplayRecord.Body("Photo attached", true));
      ConversationItem attachmentBubble = bindBubble(withAttachment);
      ImageView attachment = attachmentBubble.findViewById(R.id.attachment_indicator);
      assertNotNull(attachment);
      assertEquals(View.VISIBLE, attachment.getVisibility());
      View previewContainer = attachmentBubble.findViewById(R.id.mms_image_previews);
      assertEquals(View.VISIBLE, previewContainer.getVisibility());
      assertEquals(1, ((ViewGroup) previewContainer).getChildCount());
      assertEquals(context.getString(R.string.conversation_item__image_preview_loading),
          ((View) ((ViewGroup) previewContainer).getChildAt(0)).getContentDescription());

      attachmentBubble.unbind();
      attachmentBubble.bind(masterSecret, syntheticSms(61, "recycled SMS row"), Locale.US,
          Collections.emptySet(), recipients);
      assertEquals(View.GONE, previewContainer.getVisibility());
      assertEquals(0, ((ViewGroup) previewContainer).getChildCount());

      MmsMessageRecord encrypted = mmsRecord(40, 105, 6000, "synthetic-ciphertext", 0x80000014,
          false, new DisplayRecord.Body("synthetic-ciphertext", false));
      ConversationItem encryptedBubble = bindBubble(encrypted);
      assertFalse(encrypted.getBody().isPlaintext());
      assertFalse(bodyText(encryptedBubble).contains("synthetic-ciphertext"));
      assertEquals(context.getString(R.string.MessageNotifier_encrypted_message), bodyText(encryptedBubble));
    });
  }

  @Test public void failedMmsDoesNotOfferOrInvokeSmsDetailsActions() throws Exception {
    runOnMainSync(() -> {
      MmsMessageRecord failed = mmsRecord(50, 106, 7000, "failed synthetic MMS", 24, false,
          new DisplayRecord.Body("failed synthetic MMS", true));
      assertTrue(failed.isFailed());

      ConversationItem bubble = bindBubble(failed);
      attachToParent(bubble);
      TextView detailsIndicator = bubble.findViewById(R.id.indicator_text);
      assertNotNull(detailsIndicator);
      assertEquals(View.GONE, detailsIndicator.getVisibility());

      AtomicInteger smsActions = new AtomicInteger();
      bubble.setOnClickListener(view -> smsActions.incrementAndGet());
      bubble.performClick();
      bubble.findViewById(R.id.conversation_item_body).performClick();
      assertTrue(bubble.findViewById(R.id.conversation_item_body).performLongClick());
      assertEquals(0, smsActions.get());

      ConversationAdapter<?> adapter = isolatedConversationAdapter();
      adapter.toggleSelection(failed, 0);
      assertTrue(adapter.getSelectedItems().isEmpty());

      SmsMessageRecord sms = syntheticSms(60, "ordinary synthetic SMS");
      adapter.toggleSelection(sms, 1);
      assertTrue(adapter.getSelectedItems().contains(sms));
    });
  }

  private CharSequence bindListRow(long threadId) throws Exception {
    try (Cursor cursor = database.query("thread", new String[]{"snippet", "date", "message_count",
        "read", "status", "snippet_type", "type", "archived", "last_seen", "pinned_order"},
        "_id = ?", new String[]{String.valueOf(threadId)}, null, null, null)) {
      assertTrue(cursor.moveToFirst());
      ThreadRecord record = new ThreadRecord(context,
          new DisplayRecord.Body(cursor.getString(0), true), null, recipients,
          cursor.getLong(1), cursor.getLong(2), cursor.getInt(3) != 0, threadId,
          cursor.getInt(4), cursor.getLong(5), cursor.getInt(6), cursor.getInt(7) != 0,
          cursor.getLong(8), cursor.getLong(9));
      AtomicReference<String> snippet = new AtomicReference<>();
      runOnMainSync(() -> {
        assertMainThread();
        ConversationListItem item = (ConversationListItem) LayoutInflater.from(themedContext)
            .inflate(R.layout.conversation_list_item_view, null, false);
        try {
          item.bind(masterSecret, record, Collections.emptySet(), false);
          snippet.set(((TextView) item.findViewById(R.id.subject)).getText().toString());
        } finally {
          item.unbind();
        }
      });
      return snippet.get();
    }
  }

  private ConversationItem bindBubble(MmsMessageRecord record) {
    return bindBubble((MessageRecord) record, record.isOutgoing());
  }

  private ConversationItem bindBubble(SmsMessageRecord record) {
    return bindBubble((MessageRecord) record, record.isOutgoing());
  }

  private ConversationItem bindBubble(MessageRecord record, boolean outgoing) {
    assertMainThread();
    int layout = outgoing ? R.layout.conversation_item_sent : R.layout.conversation_item_received;
    ConversationItem item = (ConversationItem) LayoutInflater.from(themedContext).inflate(layout, null, false);
    item.bind(masterSecret, record, Locale.US, Collections.emptySet(), recipients);
    return item;
  }

  private ConversationAdapter<?> isolatedConversationAdapter() throws Exception {
    Constructor<?> constructor = ConversationAdapter.class.getDeclaredConstructor(Context.class, Cursor.class);
    constructor.setAccessible(true);
    return (ConversationAdapter<?>) constructor.newInstance(context, null);
  }

  private SmsMessageRecord syntheticSms(long id, String body) {
    return new SmsMessageRecord(context, id, new DisplayRecord.Body(body, true), recipients,
        recipient, 1, 8000, 8000, 0, 23, 106, SmsDatabase.Status.STATUS_NONE,
        Collections.emptyList(), -1);
  }

  private String bodyText(ConversationItem item) {
    return ((TextView) item.findViewById(R.id.conversation_item_body)).getText().toString();
  }

  private boolean isAlignedToEnd(ConversationItem item) {
    View bubble = item.findViewById(R.id.body_bubble);
    RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) bubble.getLayoutParams();
    return params.getRule(RelativeLayout.ALIGN_PARENT_END) != 0 ||
        params.getRule(RelativeLayout.ALIGN_PARENT_RIGHT) != 0;
  }

  private void attachToParent(ConversationItem item) {
    assertMainThread();
    FrameLayout parent = new FrameLayout(themedContext);
    parent.addView(item, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    parent.measure(
        View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST));
    parent.layout(0, 0, parent.getMeasuredWidth(), parent.getMeasuredHeight());
  }

  private void runOnMainSync(ThrowingRunnable action) throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      try {
        assertMainThread();
        action.run();
      } catch (Throwable throwable) {
        failure.set(throwable);
      }
    });

    Throwable throwable = failure.get();
    if (throwable instanceof AssertionError) throw (AssertionError) throwable;
    if (throwable instanceof Exception) throw (Exception) throwable;
    if (throwable instanceof Error) throw (Error) throwable;
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private void assertMainThread() {
    assertEquals(Looper.getMainLooper(), Looper.myLooper());
  }

  private MmsMessageRecord mmsRecord(long id, long threadId, long timestamp, String body,
                                     long type, boolean hasAttachments, DisplayRecord.Body displayBody) {
    return new MmsMessageRecord(context, id, displayBody, recipients, recipient, 1,
        timestamp, timestamp, 0, threadId, type, SmsDatabase.Status.STATUS_NONE,
        hasAttachments ? 1 : 0, null, 0, 0, "synthetic-transaction", false,
        0, -1, hasAttachments);
  }

  private int scalar(String sql) {
    try (Cursor cursor = database.rawQuery(sql, null)) {
      return cursor.moveToFirst() ? cursor.getInt(0) : 0;
    }
  }

  private void seedFixture(SQLiteDatabase db) {
    insertThread(db, 101, "SMS-only preview", 1, 3000, 20);
    insertThread(db, 102, "Outgoing MMS text", 2, 4000, 23);
    insertThread(db, 103, "Newest mixed MMS", 2, 7000, 20);
    insertThread(db, 104, "Photo attached", 1, 5000, 20);
    insertThread(db, 105, "Encrypted MMS", 1, 6000, 0x80000014);
    insertThread(db, 106, "Failed MMS", 1, 7000, 24);

    insertSms(db, 1, 101, 3000, "SMS-only message");
    insertSms(db, 5, 103, 6000, "Older mixed SMS");
    insertMms(db, 20, 102, 3000, 20, "Incoming MMS text", 0);
    insertMms(db, 21, 102, 4000, 23, "Outgoing MMS text", 0);
    insertMms(db, 5, 103, 7000, 20, "Newest mixed MMS", 0);
    insertMms(db, 30, 104, 5000, 20, "Photo attached", 0);
    insertMms(db, 40, 105, 6000, 0x80000014, "synthetic-ciphertext", 0);
    insertMms(db, 50, 106, 7000, 24, "failed synthetic MMS", 0);
    db.execSQL("INSERT INTO part (_id, mid, seq, ct, _data, data_size, encrypted) " +
        "VALUES (300, 30, 0, 'image/jpeg', '/synthetic/never-create.jpg', 321, 1)");
  }

  private void insertThread(SQLiteDatabase db, long id, String snippet, int count,
                            long date, long snippetType) {
    db.execSQL("INSERT INTO thread (_id, date, message_count, recipient_ids, snippet, read, type, " +
        "snippet_type, archived, status, last_seen, pinned_order) " +
        "VALUES (?, ?, ?, '', ?, 1, 0, ?, 0, 0, 0, 0)",
        new Object[]{id, date, count, snippet, snippetType});
  }

  private void insertSms(SQLiteDatabase db, long id, long threadId, long date, String body) {
    db.execSQL("INSERT INTO sms (_id, thread_id, date, date_sent, read, status, type, body, address, address_device_id) " +
        "VALUES (?, ?, ?, ?, 1, 0, 20, ?, NULL, 1)", new Object[]{id, threadId, date, date, body});
  }

  private void insertMms(SQLiteDatabase db, long id, long threadId, long date,
                         long messageBox, String body, int status) {
    db.execSQL("INSERT INTO mms (_id, thread_id, date, date_received, msg_box, read, m_type, body, " +
        "address, part_count, st, tr_id) VALUES (?, ?, ?, ?, ?, 1, 132, ?, NULL, 1, ?, 'synthetic')",
        new Object[]{id, threadId, date, date, messageBox, body, status});
  }

  private SQLiteOpenHelper isolatedHelper(File file) throws Exception {
    Class<?> helperClass = DatabaseFactory.class.getDeclaredClasses()[0];
    Constructor<?> constructor = helperClass.getDeclaredConstructor(Context.class, String.class,
        SQLiteDatabase.CursorFactory.class, int.class);
    constructor.setAccessible(true);
    return (SQLiteOpenHelper) constructor.newInstance(context, file.getAbsolutePath(), null, 38);
  }

  private Recipients syntheticRecipients(Recipient syntheticRecipient) throws Exception {
    for (Constructor<?> constructor : Recipients.class.getDeclaredConstructors()) {
      Class<?>[] parameters = constructor.getParameterTypes();
      if (parameters.length == 2 && java.util.List.class.isAssignableFrom(parameters[0])) {
        constructor.setAccessible(true);
        return (Recipients) constructor.newInstance(Collections.singletonList(syntheticRecipient), null);
      }
    }
    throw new IllegalStateException("No synthetic Recipients constructor found");
  }

  private void deleteIfPresent(File file) {
    if (file.exists() && !file.delete()) {
      throw new AssertionError("Unable to remove synthetic smoke-test database artifact");
    }
  }
}
