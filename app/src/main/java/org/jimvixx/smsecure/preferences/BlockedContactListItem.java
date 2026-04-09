package org.jimvixx.smsecure.preferences;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.components.AvatarImageView;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.Util;

public class BlockedContactListItem extends RelativeLayout implements Recipients.RecipientsModifiedListener {

  private AvatarImageView contactPhotoImage;
  private TextView nameView;
  private Recipients recipients;

  public BlockedContactListItem(Context context) {
    super(context);
  }

  public BlockedContactListItem(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public BlockedContactListItem(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @Override
  public void onFinishInflate() {
    super.onFinishInflate();
    this.contactPhotoImage = findViewById(R.id.contact_photo_image);
    this.nameView = findViewById(R.id.name);
  }

  public void set(Recipients recipients) {
    this.recipients = recipients;

    onModified(recipients);
    recipients.addListener(this);
  }

  @Override
  public void onModified(final Recipients recipients) {
    final AvatarImageView contactPhotoImage = this.contactPhotoImage;
    final TextView nameView = this.nameView;

    Util.runOnMain(() -> {
      contactPhotoImage.setAvatar(recipients, false);
      nameView.setText(recipients.toShortString());
    });
  }

  public Recipients getRecipients() {
    return recipients;
  }
}
