/*
 * Copyright (C) 2011 Whisper Systems
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

package org.jimvixx.smsecure.contacts;

import android.content.Context;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.components.RecyclerViewFastScroller.FastScrollAdapter;
import org.jimvixx.smsecure.database.CursorRecyclerViewAdapter;
import org.jimvixx.smsecure.util.StickyHeaderDecoration.StickyHeaderAdapter;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class ContactSelectionListAdapter extends CursorRecyclerViewAdapter<ContactSelectionListAdapter.ViewHolder>
        implements FastScrollAdapter,
        StickyHeaderAdapter<ContactSelectionListAdapter.HeaderViewHolder> {
  @SuppressWarnings("unused")
  private static final String TAG = ContactSelectionListAdapter.class.getSimpleName();

  private final boolean multiSelect;
  private final LayoutInflater li;
  private final ItemClickListener clickListener;
  private final TypedArray attributes;

  private final HashMap<Long, String> selectedContacts = new HashMap<>();

  public ContactSelectionListAdapter(@NonNull Context context,
                                     @Nullable Cursor cursor,
                                     @Nullable ItemClickListener clickListener,
                                     boolean multiSelect) {
    super(context, cursor);
    this.li = LayoutInflater.from(context);
    this.multiSelect = multiSelect;
    this.clickListener = clickListener;
    this.attributes = context.obtainStyledAttributes(R.styleable.ContactSelection);
  }

  @Override
  public long getHeaderId(int position) {
    if (!isActiveCursor()) return -1;
    return getHeaderString(position).hashCode();
  }

  @Override
  public ViewHolder onCreateItemViewHolder(ViewGroup parent, int viewType) {
    return new ViewHolder(li.inflate(R.layout.contact_selection_list_item, parent, false), clickListener);
  }

  @Override
  public void onBindItemViewHolder(ViewHolder viewHolder, @NonNull Cursor cursor) {
    long id = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsDatabase.ID_COLUMN));
    int contactType = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsDatabase.CONTACT_TYPE_COLUMN));
    String name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsDatabase.NAME_COLUMN));
    String number = cursor.getString(cursor.getColumnIndexOrThrow(ContactsDatabase.NUMBER_COLUMN));
    int numberType = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsDatabase.NUMBER_TYPE_COLUMN));
    String label = cursor.getString(cursor.getColumnIndexOrThrow(ContactsDatabase.LABEL_COLUMN));

    String labelText = ContactsContract.CommonDataKinds.Phone
            .getTypeLabel(getContext().getResources(), numberType, label)
            .toString();

    int color = (contactType == ContactsDatabase.PUSH_TYPE)
            ? attributes.getColor(R.styleable.ContactSelection_contact_selection_push_user, 0xa0000000)
            : attributes.getColor(R.styleable.ContactSelection_contact_selection_lay_user, 0xff000000);

    viewHolder.getView().unbind();
    viewHolder.getView().set(id, contactType, name, number, labelText, color, multiSelect);
    viewHolder.getView().setChecked(selectedContacts.containsKey(id));
  }

  @NonNull
  @Override
  public HeaderViewHolder onCreateHeaderViewHolder(@NonNull ViewGroup parent) {
    return new HeaderViewHolder(
            LayoutInflater.from(getContext()).inflate(R.layout.contact_selection_recyclerview_header, parent, false)
    );
  }

  @Override
  public void onBindHeaderViewHolder(@NonNull HeaderViewHolder viewHolder, int position) {
    ((TextView) viewHolder.itemView).setText(getHeaderString(position));
  }

  @Override
  public CharSequence getBubbleText(int position) {
    return getHeaderString(position);
  }

  public Map<Long, String> getSelectedContacts() {
    return selectedContacts;
  }

  private @NonNull String getHeaderString(int position) {
    Cursor cursor = getCursorAtPositionOrThrow(position);
    String name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsDatabase.NAME_COLUMN));

    if (!TextUtils.isEmpty(name)) {
      String trimmedName = name.trim();

      if (!trimmedName.isEmpty()) {
        int firstCodePoint = trimmedName.codePointAt(0);
        int firstCharCount = Character.charCount(firstCodePoint);
        String firstChar = trimmedName.substring(0, firstCharCount).toUpperCase(Locale.getDefault());

        if (Character.isLetterOrDigit(firstChar.codePointAt(0))) {
          return firstChar;
        }
      }
    }

    return "#";
  }

  public void recycle() {
    attributes.recycle();
  }

  public interface ItemClickListener {
    void onItemClick(ContactSelectionListItem item);
  }

  public static class ViewHolder extends RecyclerView.ViewHolder {
    public ViewHolder(@NonNull final View itemView,
                      @Nullable final ItemClickListener clickListener) {
      super(itemView);
      itemView.setOnClickListener(v -> {
        if (clickListener != null) clickListener.onItemClick(getView());
      });
    }

    public ContactSelectionListItem getView() {
      return (ContactSelectionListItem) itemView;
    }
  }

  public static class HeaderViewHolder extends RecyclerView.ViewHolder {
    public HeaderViewHolder(View itemView) {
      super(itemView);
    }
  }
}
