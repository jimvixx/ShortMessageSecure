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

import static org.jimvixx.smsecure.util.SpanUtil.color;
import static org.jimvixx.smsecure.util.ThemeUtil.resolveThemeColor;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import org.jimvixx.smsecure.components.AlertView;
import org.jimvixx.smsecure.components.AvatarImageView;
import org.jimvixx.smsecure.components.DeliveryStatusView;
import org.jimvixx.smsecure.components.FromTextView;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.model.ThreadRecord;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.DateUtils;
import org.jimvixx.smsecure.util.ViewUtil;

import java.util.Set;

/*
 * A view that displays the element in a list of multiple conversation threads.
 */
public class ConversationListItem extends RelativeLayout
        implements Recipients.RecipientsModifiedListener,
        BindableConversationListItem, Unbindable {
  private final static Typeface BOLD_TYPEFACE = Typeface.create("sans-serif", Typeface.BOLD);
  private final static Typeface LIGHT_TYPEFACE = Typeface.create("sans-serif-light", Typeface.NORMAL);
  private final @DrawableRes int readBackground;
  private final @DrawableRes int unreadBackground;
  private final Handler handler = new Handler();
  private Set<Long> selectedThreads;
  private Recipients recipients;
  private long threadId;
  private TextView subjectView;
  private FromTextView fromView;
  private TextView dateView;
  private TextView archivedView;
  private DeliveryStatusView deliveryStatusIndicator;
  private AlertView alertView;
  private long lastSeen;
  private boolean read;
  private AvatarImageView contactPhotoImage;
  private int distributionType;

  public ConversationListItem(Context context) {
    this(context, null);
  }

  public ConversationListItem(Context context, AttributeSet attrs) {
    super(context, attrs);
    readBackground = R.drawable.bg_conversation_list_item_read;
    unreadBackground = R.drawable.bg_conversation_list_item_unread;
  }

  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();
    this.subjectView = findViewById(R.id.subject);
    this.fromView = findViewById(R.id.from);
    this.dateView = findViewById(R.id.date);
    this.deliveryStatusIndicator = findViewById(R.id.delivery_status);
    this.alertView = findViewById(R.id.indicators_parent);
    this.contactPhotoImage = findViewById(R.id.contact_photo_image);
    this.archivedView = ViewUtil.findById(this, R.id.archived);

    ViewUtil.setTextViewGravityStart(this.fromView, getContext());
    ViewUtil.setTextViewGravityStart(this.subjectView, getContext());
  }

  public void bind(@NonNull MasterSecret masterSecret, @NonNull ThreadRecord thread,
                   @NonNull Set<Long> selectedThreads, boolean batchMode) {
    this.selectedThreads = selectedThreads;
    this.recipients = thread.getRecipients();
    this.threadId = thread.getThreadId();
    this.read = thread.isRead();
    this.distributionType = thread.getDistributionType();
    this.lastSeen = thread.getLastSeen();

    this.recipients.addListener(this);
    this.fromView.setText(recipients, read);

    this.subjectView.setText(thread.getDisplayBody());
    this.subjectView.setTypeface(read ? LIGHT_TYPEFACE : BOLD_TYPEFACE);

    if (thread.getDate() > 0) {
      CharSequence date = DateUtils.getBriefRelativeTimeSpanString(getContext(), thread.getDate());

      int color = resolveThemeColor(getContext(), R.attr.conversation_list_item__unread_date_color);

      dateView.setText(read ? date : color(color, date));

      dateView.setTypeface(read ? LIGHT_TYPEFACE : BOLD_TYPEFACE);
    }

    if (thread.isArchived()) {
      this.archivedView.setVisibility(View.VISIBLE);
    } else {
      this.archivedView.setVisibility(View.GONE);
    }

    setStatusIcons(thread);
    setBatchState(batchMode);
    setBackground(thread);
    setRippleColor(recipients);
    this.contactPhotoImage.setAvatar(recipients, true);
  }

  @Override
  public void unbind() {
    if (this.recipients != null) this.recipients.removeListener(this);
  }

  private void setBatchState(boolean batch) {
    setSelected(batch && selectedThreads.contains(threadId));
  }

  public Recipients getRecipients() {
    return recipients;
  }

  public long getThreadId() {
    return threadId;
  }

  public boolean getRead() {
    return read;
  }

  public int getDistributionType() {
    return distributionType;
  }

  public long getLastSeen() {
    return lastSeen;
  }

  private void setStatusIcons(ThreadRecord thread) {
    if (!thread.isOutgoing()) {
      deliveryStatusIndicator.setNone();
      alertView.setNone();
    } else if (thread.isFailed()) {
      deliveryStatusIndicator.setNone();
      alertView.setFailed();
    } else {
      alertView.setNone();

      if (thread.isPending()) deliveryStatusIndicator.setPending();
      else if (thread.isDelivered()) deliveryStatusIndicator.setDelivered();
      else deliveryStatusIndicator.setSent();
    }
  }

  private void setBackground(ThreadRecord thread) {
    if (thread.isRead()) setBackgroundResource(readBackground);
    else setBackgroundResource(unreadBackground);
  }

  private void setRippleColor(Recipients recipients) {
    ((RippleDrawable) (getBackground()).mutate())
            .setColor(ColorStateList.valueOf(recipients.getColor().toConversationColor(getContext())));
  }

  @Override
  public void onModified(final Recipients recipients) {
    handler.post(() -> {
      fromView.setText(recipients, read);
      contactPhotoImage.setAvatar(recipients, true);
      setRippleColor(recipients);
    });
  }
}
