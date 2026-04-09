/*
 * Copyright (C) 2014 Open Whisper Systems
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

package org.jimvixx.smsecure.util;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.components.AvatarImageView;
import org.jimvixx.smsecure.recipients.Recipient;

import java.util.ArrayList;

/**
 * ArrayAdapter for showing selected recipients with optional delete action and avatar.
 */
public class SelectedRecipientsAdapter extends ArrayAdapter<SelectedRecipientsAdapter.RecipientWrapper> {

  private final ArrayList<RecipientWrapper> recipients;
  private OnRecipientDeletedListener onRecipientDeletedListener;

  public SelectedRecipientsAdapter(@NonNull Context context, int textViewResourceId) {
    super(context, textViewResourceId);
    this.recipients = new ArrayList<>();
  }

  public SelectedRecipientsAdapter(@NonNull Context context,
                                   int resource,
                                   @NonNull ArrayList<RecipientWrapper> recipients) {
    super(context, resource, recipients);
    this.recipients = recipients;
  }

  @NonNull
  @Override
  public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
    final ViewHolder holder;

    if (convertView == null) {
      convertView = LayoutInflater.from(getContext())
              .inflate(R.layout.selected_recipient_list_item, parent, false);
      holder = new ViewHolder(convertView);
      convertView.setTag(holder);
    } else {
      holder = (ViewHolder) convertView.getTag();
    }

    final RecipientWrapper wrapper = getItem(position);
    if (wrapper == null) return convertView;

    final Recipient recipient = wrapper.getRecipient();
    final boolean modifiable = wrapper.isModifiable();

    bindTexts(holder, recipient);
    bindAvatar(holder, recipient);
    bindDelete(holder, wrapper, modifiable);

    return convertView;
  }

  private void bindTexts(@NonNull ViewHolder holder, @Nullable Recipient recipient) {
    if (recipient != null) {
      holder.name.setText(recipient.getName());
      holder.phone.setText(recipient.getNumber());
    } else {
      holder.name.setText("");
      holder.phone.setText("");
    }
  }

  private void bindAvatar(@NonNull ViewHolder holder, @Nullable Recipient recipient) {
    // No quick contact in this list item by default.
    // Passing "false" avoids attaching a click handler that opens QuickContact / insert contact.
    holder.avatar.setAvatar(recipient, false);
  }

  private void bindDelete(@NonNull ViewHolder holder,
                          @NonNull RecipientWrapper wrapper,
                          boolean modifiable) {
    if (modifiable) {
      holder.delete.setVisibility(View.VISIBLE);
      holder.delete.setOnClickListener(v -> {
        if (onRecipientDeletedListener != null) {
          onRecipientDeletedListener.onRecipientDeleted(wrapper.getRecipient());
        }
        // Remove by object reference, not by position, to be safe with view recycling.
        recipients.remove(wrapper);
        notifyDataSetChanged();
      });
    } else {
      holder.delete.setVisibility(View.INVISIBLE);
      holder.delete.setOnClickListener(null);
    }
  }

  public void setOnRecipientDeletedListener(@Nullable OnRecipientDeletedListener listener) {
    onRecipientDeletedListener = listener;
  }

  public interface OnRecipientDeletedListener {
    void onRecipientDeleted(Recipient recipient);
  }

  private static final class ViewHolder {
    final AvatarImageView avatar;
    final TextView name;
    final TextView phone;
    final ImageButton delete;

    ViewHolder(@NonNull View root) {
      avatar = root.findViewById(R.id.avatar);
      name = root.findViewById(R.id.name);
      phone = root.findViewById(R.id.phone);
      delete = root.findViewById(R.id.delete);
    }
  }

  public static class RecipientWrapper {
    private final Recipient recipient;
    private final boolean modifiable;

    public RecipientWrapper(@NonNull final Recipient recipient, final boolean modifiable) {
      this.recipient = recipient;
      this.modifiable = modifiable;
    }

    public @NonNull Recipient getRecipient() {
      return recipient;
    }

    public boolean isModifiable() {
      return modifiable;
    }
  }
}