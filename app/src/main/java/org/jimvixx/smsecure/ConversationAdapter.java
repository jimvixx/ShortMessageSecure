/*
 * Copyright (C) 2011 Whisper Systems
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
import android.util.LongSparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.RecyclerView;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.CursorRecyclerViewAdapter;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.MessageColumns;
import org.jimvixx.smsecure.database.MessageDatabase;
import org.jimvixx.smsecure.database.model.MessageRecord;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.Conversions;
import org.jimvixx.smsecure.util.DateUtils;
import org.jimvixx.smsecure.util.LRUCache;
import org.jimvixx.smsecure.util.StickyHeaderDecoration;
import org.jimvixx.smsecure.util.Util;
import org.jimvixx.smsecure.util.ViewUtil;

import java.lang.ref.SoftReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ConversationAdapter<V extends View & BindableConversationItem>
        extends CursorRecyclerViewAdapter<ConversationAdapter.ViewHolder>
        implements StickyHeaderDecoration.StickyHeaderAdapter<ConversationAdapter.HeaderViewHolder>,
        AutoCloseable {

  private static final int MAX_CACHE_SIZE = 40;
  private static final int MESSAGE_TYPE_OUTGOING = 0;
  private static final int MESSAGE_TYPE_INCOMING = 1;
  private static final int MESSAGE_TYPE_UPDATE = 2;
  private static final int MESSAGE_TYPE_AUDIO_OUTGOING = 3;
  private static final int MESSAGE_TYPE_AUDIO_INCOMING = 4;

  private final Map<String, SoftReference<MessageRecord>> messageRecordCache =
          Collections.synchronizedMap(new LRUCache<>(MAX_CACHE_SIZE));

  // selection state
  private final Set<MessageRecord> batchSelected =
          Collections.synchronizedSet(new HashSet<>());

  // key -> last known adapter position (for notifyItemChanged on exit)
  private final LongSparseArray<Integer> selectedPositions = new LongSparseArray<>();

  private final @Nullable ItemClickListener clickListener;
  private final @NonNull MasterSecret masterSecret;
  private final @NonNull Locale locale;
  private final @NonNull Recipients recipients;
  private final @NonNull MessageDatabase db;
  private final @NonNull LayoutInflater inflater;
  private final @NonNull Calendar calendar;
  private final @NonNull MessageDigest digest;

  @SuppressWarnings("ConstantConditions")
  @VisibleForTesting
  ConversationAdapter(@NonNull Context context, @Nullable Cursor cursor) {
    super(context, cursor);
    try {
      // test-only; not used by non-binding tests
      this.masterSecret = null;
      this.locale = null;
      this.clickListener = null;
      this.recipients = null;
      this.inflater = null;
      this.db = null;

      this.calendar = Calendar.getInstance();
      this.digest = MessageDigest.getInstance("SHA1");
    } catch (NoSuchAlgorithmException nsae) {
      throw new AssertionError("SHA1 isn't supported!");
    }
  }

  public ConversationAdapter(@NonNull Context context,
                             @NonNull MasterSecret masterSecret,
                             @NonNull Locale locale,
                             @Nullable ItemClickListener clickListener,
                             @Nullable Cursor cursor,
                             @NonNull Recipients recipients) {
    super(context, cursor);
    try {
      this.masterSecret = masterSecret;
      this.locale = locale;
      this.clickListener = clickListener;
      this.recipients = recipients;
      this.inflater = LayoutInflater.from(context);
      this.db = DatabaseFactory.getMessageDatabase(context);
      this.calendar = Calendar.getInstance();
      this.digest = MessageDigest.getInstance("SHA1");
      setHasStableIds(true);
    } catch (NoSuchAlgorithmException nsae) {
      throw new AssertionError("SHA1 isn't supported!");
    }
  }

  @Override
  public void changeCursor(@Nullable Cursor cursor) {
    messageRecordCache.clear();
    super.changeCursor(cursor);
  }

  @Override
  public void onBindItemViewHolder(@NonNull ViewHolder viewHolder, @NonNull Cursor cursor) {
    MessageRecord messageRecord = getMessageRecord(cursor);

    // keep selection->position mapping fresh as RV recycles/binds
    long key = selectionKey(messageRecord);
    if (selectedPositions.get(key) != null) {
      int pos = viewHolder.getBindingAdapterPosition();
      if (pos != RecyclerView.NO_POSITION) {
        selectedPositions.put(key, pos);
      }
    }

    viewHolder.getView().bind(masterSecret, messageRecord, locale, batchSelected, recipients);
  }

  @Override
  public @NonNull ViewHolder onCreateItemViewHolder(@NonNull ViewGroup parent, int viewType) {
    final V itemView = ViewUtil.inflate(inflater, parent, getLayoutForViewType(viewType));
    final ViewHolder holder = new ViewHolder(itemView);

    // Clickable items: message bubbles (including audio bubbles)
    boolean clickable =
            viewType == MESSAGE_TYPE_INCOMING ||
                    viewType == MESSAGE_TYPE_OUTGOING ||
                    viewType == MESSAGE_TYPE_AUDIO_INCOMING ||
                    viewType == MESSAGE_TYPE_AUDIO_OUTGOING;

    if (clickable) {
      itemView.setOnClickListener(v -> {
        int pos = holder.getBindingAdapterPosition();
        if (pos == RecyclerView.NO_POSITION) return;
        if (clickListener != null) clickListener.onItemClick((ConversationItem) itemView, pos);
      });

      itemView.setOnLongClickListener(v -> {
        int pos = holder.getBindingAdapterPosition();
        if (pos == RecyclerView.NO_POSITION) return true;
        if (clickListener != null) clickListener.onItemLongClick((ConversationItem) itemView, pos);
        return true;
      });
    }

    return holder;
  }

  @Override
  public void onItemViewRecycled(@NonNull ViewHolder holder) {
    holder.getView().unbind();
  }

  private @LayoutRes int getLayoutForViewType(int viewType) {
    return switch (viewType) {
      case MESSAGE_TYPE_AUDIO_OUTGOING, MESSAGE_TYPE_OUTGOING -> R.layout.conversation_item_sent;
      case MESSAGE_TYPE_AUDIO_INCOMING, MESSAGE_TYPE_INCOMING ->
              R.layout.conversation_item_received;
      case MESSAGE_TYPE_UPDATE -> R.layout.conversation_item_update;
      default -> throw new IllegalArgumentException("Unsupported item view type: " + viewType);
    };
  }

  @Override
  public int getItemViewType(@NonNull Cursor cursor) {
    MessageRecord messageRecord = getMessageRecord(cursor);

    if (messageRecord.isGroupAction()) {
      return MESSAGE_TYPE_UPDATE;
    } else if (messageRecord.isOutgoing()) {
      return MESSAGE_TYPE_OUTGOING;
    } else {
      return MESSAGE_TYPE_INCOMING;
    }
  }

  @Override
  public long getItemId(@NonNull Cursor cursor) {
    final String unique = cursor.getString(cursor.getColumnIndexOrThrow(MessageColumns.UNIQUE_ROW_ID));
    final byte[] bytes = digest.digest(unique.getBytes(StandardCharsets.UTF_8));
    return Conversions.byteArrayToLong(bytes);
  }

  private @NonNull MessageRecord getMessageRecord(@NonNull Cursor cursor) {
    long messageId = cursor.getLong(cursor.getColumnIndexOrThrow(MessageColumns.ID));
    String type = cursor.getString(cursor.getColumnIndexOrThrow(MessageDatabase.TRANSPORT));

    final SoftReference<MessageRecord> reference = messageRecordCache.get(type + messageId);
    if (reference != null) {
      final MessageRecord record = reference.get();
      if (record != null) return record;
    }

    final MessageRecord messageRecord = db.readerFor(cursor, masterSecret).getCurrent();
    messageRecordCache.put(type + messageId, new SoftReference<>(messageRecord));
    return messageRecord;
  }

  @Override
  public void close() {
    Cursor cursor = getCursor();
    if (cursor != null && !cursor.isClosed()) cursor.close();
  }

  public int findLastSeenPosition(long lastSeen) {
    if (lastSeen <= 0) return -1;
    if (!isActiveCursor()) return -1;

    int count = getItemCount();
    for (int i = 0; i < count; i++) {
      Cursor cursor = getCursorAtPositionOrThrow(i);
      MessageRecord messageRecord = getMessageRecord(cursor);

      if (messageRecord.isOutgoing() || messageRecord.getDateReceived() <= lastSeen) {
        return i;
      }
    }
    return -1;
  }

  private long selectionKey(@NonNull MessageRecord r) {
    return (0L) | (r.getId() & 0x7fffffffffffffffL);
  }

  public void toggleSelection(@NonNull MessageRecord messageRecord, int adapterPosition) {
    long key = selectionKey(messageRecord);

    if (selectedPositions.get(key) != null) {
      selectedPositions.remove(key);
      batchSelected.remove(messageRecord);
    } else {
      selectedPositions.put(key, adapterPosition);
      batchSelected.add(messageRecord);
    }
  }

  public @NonNull int[] clearSelectionAndGetPositions() {
    int size = selectedPositions.size();
    int[] positions = new int[size];

    for (int i = 0; i < size; i++) {
      Integer p = selectedPositions.valueAt(i);
      positions[i] = (p != null) ? p : RecyclerView.NO_POSITION;
    }

    selectedPositions.clear();
    batchSelected.clear();
    return positions;
  }

  public @NonNull Set<MessageRecord> getSelectedItems() {
    return Set.copyOf(batchSelected);
  }

  @Override
  public long getHeaderId(int position) {
    if (!isActiveCursor()) return -1;
    if (isHeaderPosition(position)) return -1;
    if (isFooterPosition(position)) return -1;
    if (position >= getItemCount()) return -1;
    if (position < 0) return -1;

    Cursor cursor = getCursorAtPositionOrThrow(position);
    MessageRecord record = getMessageRecord(cursor);

    calendar.setTime(new Date(record.getDateSent()));
    return Util.hashCode(calendar.get(Calendar.YEAR), calendar.get(Calendar.DAY_OF_YEAR));
  }

  public long getReceivedTimestamp(int position) {
    if (!isActiveCursor()) return 0;
    if (isHeaderPosition(position)) return 0;
    if (isFooterPosition(position)) return 0;
    if (position >= getItemCount()) return 0;
    if (position < 0) return 0;

    Cursor cursor = getCursorAtPositionOrThrow(position);
    MessageRecord messageRecord = getMessageRecord(cursor);

    return messageRecord.isOutgoing() ? 0 : messageRecord.getDateReceived();
  }

  @Override
  public @NonNull HeaderViewHolder onCreateHeaderViewHolder(@NonNull ViewGroup parent) {
    return new HeaderViewHolder(LayoutInflater.from(getContext())
            .inflate(R.layout.conversation_item_header_date, parent, false));
  }

  // ---------------------------------------------------------------------------
  // StickyHeaderAdapter implementation
  // ---------------------------------------------------------------------------

  public @NonNull HeaderViewHolder onCreateLastSeenViewHolder(@NonNull ViewGroup parent) {
    return new HeaderViewHolder(LayoutInflater.from(getContext())
            .inflate(R.layout.conversation_item_last_seen, parent, false));
  }

  @Override
  public void onBindHeaderViewHolder(@NonNull HeaderViewHolder viewHolder, int position) {
    Cursor cursor = getCursorAtPositionOrThrow(position);
    viewHolder.setText(DateUtils.getRelativeDate(getContext(), locale, getMessageRecord(cursor).getDateReceived()));
  }

  public void onBindLastSeenViewHolder(@NonNull HeaderViewHolder viewHolder, int position) {
    viewHolder.setText(getContext().getResources().getQuantityString(
            R.plurals.ConversationAdapter_n_unread_messages, (position + 1), (position + 1)));
  }

  public interface ItemClickListener {
    void onItemClick(@NonNull ConversationItem item, int adapterPosition);

    void onItemLongClick(@NonNull ConversationItem item, int adapterPosition);
  }

  public static class ViewHolder extends RecyclerView.ViewHolder {
    public <V extends View & BindableConversationItem> ViewHolder(final @NonNull V itemView) {
      super(itemView);
    }

    @SuppressWarnings("unchecked")
    public <V extends View & BindableConversationItem> V getView() {
      return (V) itemView;
    }
  }

  public static class HeaderViewHolder extends RecyclerView.ViewHolder {
    private final TextView textView;

    HeaderViewHolder(@NonNull View itemView) {
      super(itemView);
      this.textView = ViewUtil.findById(itemView, R.id.text);
    }

    HeaderViewHolder(@NonNull TextView textView) {
      super(textView);
      this.textView = textView;
    }

    public void setText(@NonNull CharSequence text) {
      textView.setText(text);
    }

    /// Needed for header animations in ConversationFragment (Signal-like floating date header).
    public @NonNull TextView getTextView() {
      return textView;
    }
  }

  // ---------------------------------------------------------------------------
  // Last-seen decoration
  // ---------------------------------------------------------------------------

  static class LastSeenHeader extends StickyHeaderDecoration<HeaderViewHolder> {

    private final ConversationAdapter<?> adapter;
    private final long lastSeenTimestamp;

    LastSeenHeader(@NonNull ConversationAdapter<?> adapter, long lastSeenTimestamp) {
      super(adapter, false, false);
      this.adapter = adapter;
      this.lastSeenTimestamp = lastSeenTimestamp;
    }

    @Override
    protected boolean hasHeader(@NonNull RecyclerView parent,
                                @NonNull StickyHeaderAdapter<HeaderViewHolder> stickyAdapter,
                                int position) {
      if (!adapter.isActiveCursor()) return false;
      if (lastSeenTimestamp <= 0) return false;

      long currentRecordTimestamp = adapter.getReceivedTimestamp(position);
      long previousRecordTimestamp = adapter.getReceivedTimestamp(position + 1);

      return (currentRecordTimestamp > lastSeenTimestamp) && (previousRecordTimestamp < lastSeenTimestamp);
    }

    @Override
    protected int getHeaderTop(@NonNull RecyclerView parent,
                               @NonNull View child,
                               @NonNull View header,
                               int adapterPos,
                               int layoutPos) {
      RecyclerView.LayoutManager lm = parent.getLayoutManager();
      if (lm == null) return parent.getPaddingTop();
      return lm.getDecoratedTop(child);
    }

    @Override
    protected @NonNull HeaderViewHolder getHeader(@NonNull RecyclerView parent,
                                                  @NonNull StickyHeaderAdapter<HeaderViewHolder> stickyAdapter,
                                                  int position) {
      HeaderViewHolder viewHolder = adapter.onCreateLastSeenViewHolder(parent);
      adapter.onBindLastSeenViewHolder(viewHolder, position);

      int widthSpec = View.MeasureSpec.makeMeasureSpec(parent.getWidth(), View.MeasureSpec.EXACTLY);
      int heightSpec = View.MeasureSpec.makeMeasureSpec(parent.getHeight(), View.MeasureSpec.UNSPECIFIED);

      int childWidth = ViewGroup.getChildMeasureSpec(
              widthSpec,
              parent.getPaddingLeft() + parent.getPaddingRight(),
              viewHolder.itemView.getLayoutParams().width);

      int childHeight = ViewGroup.getChildMeasureSpec(
              heightSpec,
              parent.getPaddingTop() + parent.getPaddingBottom(),
              viewHolder.itemView.getLayoutParams().height);

      viewHolder.itemView.measure(childWidth, childHeight);
      viewHolder.itemView.layout(
              0, 0,
              viewHolder.itemView.getMeasuredWidth(),
              viewHolder.itemView.getMeasuredHeight());

      return viewHolder;
    }
  }
}
