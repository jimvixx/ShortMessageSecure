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

package org.jimvixx.smsecure.components;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.AttributeSet;

import androidx.appcompat.content.res.AppCompatResources;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.components.emoji.EmojiTextView;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.ViewUtil;

public class FromTextView extends EmojiTextView {

  @SuppressWarnings("unused")
  private static final String TAG = FromTextView.class.getSimpleName();
  private boolean pinned;

  public FromTextView(Context context) {
    super(context);
  }

  public FromTextView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public void setText(Recipient recipient) {
    setText(RecipientFactory.getRecipientsFor(getContext(), recipient, true));
  }

  public void setText(Recipients recipients) {
    setText(recipients, true);
  }

  public void setText(Recipients recipients, boolean read) {
    int[] attributes = new int[]{R.attr.conversation_list_item_count_color};
    TypedArray colors = getContext().obtainStyledAttributes(attributes);
    boolean isUnnamedGroup = recipients.isGroupRecipient() && TextUtils.isEmpty(recipients.getPrimaryRecipient().getName());

    String fromString;

    if (isUnnamedGroup) {
      fromString = getContext().getString(R.string.ConversationActivity_unnamed_group);
    } else {
      fromString = recipients.toShortString();
    }

    int typeface;

    if (isUnnamedGroup) {
      if (!read) typeface = Typeface.BOLD_ITALIC;
      else typeface = Typeface.ITALIC;
    } else if (!read) {
      typeface = Typeface.BOLD;
    } else {
      typeface = Typeface.NORMAL;
    }

    SpannableStringBuilder builder = new SpannableStringBuilder(fromString);
    builder.setSpan(new StyleSpan(typeface), 0, builder.length(),
            Spannable.SPAN_INCLUSIVE_EXCLUSIVE);

    colors.recycle();

    setText(builder);

    int statusIconResId;
    if (recipients.isBlocked()) statusIconResId = R.drawable.ic_block;
    else if (recipients.isMuted()) statusIconResId = R.drawable.ic_volume_off;
    else statusIconResId = 0;

    setConversationIcons(statusIconResId);
  }

  public void setPinned(boolean pinned) {
    this.pinned = pinned;
  }

  private void setConversationIcons(int statusIconResId) {
    Drawable statusIcon = getSizedDrawable(statusIconResId);
    Drawable pinIcon = getSizedDrawable(pinned ? R.drawable.ic_pin : 0);
    setCompoundDrawablesRelative(statusIcon, null, pinIcon, null);
  }

  private Drawable getSizedDrawable(int resId) {
    if (resId == 0) return null;

    Drawable drawable = AppCompatResources.getDrawable(getContext(), resId);
    if (drawable == null) return null;

    drawable = drawable.mutate();
    int sizePx = ViewUtil.dpToPx(getResources(), 18);
    drawable.setBounds(0, 0, sizePx, sizePx);
    return drawable;
  }
}
