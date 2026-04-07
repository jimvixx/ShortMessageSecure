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

import static org.jimvixx.smsecure.util.ThemeUtil.resolveThemeColor;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;

import org.jimvixx.smsecure.ConversationListActivity;
import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.preferences.widgets.NotificationPrivacyPreference;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.util.Util;

import java.util.LinkedList;
import java.util.List;

public class MultipleRecipientNotificationBuilder extends AbstractNotificationBuilder {

  private final List<CharSequence> messageBodies = new LinkedList<>();

  public MultipleRecipientNotificationBuilder(@NonNull Context context,
                                              @NonNull NotificationPrivacyPreference privacy,
                                              @NonNull String channelId) {
    super(context, privacy, channelId);
    initialize();
  }

  private void initialize() {
    setColor(resolveThemeColor(context, R.attr.appColorToolbarBackground));
    setSmallIcon(R.drawable.ic_smsecure);
    setContentTitle(context.getString(R.string.app_name));
    setContentIntent(PendingIntent.getActivity(
            context,
            0,
            new Intent(context, ConversationListActivity.class),
            PendingIntent.FLAG_IMMUTABLE
    ));
    setCategory(NotificationCompat.CATEGORY_MESSAGE);
    setPriority(NotificationCompat.PRIORITY_HIGH);
    setGroupSummary(true);
  }

  public void setMessageCount(int messageCount, int threadCount) {
    setSubText(context.getString(R.string.MessageNotifier_d_new_messages_in_d_conversations,
            messageCount, threadCount));
    setContentInfo(String.valueOf(messageCount));
    setNumber(messageCount);
  }

  public void setMostRecentSender(@NonNull Recipient recipient) {
    if (privacy.isDisplayContact()) {
      setContentText(context.getString(R.string.MessageNotifier_most_recent_from_s,
              recipient.toShortString()));
    }
  }

  public void addActions(@NonNull PendingIntent markAsReadIntent) {
    NotificationCompat.Action markAllAsReadAction = new NotificationCompat.Action(
            R.drawable.ic_check,
            context.getString(R.string.MessageNotifier_mark_all_as_read),
            markAsReadIntent
    );

    addAction(markAllAsReadAction);
    extend(new NotificationCompat.WearableExtender().addAction(markAllAsReadAction));
  }

  public void addMessageBody(@NonNull Recipient sender, @Nullable CharSequence body) {
    if (privacy.isDisplayMessage()) {
      messageBodies.add(getStyledMessage(sender, body));
    } else if (privacy.isDisplayContact()) {
      messageBodies.add(Util.getBoldedString(sender.toShortString()));
    }

    if (privacy.isDisplayContact() && sender.getContactUri() != null) {
      Person person = new Person.Builder()
              .setUri(sender.getContactUri().toString())
              .setName(sender.toShortString())
              .build();

      addPerson(person);
    }
  }

  @Override
  @NonNull
  public Notification build() {
    if (privacy.isDisplayMessage() || privacy.isDisplayContact()) {
      NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();

      for (CharSequence body : messageBodies) {
        style.addLine(body);
      }

      setStyle(style);
    }

    return super.build();
  }
}