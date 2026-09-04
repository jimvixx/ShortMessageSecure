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
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.jimvixx.smsecure.crypto.MasterCipher;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.CursorRecyclerViewAdapter;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.ThreadDatabase;
import org.jimvixx.smsecure.database.model.ThreadRecord;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.Conversions;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * A CursorAdapter for building a list of conversation threads.
 */
public class ConversationListAdapter extends CursorRecyclerViewAdapter<ConversationListAdapter.ViewHolder> {

  private static final int MESSAGE_TYPE_SWITCH_ARCHIVE = 1;
  private static final int MESSAGE_TYPE_THREAD = 2;

  private final ThreadDatabase threadDatabase;
  private final MasterSecret masterSecret;
  private final MasterCipher masterCipher;
  private final LayoutInflater inflater;
  private final ItemClickListener clickListener;
  private final @NonNull MessageDigest digest;

  private final Set<Long> batchSet = Collections.synchronizedSet(new HashSet<>());
  private final LinkedList<Pair<Long, Recipients>> threadIdAndRecipients = new LinkedList<>();
  private boolean batchMode = false;

  public ConversationListAdapter(@NonNull Context context,
                                 @NonNull MasterSecret masterSecret,
                                 @Nullable Cursor cursor,
                                 @Nullable ItemClickListener clickListener) {
    super(context, cursor);
    try {
      this.masterSecret = masterSecret;
      this.masterCipher = new MasterCipher(masterSecret);
      this.threadDatabase = DatabaseFactory.getThreadDatabase(context);
      this.inflater = LayoutInflater.from(context);
      this.clickListener = clickListener;
      this.digest = MessageDigest.getInstance("SHA1");
      setHasStableIds(true);
    } catch (NoSuchAlgorithmException nsae) {
      throw new AssertionError("SHA-1 missing");
    }
  }

  @Override
  public long getItemId(@NonNull Cursor cursor) {
    ThreadRecord record = getThreadRecord(cursor);
    StringBuilder builder = new StringBuilder("" + record.getThreadId());

    for (long recipientId : record.getRecipients().getIds()) {
      builder.append("::").append(recipientId);
    }

    return Conversions.byteArrayToLong(digest.digest(builder.toString().getBytes()));
  }

  @Override
  public ViewHolder onCreateItemViewHolder(ViewGroup parent, int viewType) {
    if (viewType == MESSAGE_TYPE_SWITCH_ARCHIVE) {
      ConversationListItemArchived action =
              (ConversationListItemArchived) inflater.inflate(R.layout.conversation_list_item_archived, parent, false);
      return new ViewHolder(action);
    } else {
      ConversationListItem item =
              (ConversationListItem) inflater.inflate(R.layout.conversation_list_item_view, parent, false);
      return new ViewHolder(item);
    }
  }

  @Override
  public void onItemViewRecycled(ViewHolder holder) {
    holder.getItem().unbind();

    holder.itemView.setOnClickListener(null);
    holder.itemView.setOnLongClickListener(null);
  }

  @Override
  public void onBindItemViewHolder(@NonNull ViewHolder viewHolder, @NonNull Cursor cursor) {
    final ThreadRecord record = getThreadRecord(cursor);

    viewHolder.getItem().bind(masterSecret, record, batchSet, batchMode);

    if (clickListener == null) {
      viewHolder.itemView.setOnClickListener(null);
      viewHolder.itemView.setOnLongClickListener(null);
      return;
    }

    if (getItemViewType(cursor) == MESSAGE_TYPE_SWITCH_ARCHIVE) {
      viewHolder.itemView.setOnLongClickListener(null);
      viewHolder.itemView.setOnClickListener(v -> clickListener.onSwitchToArchive());
    } else {
      final ConversationListItem item = (ConversationListItem) viewHolder.itemView;

      viewHolder.itemView.setOnClickListener(v -> {
        int pos = viewHolder.getBindingAdapterPosition();
        if (pos == RecyclerView.NO_POSITION) return;
        clickListener.onItemClick(item, pos);
      });

      viewHolder.itemView.setOnLongClickListener(v -> {
        int pos = viewHolder.getBindingAdapterPosition();
        if (pos == RecyclerView.NO_POSITION) return true;
        clickListener.onItemLongClick(item, pos);
        return true;
      });
    }
  }

  @Override
  public int getItemViewType(@NonNull Cursor cursor) {
    ThreadRecord threadRecord = getThreadRecord(cursor);

    if (threadRecord.getDistributionType() == ThreadDatabase.DistributionTypes.ARCHIVE) {
      return MESSAGE_TYPE_SWITCH_ARCHIVE;
    } else {
      return MESSAGE_TYPE_THREAD;
    }
  }

  private ThreadRecord getThreadRecord(@NonNull Cursor cursor) {
    return threadDatabase.readerFor(cursor, masterCipher).getCurrent();
  }

  public void toggleThreadInBatchSet(long threadId) {
    if (batchSet.contains(threadId)) {
      batchSet.remove(threadId);
    } else if (threadId != -1) {
      batchSet.add(threadId);
    }
  }

  public void populateRecipients(long threadId, Recipients recipients) {
    threadIdAndRecipients.add(new Pair<>(threadId, recipients));
  }

  public @Nullable Recipients getRecipientsFromThreadId(long threadId) {
    for (Pair<Long, Recipients> pair : threadIdAndRecipients) {
      if (threadId == pair.first) return pair.second;
    }
    return null;
  }

  public Set<Long> getBatchSelections() {
    return batchSet;
  }

  public List<Long> getBatchSelectionsInDisplayOrder() {
    List<Long> result = new ArrayList<>();

    for (int i = 0; i < getItemCount(); i++) {
      ThreadRecord record = getThreadRecord(getCursorAtPositionOrThrow(i));
      if (batchSet.contains(record.getThreadId())) result.add(record.getThreadId());
    }

    return result;
  }

  public boolean areAllBatchSelectionsPinned() {
    int selectedCount = 0;
    int pinnedCount = 0;

    for (int i = 0; i < getItemCount(); i++) {
      ThreadRecord record = getThreadRecord(getCursorAtPositionOrThrow(i));
      if (!batchSet.contains(record.getThreadId())) continue;

      selectedCount++;
      if (record.isPinned()) pinnedCount++;
    }

    return shouldUnpinSelection(selectedCount, pinnedCount);
  }

  static boolean shouldUnpinSelection(int selectedCount, int pinnedCount) {
    return selectedCount > 0 && selectedCount == pinnedCount;
  }

  public void initializeBatchMode(boolean toggle) {
    this.batchMode = toggle;
    unselectAllThreads();
  }

  public void unselectAllThreads() {
    this.batchSet.clear();
    if (getItemCount() > 0) notifyItemRangeChanged(0, getItemCount());
  }

  public void selectAllThreads() {
    for (int i = 0; i < getItemCount(); i++) {
      long threadId = getThreadRecord(getCursorAtPositionOrThrow(i)).getThreadId();
      if (threadId != -1) batchSet.add(threadId);
    }
    if (getItemCount() > 0) notifyItemRangeChanged(0, getItemCount());
  }

  public interface ItemClickListener {
    void onItemClick(@NonNull ConversationListItem item, int position);

    void onItemLongClick(@NonNull ConversationListItem item, int position);

    void onSwitchToArchive();
  }

  public static class ViewHolder extends RecyclerView.ViewHolder {
    public <V extends View & BindableConversationListItem> ViewHolder(final @NonNull V itemView) {
      super(itemView);
    }

    public BindableConversationListItem getItem() {
      return (BindableConversationListItem) itemView;
    }
  }
}
