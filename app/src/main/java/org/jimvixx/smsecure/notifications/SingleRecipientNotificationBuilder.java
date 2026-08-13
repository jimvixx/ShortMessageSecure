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

package org.jimvixx.smsecure.notifications;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.Action;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.preferences.widgets.NotificationPrivacyPreference;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.BitmapUtil;
import org.jimvixx.smsecure.util.Util;

import java.util.LinkedList;
import java.util.List;

public class SingleRecipientNotificationBuilder extends AbstractNotificationBuilder {

  @SuppressWarnings("unused")
  private static final String TAG = SingleRecipientNotificationBuilder.class.getSimpleName();

  private final List<CharSequence> messageBodies = new LinkedList<>();
  private CharSequence contentTitle;
  private CharSequence contentText;
  @Nullable
  private NotificationCompat.MessagingStyle messagingStyle;

  public SingleRecipientNotificationBuilder(@NonNull Context context,
                                            @NonNull NotificationPrivacyPreference privacy) {
    this(context, privacy, NotificationChannels.MESSAGES_DEFAULT);
  }

  public SingleRecipientNotificationBuilder(@NonNull Context context,
                                            @NonNull NotificationPrivacyPreference privacy,
                                            @NonNull String channelId) {
    super(context, privacy, channelId);
    initialize();
  }

  private void initialize() {
    setSmallIcon(R.drawable.ic_smsecure);
    setColor(ContextCompat.getColor(context, R.color.primary_color));
    setPriority(NotificationCompat.PRIORITY_HIGH);
    setCategory(NotificationCompat.CATEGORY_MESSAGE);
  }

  public void setThread(@NonNull Recipients recipients) {
    if (privacy.isDisplayContact()) {
      setContentTitle(recipients.toShortString());

      Recipient primary = recipients.getPrimaryRecipient();

      if (recipients.isSingleRecipient() && primary.getContactUri() != null) {
        Person person = new Person.Builder()
                .setUri(primary.getContactUri().toString())
                .setName(primary.toShortString())
                .build();
        addPerson(person);
      }

      setLargeIcon(
              recipients.getContactPhoto()
                      .asDrawable(context, recipients.getColor().toConversationColor(context))
      );
    } else {
      setContentTitle(context.getString(R.string.SingleRecipientNotificationBuilder_smsecure));
      setLargeIcon(
              Recipient.getUnknownRecipient()
                      .getContactPhoto()
                      .asDrawable(
                              context,
                              Recipient.getUnknownRecipient().getColor().toConversationColor(context)
                      )
      );
    }
  }

  public void setMessageCount(int messageCount) {
    setContentInfo(String.valueOf(messageCount));
    setNumber(messageCount);
  }

  public void setPrimaryMessageBody(@NonNull Recipients threadRecipients,
                                    @NonNull Recipient individualRecipient,
                                    @Nullable CharSequence message) {
    SpannableStringBuilder stringBuilder = new SpannableStringBuilder();

    if (privacy.isDisplayContact() &&
            (threadRecipients.isGroupRecipient() || !threadRecipients.isSingleRecipient())) {
      stringBuilder.append(Util.getBoldedString(individualRecipient.toShortString() + ": "));
    }

    if (privacy.isDisplayMessage() && message != null) {
      setContentText(stringBuilder.append(message));
    } else {
      setContentText(stringBuilder.append(
              context.getString(R.string.SingleRecipientNotificationBuilder_new_message)
      ));
    }
  }

  public void addAndroidAutoAction(long timestamp) {
    if (contentTitle == null || contentText == null) {
      return;
    }

    Person user = new Person.Builder()
            .setName(context.getString(R.string.app_name))
            .build();

    messagingStyle = new NotificationCompat.MessagingStyle(user);
  }

