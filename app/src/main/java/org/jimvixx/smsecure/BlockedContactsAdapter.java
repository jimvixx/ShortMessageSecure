/*
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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import org.jimvixx.smsecure.preferences.BlockedContactListItem;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;

final class BlockedContactsAdapter extends ListAdapter<BlockedContactsAdapter.Item, BlockedContactsAdapter.VH> {

  private static final DiffUtil.ItemCallback<Item> DIFF = new DiffUtil.ItemCallback<>() {
    @Override
    public boolean areItemsTheSame(@NonNull Item oldItem, @NonNull Item newItem) {
      return oldItem.recipientIds.equals(newItem.recipientIds);
    }

    @Override
    public boolean areContentsTheSame(@NonNull Item oldItem, @NonNull Item newItem) {
      return oldItem.recipientIds.equals(newItem.recipientIds);
    }
  };
  private final @NonNull Context context;
  private final @NonNull OnClickListener listener;

  BlockedContactsAdapter(@NonNull Context context, @NonNull OnClickListener listener) {
    super(DIFF);
    this.context = context.getApplicationContext();
    this.listener = listener;
    setHasStableIds(true);
  }

  @Override
  public long getItemId(int position) {
    return getItem(position).recipientIds.hashCode();
  }

  @NonNull
  @Override
  public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.blocked_contact_list_item, parent, false);
    return new VH(v);
  }

  @Override
  public void onBindViewHolder(@NonNull VH holder, int position) {
    Item item = getItem(position);

    Recipients recipients = RecipientFactory.getRecipientsForIds(context, item.recipientIds, true);

    holder.bind(recipients, listener);
  }

  interface OnClickListener {
    void onClick(@NonNull Recipients recipients);
  }

  static final class VH extends RecyclerView.ViewHolder {
    VH(@NonNull View itemView) {
      super(itemView);
    }

    void bind(@NonNull Recipients recipients, @NonNull OnClickListener listener) {
      if (itemView instanceof BlockedContactListItem) {
        ((BlockedContactListItem) itemView).set(recipients);
      }
      itemView.setOnClickListener(v -> listener.onClick(recipients));
    }
  }

  static final class Item {
    final @NonNull String recipientIds;

    Item(@NonNull String recipientIds) {
      this.recipientIds = recipientIds;
    }
  }
}
