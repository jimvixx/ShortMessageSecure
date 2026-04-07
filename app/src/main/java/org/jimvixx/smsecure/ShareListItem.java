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

package org.jimvixx.smsecure;

import android.content.Context;
import android.os.Handler;
import android.util.AttributeSet;
import android.widget.RelativeLayout;

import org.jimvixx.smsecure.components.AvatarImageView;
import org.jimvixx.smsecure.components.FromTextView;
import org.jimvixx.smsecure.database.model.ThreadRecord;
import org.jimvixx.smsecure.recipients.Recipients;

/**
 * A simple view to show the recipients of an open conversation
 *
 * @author Jake McGinty
 */
public class ShareListItem extends RelativeLayout
        implements Recipients.RecipientsModifiedListener {
  @SuppressWarnings("unused")
  private static final String TAG = ShareListItem.class.getSimpleName();
  private final Handler handler = new Handler();
  private Recipients recipients;
  private long threadId;
  private FromTextView fromView;
  private AvatarImageView contactPhotoImage;
  private int distributionType;

  public ShareListItem(Context context) {
    super(context);
  }

  public ShareListItem(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();
    this.fromView = findViewById(R.id.from);
    this.contactPhotoImage = findViewById(R.id.contact_photo_image);
  }

  public void set(ThreadRecord thread) {
    this.recipients = thread.getRecipients();
    this.threadId = thread.getThreadId();
    this.distributionType = thread.getDistributionType();

    this.recipients.addListener(this);
    this.fromView.setText(recipients);

    setBackgroundResource(R.drawable.bg_conversation_list_item_read);

    this.contactPhotoImage.setAvatar(this.recipients, false);
  }

  public void unbind() {
    if (this.recipients != null) this.recipients.removeListener(this);
  }

  public Recipients getRecipients() {
    return recipients;
  }

  public long getThreadId() {
    return threadId;
  }

  public int getDistributionType() {
    return distributionType;
  }

  @Override
  public void onModified(final Recipients recipients) {
    handler.post(() -> {
      fromView.setText(recipients);
      contactPhotoImage.setAvatar(recipients, false);
    });
  }
}
