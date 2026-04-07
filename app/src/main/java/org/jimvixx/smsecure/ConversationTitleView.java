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
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.ViewUtil;

public class ConversationTitleView extends LinearLayout {

  @SuppressWarnings("unused")
  private static final String TAG = ConversationTitleView.class.getSimpleName();

  private TextView title;
  private TextView subtitle;

  public ConversationTitleView(Context context) {
    this(context, null);
  }

  public ConversationTitleView(Context context, AttributeSet attrs) {
    super(context, attrs);

  }

  @Override
  public void onFinishInflate() {
    super.onFinishInflate();

    this.title = findViewById(R.id.title);
    this.subtitle = findViewById(R.id.subtitle);

    ViewUtil.setTextViewGravityStart(this.title, getContext());
    ViewUtil.setTextViewGravityStart(this.subtitle, getContext());
  }

  public void setTitle(@Nullable Recipients recipients) {
    if (recipients == null) setComposeTitle();
    else if (recipients.isSingleRecipient()) setRecipientTitle(recipients.getPrimaryRecipient());
    else setRecipientsTitle(recipients);

    if (recipients != null && recipients.isBlocked()) {
      title.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_block, 0, 0, 0);
    } else if (recipients != null && recipients.isMuted()) {
      title.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_volume_off, 0, 0, 0);
    } else {
      title.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
    }
  }

  private void setComposeTitle() {
    this.title.setText(R.string.ConversationActivity_compose_message);
    this.subtitle.setText(null);
    this.subtitle.setVisibility(View.GONE);
  }

  private void setRecipientTitle(Recipient recipient) {
    if (!recipient.isGroupRecipient()) {
      if (TextUtils.isEmpty(recipient.getName())) {
        this.title.setText(recipient.getNumber());
        this.subtitle.setText(null);
        this.subtitle.setVisibility(View.GONE);
      } else {
        this.title.setText(recipient.getName());
        this.subtitle.setText(recipient.getNumber());
        this.subtitle.setVisibility(View.VISIBLE);
      }
    } else {
      String groupName = (!TextUtils.isEmpty(recipient.getName())) ?
              recipient.getName() :
              getContext().getString(R.string.ConversationActivity_unnamed_group);

      this.title.setText(groupName);
      this.subtitle.setText(null);
      this.subtitle.setVisibility(View.GONE);
    }
  }

  private void setRecipientsTitle(Recipients recipients) {
    int size = recipients.getRecipientsList().size();

    title.setText(getContext().getString(R.string.ConversationActivity__group));
    subtitle.setText(getContext().getResources().getQuantityString(R.plurals.ConversationActivity_d_recipients_in_group, size, size));
    subtitle.setVisibility(View.VISIBLE);
  }
}
