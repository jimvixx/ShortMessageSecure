package org.jimvixx.smsecure.notifications;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.TaskStackBuilder;

import org.jimvixx.smsecure.ConversationActivity;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.Recipients;

public class NotificationItem {

  private final long id;
  private final @NonNull Recipients recipients;
  private final @NonNull Recipient individualRecipient;
  private final @Nullable Recipients threadRecipients;
  private final long threadId;
  private final @Nullable CharSequence text;
  private final long timestamp;

  public NotificationItem(long id,
                          @NonNull Recipient individualRecipient,
                          @NonNull Recipients recipients,
                          @Nullable Recipients threadRecipients,
                          long threadId, @Nullable CharSequence text, long timestamp) {
    this.id = id;
    this.individualRecipient = individualRecipient;
    this.recipients = recipients;
    this.threadRecipients = threadRecipients;
    this.text = text;
    this.threadId = threadId;
    this.timestamp = timestamp;
  }

  public @NonNull Recipients getRecipients() {
    return threadRecipients == null ? recipients : threadRecipients;
  }

  public @NonNull Recipient getIndividualRecipient() {
    return individualRecipient;
  }

  @Nullable
  public CharSequence getText() {
    return text;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public long getThreadId() {
    return threadId;
  }

  public PendingIntent getPendingIntent(Context context) {
    Intent intent = new Intent(context, ConversationActivity.class);
    Recipients notifyRecipients = threadRecipients != null ? threadRecipients : recipients;
    intent.putExtra("recipients", notifyRecipients.getIds());

    intent.putExtra("thread_id", threadId);
    intent.setData(Uri.parse("custom://" + System.currentTimeMillis()));

    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    flags |= PendingIntent.FLAG_IMMUTABLE;

    return TaskStackBuilder.create(context)
            .addNextIntentWithParentStack(intent)
            .getPendingIntent(0, flags);
  }

  public long getId() {
    return id;
  }
}
