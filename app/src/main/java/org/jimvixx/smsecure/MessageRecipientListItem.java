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
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.components.AvatarImageView;
import org.jimvixx.smsecure.components.FromTextView;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.documents.IdentityKeyMismatch;
import org.jimvixx.smsecure.database.documents.NetworkFailure;
import org.jimvixx.smsecure.database.model.MessageRecord;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.sms.MessageSender;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * A simple view to show the recipients of a message.
 *
 * @author Jake McGinty
 */
public class MessageRecipientListItem extends RelativeLayout
        implements Recipient.RecipientModifiedListener {
  private static final String TAG = MessageRecipientListItem.class.getSimpleName();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Executor resendExecutor = Executors.newSingleThreadExecutor();
  private @Nullable Recipient recipient;
  private FromTextView fromView;
  private TextView errorDescription;
  private Button conflictButton;
  private Button resendButton;
  private AvatarImageView contactPhotoImage;

  public MessageRecipientListItem(@NonNull Context context) {
    super(context);
  }

  public MessageRecipientListItem(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();
    fromView = findViewById(R.id.from);
    errorDescription = findViewById(R.id.error_description);
    contactPhotoImage = findViewById(R.id.contact_photo_image);
    conflictButton = findViewById(R.id.conflict_button);
    resendButton = findViewById(R.id.resend_button);
  }

  public void set(@NonNull MasterSecret masterSecret,
                  @NonNull MessageRecord record,
                  @NonNull Recipient recipient) {
    unbind();

    this.recipient = recipient;
    recipient.addListener(this);

    fromView.setText(recipient);
    contactPhotoImage.setAvatar(recipient, false);
    setIssueIndicators(masterSecret, record);
  }

  public void unbind() {
    Recipient r = this.recipient;
    if (r != null) {
      r.removeListener(this);
      this.recipient = null;
    }
  }

  @Override
  public void onModified(@NonNull final Recipient recipient) {
    handler.post(() -> {
      fromView.setText(recipient);
      contactPhotoImage.setAvatar(recipient, false);
    });
  }

  private void setIssueIndicators(@NonNull final MasterSecret masterSecret,
                                  @NonNull final MessageRecord record) {
    final NetworkFailure networkFailure = getNetworkFailure(record);
    final IdentityKeyMismatch keyMismatch = (networkFailure == null) ? getKeyMismatch(record) : null;

    String errorText = "";

    if (keyMismatch != null) {
      resendButton.setVisibility(View.GONE);
      conflictButton.setVisibility(View.VISIBLE);

      Context context = getContext();
      if (context != null) {
        errorText = context.getString(R.string.MessageDetailsRecipient_new_identity);
      }

      conflictButton.setOnClickListener(v -> {
        Context c = getContext();
        if (c != null) {
          new ReceiveKeyDialog(c, masterSecret, record).show();
        }
      });

    } else if (networkFailure != null || record.isFailed()) {
      resendButton.setVisibility(View.VISIBLE);
      resendButton.setEnabled(true);
      resendButton.requestFocus();
      conflictButton.setVisibility(View.GONE);

      Context context = getContext();
      if (context != null) {
        errorText = context.getString(R.string.MessageDetailsRecipient_failed_to_send);
      }

      resendButton.setOnClickListener(v -> resendMessage(masterSecret, record));

    } else {
      resendButton.setVisibility(View.GONE);
      conflictButton.setVisibility(View.GONE);
      conflictButton.setOnClickListener(null);
      resendButton.setOnClickListener(null);
    }

    errorDescription.setText(errorText);
    errorDescription.setVisibility(TextUtils.isEmpty(errorText) ? View.GONE : View.VISIBLE);
  }

  private void resendMessage(@NonNull MasterSecret masterSecret,
                             @NonNull MessageRecord record) {
    Context context = getContext();
    if (context == null) {
      return;
    }

    final Context appContext = context.getApplicationContext();

    resendExecutor.execute(() -> MessageSender.resend(appContext, masterSecret, record));
  }

  @Nullable
  private NetworkFailure getNetworkFailure(@NonNull MessageRecord record) {
    Recipient r = this.recipient;
    if (r == null) {
      return null;
    }

    if (record.hasNetworkFailures()) {
      for (final NetworkFailure failure : record.getNetworkFailures()) {
        if (failure.getRecipientId() == r.getRecipientId()) {
          return failure;
        }
      }
    }
    return null;
  }

  @Nullable
  private IdentityKeyMismatch getKeyMismatch(@NonNull MessageRecord record) {
    Recipient r = this.recipient;
    if (r == null) {
      return null;
    }

    if (record.isIdentityMismatchFailure()) {
      for (final IdentityKeyMismatch mismatch : record.getIdentityKeyMismatches()) {
        if (mismatch.getRecipientId() == r.getRecipientId()) {
          return mismatch;
        }
      }
    }
    return null;
  }
}
