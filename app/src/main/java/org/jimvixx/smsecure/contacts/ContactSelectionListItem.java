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
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.components.AvatarImageView;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.ViewUtil;

public class ContactSelectionListItem extends LinearLayout implements Recipients.RecipientsModifiedListener {

  private AvatarImageView contactPhotoImage;
  private TextView numberView;
  private TextView nameView;
  private TextView labelView;
  private CheckBox checkBox;

  private long id;
  private String number;
  private Recipients recipients;

  public ContactSelectionListItem(Context context) {
    super(context);
  }

  public ContactSelectionListItem(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();

    this.contactPhotoImage = findViewById(R.id.contact_photo_image);
    this.numberView = findViewById(R.id.number);
    this.labelView = findViewById(R.id.label);
    this.nameView = findViewById(R.id.name);
    this.checkBox = findViewById(R.id.check_box);

    ViewUtil.setTextViewGravityStart(this.nameView, getContext());
  }

  public void set(long id, int type, String name, String number, String label, int color, boolean multiSelect) {
    this.id = id;
    this.number = number;

    if (type == ContactsDatabase.NEW_TYPE) {
      this.recipients = null;
      this.contactPhotoImage.setAvatar(Recipient.getUnknownRecipient(), false);
    } else if (!TextUtils.isEmpty(number)) {
      this.recipients = RecipientFactory.getRecipientsFromString(getContext(), number, true);

      this.recipients.getPrimaryRecipient();
      if (this.recipients.getPrimaryRecipient().getName() != null) {
        name = this.recipients.getPrimaryRecipient().getName();
      }

      this.recipients.addListener(this);
      this.contactPhotoImage.setAvatar(recipients, false);
    } else {
      this.recipients = null;
      this.contactPhotoImage.setAvatar(Recipient.getUnknownRecipient(), false);
    }

    this.nameView.setTextColor(color);
    this.numberView.setTextColor(color);

    setText(type, name, number, label);

    this.checkBox.setVisibility(multiSelect ? View.VISIBLE : View.GONE);
  }

  public void setChecked(boolean selected) {
    this.checkBox.setChecked(selected);
  }

  public void unbind() {
    if (recipients != null) {
      recipients.removeListener(this);
      recipients = null;
    }
  }

  private void setText(int type, String name, String number, String label) {
    String safeNumber = (number == null) ? "" : number.trim();
    String safeName = (name == null) ? "" : name.trim();

    // If we don't have a name, fall back to number.
    if (TextUtils.isEmpty(safeName)) {
      safeName = safeNumber;
    }

    nameView.setEnabled(!TextUtils.isEmpty(safeNumber));
    nameView.setText(safeName);

    if (TextUtils.isEmpty(safeNumber)) {
      // No number => hide second line completely.
      numberView.setText("");
      labelView.setVisibility(View.GONE);
      return;
    }

    // Number is always shown for non-push contacts too.
    numberView.setText(safeNumber);

    if (type == ContactsDatabase.PUSH_TYPE || TextUtils.isEmpty(label)) {
      labelView.setVisibility(View.GONE);
    } else {
      labelView.setText(label);
      labelView.setVisibility(View.VISIBLE);
    }
  }

  public long getContactId() {
    return id;
  }

  public String getNumber() {
    return number;
  }

  @Override
  public void onModified(final Recipients recipients) {
    if (this.recipients == recipients) {
      this.contactPhotoImage.post(() -> {
        contactPhotoImage.setAvatar(recipients, false);

        // Keep the resolved recipient name if available.
        String resolved = recipients.toShortString();
        if (!TextUtils.isEmpty(resolved)) {
          nameView.setText(resolved);
        } else if (!TextUtils.isEmpty(number)) {
          nameView.setText(number);
        }
      });
    }
  }
}
