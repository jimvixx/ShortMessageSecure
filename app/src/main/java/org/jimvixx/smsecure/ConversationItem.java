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

package org.jimvixx.smsecure;

import static org.jimvixx.smsecure.util.ThemeUtil.resolveThemeColor;

import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.text.util.Linkify;
import android.util.AttributeSet;
import android.util.Patterns;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.drawable.DrawableCompat;

import org.jimvixx.smsecure.components.AlertView;
import org.jimvixx.smsecure.components.AvatarImageView;
import org.jimvixx.smsecure.components.DeliveryStatusView;
import org.jimvixx.smsecure.crypto.KeyExchangeInitiator;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.MessageDatabase;
import org.jimvixx.smsecure.database.SmsDatabase;
import org.jimvixx.smsecure.database.model.MessageRecord;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.protocol.AutoInitiate;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.DateUtils;
import org.jimvixx.smsecure.util.TelephonyUtil;
import org.jimvixx.smsecure.util.Util;
import org.jimvixx.smsecure.util.dualsim.SubscriptionInfoCompat;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A view that displays an individual conversation item within a conversation thread.
 */
public class ConversationItem extends LinearLayout
        implements Recipient.RecipientModifiedListener,
        Recipients.RecipientsModifiedListener,
        BindableConversationItem {
  private static final String TAG = ConversationItem.class.getSimpleName();

  private static final Pattern XMPP_PATTERN =
          Pattern.compile("xmpp:[^ \t\n\"':,<>]+", Pattern.CASE_INSENSITIVE);

  private static final Pattern GEO_URI_PATTERN =
          Pattern.compile("geo:[-0-9.]+,[-0-9.]+[^ \t\n\"':]*", Pattern.CASE_INSENSITIVE);

  private static final Linkify.TransformFilter WEBURL_TRANSFORM = (matcher, url) -> {
    if (url == null) return null;

    String[] split = url.split(":", 2);
    if (split.length == 2) {
      return split[0].toLowerCase(Locale.ROOT) + ":" + split[1];
    } else {
      return "http://" + url;
    }
  };

  private final @NonNull Context context;
  private final PassthroughClickListener passthroughClickListener = new PassthroughClickListener();
  protected @Nullable View bodyBubble;
  private @Nullable MessageRecord messageRecord;
  private @Nullable MasterSecret masterSecret;
  private @Nullable Locale locale;
  private boolean groupThread;
  private @Nullable Recipient recipient;
  private @Nullable TextView bodyText;
  private @Nullable TextView dateText;
  private @Nullable TextView simInfoText;
  private @Nullable TextView indicatorText;
  private @Nullable TextView groupStatusText;
  private @Nullable ImageView secureImage;
  private @Nullable AvatarImageView contactPhoto;
  private @Nullable DeliveryStatusView deliveryStatusIndicator;
  private @Nullable AlertView alertView;
  private @NonNull Set<MessageRecord> batchSelected = new HashSet<>();
  private @Nullable Recipients conversationRecipients;
  // These are inflated from ViewStubs and must be nullable in Java.
  private int defaultBubbleColor;

  public ConversationItem(Context context) {
    this(context, null);
  }

  public ConversationItem(@NonNull Context context, AttributeSet attrs) {
    super(context, attrs);
    this.context = context;
  }

  @Override
  public void setOnClickListener(OnClickListener l) {
    super.setOnClickListener(new ClickListener(l));
  }

  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();

    initializeAttributes();

    bodyText = findViewById(R.id.conversation_item_body);
    dateText = findViewById(R.id.conversation_item_date);
    simInfoText = findViewById(R.id.sim_info);
    indicatorText = findViewById(R.id.indicator_text);
    groupStatusText = findViewById(R.id.group_message_status);
    secureImage = findViewById(R.id.secure_indicator);
    deliveryStatusIndicator = findViewById(R.id.delivery_status);
    alertView = findViewById(R.id.indicators_parent);
    contactPhoto = findViewById(R.id.contact_photo);
    bodyBubble = findViewById(R.id.body_bubble);

    // Ensure ConversationItem always has its internal click routing.
    super.setOnClickListener(new ClickListener(null));


    if (bodyText != null) {
      bodyText.setOnLongClickListener(passthroughClickListener);
      bodyText.setOnClickListener(passthroughClickListener);
    }
  }

  private void initializeAttributes() {

    defaultBubbleColor = resolveThemeColor(
            context,
            R.attr.conversation_item_bubble_background_color);
  }

  @Override
  public void bind(@NonNull MasterSecret masterSecret,
                   @NonNull MessageRecord messageRecord,
                   @NonNull Locale locale,
                   @NonNull Set<MessageRecord> batchSelected,
                   @NonNull Recipients conversationRecipients) {
    this.masterSecret = masterSecret;
    this.messageRecord = messageRecord;
    this.locale = locale;
    this.batchSelected = batchSelected;
    this.conversationRecipients = conversationRecipients;
    this.groupThread = !conversationRecipients.isSingleRecipient() || conversationRecipients.isGroupRecipient();
    this.recipient = messageRecord.getIndividualRecipient();

    if (this.recipient != null) this.recipient.addListener(this);
    this.conversationRecipients.addListener(this);

    setInteractionState(messageRecord);
    setBodyText(messageRecord);

    Recipient r = this.recipient;
    if (r != null) {
      setBubbleState(messageRecord);
      setContactPhoto(r);
      setGroupMessageStatus(messageRecord, r);
    }

    setStatusIcons(messageRecord);
    checkForAutoInitiate(messageRecord);
    setMinimumWidth();
    setSimInfo(messageRecord);
  }

  @Override
  public void unbind() {
    if (recipient != null) {
      recipient.removeListener(this);
    }
    if (conversationRecipients != null) {
      conversationRecipients.removeListener(this);
    }
  }

  public @Nullable MessageRecord getMessageRecord() {
    return messageRecord;
  }

  private void setBubbleState(@NonNull MessageRecord messageRecord) {
    if (bodyBubble == null) return;

    final int bubbleColor;

    if (messageRecord.isOutgoing()) {
      bubbleColor = defaultBubbleColor;
    } else {
      Recipients recipients = conversationRecipients;
      if (recipients != null) {
        bubbleColor = recipients.getColor().toConversationColor(context);
      } else {
        bubbleColor = defaultBubbleColor;
      }
    }

    Drawable bg = bodyBubble.getBackground();
    if (bg == null) return;

    bg = DrawableCompat.wrap(bg).mutate();
    DrawableCompat.setTint(bg, bubbleColor);
    DrawableCompat.setTintMode(bg, PorterDuff.Mode.SRC_IN);
    bodyBubble.setBackground(bg);
  }

  private void setInteractionState(@NonNull MessageRecord messageRecord) {
    setSelected(batchSelected.contains(messageRecord));
  }

  private void setBodyText(@NonNull MessageRecord messageRecord) {
    if (bodyText == null) return;

    bodyText.setClickable(false);
    bodyText.setFocusable(false);

    bodyText.setText(messageRecord.getDisplayBody());
    bodyText.setVisibility(View.VISIBLE);
    linkifyBodyText();
  }

  private void linkifyBodyText() {
    if (bodyText == null) return;

    if (batchSelected.isEmpty()) {
      Linkify.addLinks(bodyText, XMPP_PATTERN, "xmpp:");
      Linkify.addLinks(bodyText, GEO_URI_PATTERN, "geo:");

      // Linkify.ALL conflicts with custom patterns; rebuild patterns manually.
      Linkify.addLinks(bodyText, Patterns.WEB_URL, null, Linkify.sUrlMatchFilter, WEBURL_TRANSFORM);
      Linkify.addLinks(bodyText, Patterns.EMAIL_ADDRESS, "mailto:");
      Linkify.addLinks(bodyText, Patterns.PHONE, "tel:");
    } else {
      bodyText.setAutoLinkMask(0);
    }
  }

  private void setContactPhoto(@NonNull Recipient recipient) {
    if (messageRecord != null && !messageRecord.isOutgoing()) {
      setContactPhotoForRecipient(recipient);
    }
  }

  private void setStatusIcons(@NonNull MessageRecord messageRecord) {
    if (indicatorText != null) indicatorText.setVisibility(View.GONE);

    if (secureImage != null) {
      secureImage.setVisibility(messageRecord.isSecure() ? View.VISIBLE : View.GONE);
    }

    if (dateText != null && locale != null) {
      dateText.setText(DateUtils.getExtendedRelativeTimeSpanString(getContext(), locale, messageRecord.getTimestamp()));
    }

    if (messageRecord.isFailed()) {
      setFailedStatusIcons();
    } else {
      if (alertView != null) alertView.setNone();

      if (deliveryStatusIndicator != null) {
        if (!messageRecord.isOutgoing()) deliveryStatusIndicator.setNone();
        else if (messageRecord.isPending()) deliveryStatusIndicator.setPending();
        else if (messageRecord.isDelivered()) deliveryStatusIndicator.setDelivered();
        else deliveryStatusIndicator.setSent();
      }
    }
  }

  private void setSimInfo(@NonNull MessageRecord messageRecord) {
    if (simInfoText == null) return;

    SubscriptionManagerCompat subscriptionManager = SubscriptionManagerCompat.from(context);

    if (subscriptionManager.getActiveSubscriptionInfoList().size() < 2) {
      simInfoText.setVisibility(View.GONE);
    } else {
      Optional<SubscriptionInfoCompat> subscriptionInfo =
              subscriptionManager.getActiveSubscriptionInfo(messageRecord.getSubscriptionId());

      if (subscriptionInfo.isPresent()) {
        simInfoText.setText(getContext().getString(R.string.ConversationItem_via_s, subscriptionInfo.get().getDisplayName()));
        simInfoText.setVisibility(View.VISIBLE);
      } else {
        simInfoText.setVisibility(View.GONE);
      }
    }
  }

  public void hideClickForDetails() {
    if (indicatorText != null) indicatorText.setVisibility(View.GONE);
  }

  private void setFailedStatusIcons() {
    if (alertView != null) alertView.setFailed();
    if (deliveryStatusIndicator != null) deliveryStatusIndicator.setNone();
    if (dateText != null) dateText.setText(R.string.ConversationItem_error_not_delivered);

    if (messageRecord != null && messageRecord.isOutgoing() && indicatorText != null) {
      indicatorText.setText(R.string.ConversationItem_click_for_details);
      indicatorText.setVisibility(View.VISIBLE);
    }
  }

  private void setMinimumWidth() {
    if (indicatorText == null || bodyBubble == null) return;

    if (indicatorText.getVisibility() == View.VISIBLE && indicatorText.getText() != null) {
      final float density = getResources().getDisplayMetrics().density;
      bodyBubble.setMinimumWidth(indicatorText.getText().length() * (int) (6.5f * density) + (int) (22.0f * density));
    } else {
      bodyBubble.setMinimumWidth(0);
    }
  }

  private boolean shouldInterceptClicks(@NonNull MessageRecord messageRecord) {
    return batchSelected.isEmpty() &&
            ((messageRecord.isFailed()) ||
                    shouldInterceptKeyExchangeMessage(messageRecord));
  }

  private boolean shouldInterceptKeyExchangeMessage(@NonNull MessageRecord keyExchangeMessage) {
    return keyExchangeMessage.isKeyExchange() &&
            !keyExchangeMessage.isProcessedKeyExchange() &&
            !keyExchangeMessage.isOutgoing();
  }

  private void setGroupMessageStatus(@NonNull MessageRecord messageRecord, @NonNull Recipient recipient) {
    if (groupStatusText == null) return;

    if (groupThread && !messageRecord.isOutgoing()) {
      groupStatusText.setText(recipient.toShortString());
      groupStatusText.setVisibility(View.VISIBLE);
    } else {
      groupStatusText.setVisibility(View.GONE);
    }
  }

  private void checkForAutoInitiate(@NonNull MessageRecord messageRecord) {
    if (!messageRecord.isOutgoing() &&
            messageRecord.getRecipients().isSingleRecipient() &&
            !messageRecord.isSecure()) {
      final Recipients recipients = messageRecord.getRecipients();

      Recipient recipient = recipients.getPrimaryRecipient();
      String body = messageRecord.getBody().getBody();
      long threadId = messageRecord.getThreadId();

      if (!groupThread && !TelephonyUtil.isMyPhoneNumber(
              context, recipient.getNumber()) &&
              AutoInitiate.isValidAutoInitiateSituation(
                      context, masterSecret, recipient, body, threadId)) {
        AutoInitiate.exemptThread(context, threadId);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.ConversationActivity_initiate_secure_session_question);
        builder.setMessage(R.string.ConversationActivity_detected_smsecure_initiate_session_question);
        builder.setIconAttribute(R.attr.dialog_info_icon);
        builder.setCancelable(true);
        builder.setNegativeButton(R.string.No, null);
        builder.setPositiveButton(R.string.Yes, (dialog, which) -> KeyExchangeInitiator.initiate(context, masterSecret, recipients, true));
        builder.show();
      }
    }
  }

  private void setContactPhotoForRecipient(@NonNull Recipient recipient) {
    if (contactPhoto == null) return;
    contactPhoto.setAvatar(recipient, true);
    contactPhoto.setVisibility(View.VISIBLE);
  }

  private void handleKeyExchangeClicked() {
    if (masterSecret == null || messageRecord == null) {
      Log.w(TAG, "handleKeyExchangeClicked() called with null state");
      return;
    }
    new ReceiveKeyDialog(context, masterSecret, messageRecord).show();
  }

  private void handleLegacyKeyExchangeClicked() {
    if (recipient == null || messageRecord == null) return;

    KeyExchangeInitiator.initiate(context,
            masterSecret,
            RecipientFactory.getRecipientsFor(context, recipient, false),
            false,
            messageRecord.getSubscriptionId());

    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);
    smsDatabase.markAsProcessedKeyExchange(messageRecord.getId());
  }

  @Override
  public void onModified(final Recipient recipient) {
    Util.runOnMain(() -> {
      MessageRecord mr = messageRecord;
      if (mr == null) return;

      setBubbleState(mr);
      setContactPhoto(recipient);
      setGroupMessageStatus(mr, recipient);
    });
  }

  @Override
  public void onModified(final Recipients recipients) {
  }


  private class PassthroughClickListener implements View.OnLongClickListener, View.OnClickListener {

    @Override
    public boolean onLongClick(View v) {
      performLongClick();
      return true;
    }

    @Override
    public void onClick(View v) {
      performClick();
    }
  }

  private class ClickListener implements View.OnClickListener {
    private final @Nullable OnClickListener parent;

    public ClickListener(@Nullable OnClickListener parent) {
      this.parent = parent;
    }

    @Override
    public void onClick(View v) {
      if (messageRecord == null) return;

      if (!shouldInterceptClicks(messageRecord) && parent != null) {
        parent.onClick(v);
        return;
      }

      if (messageRecord.isFailed()) {
        Intent intent = new Intent(context, MessageDetailsActivity.class);
        intent.putExtra(MessageDetailsActivity.MASTER_SECRET_EXTRA, masterSecret);
        intent.putExtra(MessageDetailsActivity.MESSAGE_ID_EXTRA, messageRecord.getId());
        intent.putExtra(MessageDetailsActivity.THREAD_ID_EXTRA, messageRecord.getThreadId());
        intent.putExtra(MessageDetailsActivity.TYPE_EXTRA, MessageDatabase.SMS_TRANSPORT);

        Recipients recipients = conversationRecipients;
        if (recipients != null) {
          intent.putExtra(MessageDetailsActivity.RECIPIENTS_IDS_EXTRA, recipients.getIds());
        }

        context.startActivity(intent);

      } else if (messageRecord.isKeyExchange() &&
              !messageRecord.isOutgoing() &&
              !messageRecord.isProcessedKeyExchange() &&
              !messageRecord.isStaleKeyExchange() &&
              !messageRecord.isLegacyMessage()) {
        handleKeyExchangeClicked();

      } else if (shouldInterceptKeyExchangeMessage(messageRecord)) {
        handleLegacyKeyExchangeClicked();
      }
    }
  }
}
