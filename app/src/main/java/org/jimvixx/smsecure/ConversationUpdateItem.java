/*
 * Copyright (C) 2015 Whisper Systems
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
import android.content.Intent;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.model.MessageRecord;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.Util;

import java.util.Locale;
import java.util.Set;

public class ConversationUpdateItem extends LinearLayout
        implements Recipients.RecipientsModifiedListener,
        Recipient.RecipientModifiedListener,
        BindableConversationItem,
        View.OnClickListener
{
  private static final String TAG = ConversationUpdateItem.class.getSimpleName();

  private ImageView     icon;
  private TextView      body;
  private Recipient     sender;
  private MessageRecord messageRecord;

  public ConversationUpdateItem(Context context) {
    super(context);
  }

  public ConversationUpdateItem(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  public void onFinishInflate() {
    super.onFinishInflate();

    this.icon = findViewById(R.id.conversation_update_icon);
    this.body = findViewById(R.id.conversation_update_body);

    setOnClickListener(this);
  }

  @Override
  public void bind(@NonNull MasterSecret masterSecret,
                   @NonNull MessageRecord messageRecord,
                   @NonNull Locale locale,
                   @NonNull Set<MessageRecord> batchSelected,
                   @NonNull Recipients conversationRecipients)
  {
    bind(messageRecord);
  }

  private void bind(@NonNull MessageRecord messageRecord) {
    this.messageRecord = messageRecord;
    this.sender        = messageRecord.getIndividualRecipient();

    this.sender.addListener(this);

    if (messageRecord.isGroupAction()) {
      icon.setImageDrawable(ResourcesCompat.getDrawable(
              getResources(),
              R.drawable.ic_account_multiple,
              getContext().getTheme()
      ));

      if (messageRecord.isGroupQuit() && messageRecord.isOutgoing()) {
        body.setText(R.string.MessageRecord_left_group);
      } else if (messageRecord.isGroupQuit()) {
        body.setText(getContext().getString(
                R.string.ConversationItem_group_action_left,
                sender.toShortString()
        ));
      }
    }
  }

  @Override
  public void onModified(Recipients recipients) {
    onModified(recipients.getPrimaryRecipient());
  }

  @Override
  public void onModified(Recipient recipient) {
    Util.runOnMain(() -> bind(messageRecord));
  }

  @Override
  public void onClick(View v) {
    if (messageRecord.isIdentityUpdate()) {
      Intent intent = new Intent(getContext(), RecipientPreferenceActivity.class);
      intent.putExtra(RecipientPreferenceActivity.RECIPIENTS_EXTRA,
              new long[] { messageRecord.getIndividualRecipient().getRecipientId() });

      getContext().startActivity(intent);
    }
  }

  @Override
  public void unbind() {
    if (sender != null) {
      sender.removeListener(this);
    }
  }
}