  public void addActions(@Nullable MasterSecret masterSecret,
                         @NonNull PendingIntent markReadIntent,
                         @NonNull PendingIntent wearableReplyIntent) {
    Action markAsReadAction = new Action.Builder(
            R.drawable.ic_check,
            context.getString(R.string.MessageNotifier_mark_read),
            markReadIntent
    )
            .setSemanticAction(Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build();

    if (masterSecret != null) {
      Action replyAction = new Action.Builder(
              R.drawable.ic_reply,
              context.getString(R.string.MessageNotifier_reply),
              wearableReplyIntent
      )
              .addRemoteInput(new RemoteInput.Builder(MessageNotifier.EXTRA_REMOTE_REPLY)
                      .setLabel(context.getString(R.string.MessageNotifier_reply))
                      .build())
              .setSemanticAction(Action.SEMANTIC_ACTION_REPLY)
              .setShowsUserInterface(false)
              .build();

      Action wearableReplyAction = new Action.Builder(
              R.drawable.ic_reply,
              context.getString(R.string.MessageNotifier_reply),
              wearableReplyIntent
      )
              .addRemoteInput(new RemoteInput.Builder(MessageNotifier.EXTRA_REMOTE_REPLY)
                      .setLabel(context.getString(R.string.MessageNotifier_reply))
                      .build())
              .setSemanticAction(Action.SEMANTIC_ACTION_REPLY)
              .setShowsUserInterface(false)
              .build();

      addAction(markAsReadAction);
      addAction(replyAction);

      extend(new NotificationCompat.WearableExtender()
              .addAction(markAsReadAction)
              .addAction(wearableReplyAction));
    } else {
      addAction(markAsReadAction);
      extend(new NotificationCompat.WearableExtender().addAction(markAsReadAction));
    }
  }

  public void addMessageBody(@NonNull Recipients threadRecipients,
                             @NonNull Recipient individualRecipient,
                             @Nullable CharSequence messageBody,
                             long timestamp) {
    SpannableStringBuilder stringBuilder = new SpannableStringBuilder();

    if (privacy.isDisplayContact() &&
            (threadRecipients.isGroupRecipient() || !threadRecipients.isSingleRecipient())) {
      stringBuilder.append(Util.getBoldedString(individualRecipient.toShortString() + ": "));
    }

    CharSequence displayMessage;

    if (privacy.isDisplayMessage()) {
      displayMessage = messageBody == null ? "" : messageBody;
      messageBodies.add(stringBuilder.append(displayMessage));
    } else {
      displayMessage =
              context.getString(R.string.SingleRecipientNotificationBuilder_new_message);
      messageBodies.add(stringBuilder.append(displayMessage));
    }

    if (messagingStyle != null) {
      Person.Builder senderBuilder = new Person.Builder();

      if (privacy.isDisplayContact()) {
        senderBuilder.setName(individualRecipient.toShortString());

        if (individualRecipient.getContactUri() != null) {
          senderBuilder.setUri(individualRecipient.getContactUri().toString());
        }
      } else {
        senderBuilder.setName(
                context.getString(R.string.SingleRecipientNotificationBuilder_smsecure)
        );
      }

      messagingStyle.addMessage(
              displayMessage,
              timestamp,
              senderBuilder.build()
      );
    }
  }

  @Override
  @NonNull
  public Notification build() {
    if (messagingStyle != null) {
      setStyle(messagingStyle);
    } else if (privacy.isDisplayMessage()) {
      setStyle(new NotificationCompat.BigTextStyle().bigText(getBigText(messageBodies)));
    }

    return super.build();
  }
  private void setLargeIcon(@Nullable Drawable drawable) {
    if (drawable != null) {
      int largeIconTargetSize =
              context.getResources().getDimensionPixelSize(R.dimen.contact_photo_target_size);

      Bitmap recipientPhotoBitmap =
              BitmapUtil.createFromDrawable(drawable, largeIconTargetSize, largeIconTargetSize);

      if (recipientPhotoBitmap != null) {
        setLargeIcon(recipientPhotoBitmap);
      }
    }
  }

  @NonNull
  @Override
  public NotificationCompat.Builder setContentTitle(CharSequence contentTitle) {
    this.contentTitle = contentTitle;
    return super.setContentTitle(contentTitle);
  }

  @NonNull
  @Override
  public NotificationCompat.Builder setContentText(CharSequence contentText) {
    this.contentText = trimToDisplayLength(contentText);
    return super.setContentText(this.contentText);
  }

  private CharSequence getBigText(@NonNull List<CharSequence> messageBodies) {
    SpannableStringBuilder content = new SpannableStringBuilder();

    for (CharSequence message : messageBodies) {
      content.append(message);
      content.append('\n');
    }

    return content;
  }
}