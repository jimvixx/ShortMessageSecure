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

package org.jimvixx.smsecure;

import android.content.Context;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;

import org.jimvixx.smsecure.color.MaterialColor;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.EncryptingSmsDatabase;
import org.jimvixx.smsecure.database.MessageDatabase;
import org.jimvixx.smsecure.database.SmsDatabase;
import org.jimvixx.smsecure.database.loaders.MessageDetailsLoader;
import org.jimvixx.smsecure.database.model.MessageRecord;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.notifications.MessageNotifier;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.AppExecutors;
import org.jimvixx.smsecure.util.DateUtils;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.Util;

import java.lang.ref.WeakReference;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Locale;

public class MessageDetailsActivity extends PassphraseRequiredActionBarActivity
        implements LoaderManager.LoaderCallbacks<Cursor>, Recipients.RecipientsModifiedListener {

  public static final String MASTER_SECRET_EXTRA = "master_secret";
  public static final String MESSAGE_ID_EXTRA = "message_id";
  public static final String THREAD_ID_EXTRA = "thread_id";
  public static final String TYPE_EXTRA = "type";
  public static final String RECIPIENTS_IDS_EXTRA = "recipients_ids";
  private static final String TAG = MessageDetailsActivity.class.getSimpleName();
  private MasterSecret masterSecret;
  private long threadId;

  private Toolbar toolbar;
  private ConversationItem conversationItem;
  private ViewGroup itemParent;
  private View metadataContainer;
  private TextView errorText;
  private TextView sentDate;
  private TextView receivedDate;
  private View receivedContainer;
  private TextView transport;
  private TextView toFrom;
  private ListView recipientsList;
  private LayoutInflater inflater;

  // Async control: ignore stale results.
  private int recipientsGen = 0;

  @Override
  public void onCreate(@Nullable Bundle bundle, @NonNull MasterSecret masterSecret) {
    setContentView(R.layout.message_details_activity);

    initializeResources();
    initializeToolbar();
    initializeActionBar();

    LoaderManager.getInstance(this).initLoader(0, null, this);
  }

  @Override
  protected void onResume() {
    super.onResume();

    setTitleOnToolbar(getString(R.string.AndroidManifest__message_details));
    MessageNotifier.setVisibleThread(threadId);
  }

  @Override
  protected void onPause() {
    super.onPause();
    MessageNotifier.setVisibleThread(-1L);
  }

  private void initializeToolbar() {
    toolbar = findViewById(R.id.toolbar);
    if (toolbar == null) return;

    setSupportActionBar(toolbar);

    ActionBar actionBar = getSupportActionBar();
    if (actionBar != null) {
      actionBar.setDisplayHomeAsUpEnabled(true);
      actionBar.setDisplayShowHomeEnabled(true);
    }
  }

  private void setTitleOnToolbar(@NonNull String title) {
    if (toolbar != null) {
      toolbar.setTitle(title);
      return;
    }

    ActionBar actionBar = getSupportActionBar();
    if (actionBar != null) {
      actionBar.setTitle(title);
    }
  }

  private void initializeActionBar() {
    // Home/up enable is done in initializeToolbar(), keep this safe for legacy.
    ActionBar actionBar = getSupportActionBar();
    if (actionBar != null) {
      actionBar.setDisplayHomeAsUpEnabled(true);
    }

    long[] ids = getIntent().getLongArrayExtra(RECIPIENTS_IDS_EXTRA);
    if (ids != null && ids.length > 0) {
      Recipients headerRecipients = RecipientFactory.getRecipientsForIds(this, ids, true);
      if (headerRecipients != null) {
        headerRecipients.addListener(this);
        setActionBarColor(headerRecipients.getColor());
      }
    }
  }

  private void setActionBarColor(@NonNull MaterialColor color) {
    int abColor = color.toActionBarColor(this);

    // With NoActionBar, the actual bar is our Toolbar.
    if (toolbar != null) {
      toolbar.setBackground(new ColorDrawable(abColor));
    } else {
      ActionBar actionBar = getSupportActionBar();
      if (actionBar != null) {
        actionBar.setBackgroundDrawable(new ColorDrawable(abColor));
      }
    }

    getWindow().setStatusBarColor(color.toStatusBarColor(this));
  }

  @Override
  public void onModified(final Recipients recipients) {
    Util.runOnMain(() -> {
      if (recipients != null) {
        setActionBarColor(recipients.getColor());
      }
    });
  }

  private void initializeResources() {
    inflater = LayoutInflater.from(this);

    recipientsList = findViewById(R.id.recipients_list);
    if (recipientsList == null) {
      // Defensive: layout mismatch
      Log.w(TAG, "recipients_list is null; finishing.");
      finish();
      return;
    }

    // IMPORTANT: recipientsList must be non-null before inflating header with it as parent.
    View header = inflater.inflate(R.layout.message_details_header, recipientsList, false);

    masterSecret = getIntent().getParcelableExtra(MASTER_SECRET_EXTRA);
    threadId = getIntent().getLongExtra(THREAD_ID_EXTRA, -1);

    itemParent = header.findViewById(R.id.item_container);
    metadataContainer = header.findViewById(R.id.metadata_container);
    errorText = header.findViewById(R.id.error_text);
    sentDate = header.findViewById(R.id.sent_time);
    receivedContainer = header.findViewById(R.id.received_container);
    receivedDate = header.findViewById(R.id.received_time);
    transport = header.findViewById(R.id.transport);
    toFrom = header.findViewById(R.id.tofrom);

    recipientsList.setHeaderDividersEnabled(false);
    recipientsList.addHeaderView(header, null, false);
  }

  private void updateTransport(@NonNull MessageRecord messageRecord) {
    final String transportText;
    if (messageRecord.isOutgoing() && messageRecord.isFailed()) {
      transportText = "-";
    } else if (messageRecord.isPending()) {
      transportText = getString(R.string.ConversationFragment_pending);
    } else {
      transportText = getString(R.string.SMS);
    }

    transport.setText(transportText);
  }

  private void updateTime(@NonNull Context context, @NonNull MessageRecord messageRecord) {
    boolean isSmsDeliveryReportsEnabled = SMSecurePreferences.isSmsDeliveryReportsEnabled(context);

    if (messageRecord.isPending() || messageRecord.isFailed()) {
      sentDate.setText("-");
      if (!isSmsDeliveryReportsEnabled) receivedContainer.setVisibility(View.GONE);
      receivedDate.setText("-");
      return;
    }

    Locale dateLocale = getCurrentLocale();
    SimpleDateFormat dateFormatter = DateUtils.getDetailedDateFormatter(this, dateLocale);
    sentDate.setText(dateFormatter.format(new Date(messageRecord.getDateSent())));

    if (!messageRecord.isOutgoing()) {
      receivedDate.setText(dateFormatter.format(new Date(messageRecord.getDateReceived())));
      return;
    }

    if (isSmsDeliveryReportsEnabled) {
      final String deliveryString;
      if (!messageRecord.isDelivered()) {
        deliveryString = getString(R.string.No);
      } else if (messageRecord.getDateDeliveryReceived() == 0) {
        deliveryString = getString(R.string.Yes);
      } else {
        deliveryString = dateFormatter.format(new Date(messageRecord.getDateDeliveryReceived()));
      }
      receivedDate.setText(deliveryString);
    } else {
      receivedContainer.setVisibility(View.GONE);
    }
  }

  private void updateRecipients(@NonNull MessageRecord messageRecord, @NonNull Recipients recipients) {
    final int toFromRes;
    if (messageRecord.isOutgoing()) {
      toFromRes = R.string.message_details_header__to;
    } else {
      toFromRes = R.string.message_details_header__from;
    }

    toFrom.setText(toFromRes);

    // Ensure view exists before binding.
    if (conversationItem != null) {
      conversationItem.bind(masterSecret, messageRecord, getCurrentLocale(),
              new HashSet<>(), recipients);
      conversationItem.hideClickForDetails();
    }

    recipientsList.setAdapter(new MessageDetailsRecipientAdapter(this, masterSecret, messageRecord, recipients));
  }

  private void inflateMessageViewIfAbsent(@NonNull MessageRecord messageRecord) {
    if (conversationItem != null) return;

    if (messageRecord.isGroupAction()) {
      conversationItem = (ConversationItem) inflater.inflate(R.layout.conversation_item_update, itemParent, false);
    } else if (messageRecord.isOutgoing()) {
      conversationItem = (ConversationItem) inflater.inflate(R.layout.conversation_item_sent, itemParent, false);
    } else {
      conversationItem = (ConversationItem) inflater.inflate(R.layout.conversation_item_received, itemParent, false);
    }

    itemParent.addView(conversationItem);
  }

  @Nullable
  private MessageRecord getMessageRecord(@NonNull Context context,
                                         @NonNull Cursor cursor,
                                         @NonNull String type) {
    if (type.equals(MessageDatabase.SMS_TRANSPORT)) {
      EncryptingSmsDatabase smsDatabase = DatabaseFactory.getEncryptingSmsDatabase(context);
      SmsDatabase.Reader reader = smsDatabase.readerFor(masterSecret, cursor);
      return reader.getNext();
    }
    throw new AssertionError("No valid message type specified");
  }

  @NonNull
  @Override
  public Loader<Cursor> onCreateLoader(int id, @Nullable Bundle args) {
    @Nullable String type = getIntent().getStringExtra(TYPE_EXTRA);
    if (type == null) {
      // Fail fast with a clear log; avoid passing null into loader.
      Log.w(TAG, "Missing TYPE_EXTRA; finishing.");
      finish();
      // Return a loader that yields empty cursor in a safe way.
      return new MessageDetailsLoader(this, MessageDatabase.SMS_TRANSPORT, -1);
    }

    long messageId = getIntent().getLongExtra(MESSAGE_ID_EXTRA, -1);
    return new MessageDetailsLoader(this, type, messageId);
  }

  @Override
  public void onLoadFinished(@NonNull Loader<Cursor> loader, @NonNull Cursor cursor) {
    @Nullable String type = getIntent().getStringExtra(TYPE_EXTRA);
    if (type == null) {
      Log.w(TAG, "Missing TYPE_EXTRA in onLoadFinished; finishing.");
      finish();
      return;
    }

    final MessageRecord messageRecord = getMessageRecord(this, cursor, type);
    if (messageRecord == null) {
      Log.w(TAG, "Message no longer exists; finishing activity.");
      finish();
      return;
    }

    loadRecipientsAsync(messageRecord);
  }

  @Override
  public void onLoaderReset(@NonNull Loader<Cursor> loader) {
    if (recipientsList != null) {
      recipientsList.setAdapter(null);
    }
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (super.onOptionsItemSelected(item)) return true;

    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }

    return false;
  }

  private void loadRecipientsAsync(@NonNull MessageRecord messageRecord) {
    final int myGen = ++recipientsGen;
    final WeakReference<MessageDetailsActivity> weakSelf = new WeakReference<>(this);

    AppExecutors.background().execute(() -> {
      MessageDetailsActivity self = weakSelf.get();
      if (self == null || self.isFinishing()) return;

      final Recipients recipients;
      recipients = messageRecord.getRecipients();

      AppExecutors.mainHandler().post(() -> {
        MessageDetailsActivity a = weakSelf.get();
        if (a == null || a.isFinishing()) return;
        if (myGen != a.recipientsGen) return; // stale result

        a.onRecipientsLoaded(messageRecord, recipients);
      });
    });
  }

  private void onRecipientsLoaded(@NonNull MessageRecord messageRecord, @Nullable Recipients recipients) {
    if (recipients == null) {
      Log.w(TAG, "recipients is null, finishing activity...");
      finish();
      return;
    }

    inflateMessageViewIfAbsent(messageRecord);
    updateRecipients(messageRecord, recipients);

    if (messageRecord.isFailed()) {
      errorText.setVisibility(View.VISIBLE);
      metadataContainer.setVisibility(View.GONE);
    } else {
      updateTransport(messageRecord);
      updateTime(this, messageRecord);
      errorText.setVisibility(View.GONE);
      metadataContainer.setVisibility(View.VISIBLE);
    }
  }
}
