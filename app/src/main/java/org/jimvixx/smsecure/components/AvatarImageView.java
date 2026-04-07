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
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.provider.ContactsContract;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.color.MaterialColor;
import org.jimvixx.smsecure.contacts.avatars.ContactColors;
import org.jimvixx.smsecure.contacts.avatars.ContactPhotoFactory;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.SessionUtil;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.service.KeyCachingService;
import org.jimvixx.smsecure.util.dualsim.SubscriptionInfoCompat;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class AvatarImageView extends AppCompatImageView {

  private static final ExecutorService BADGE_EXECUTOR =
          Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AvatarBadgeResolver");
            t.setDaemon(true);
            return t;
          });

  private static final AtomicInteger BADGE_SEQ = new AtomicInteger(1);

  private boolean inverted;
  private boolean showBadge;

  private @Nullable Future<?> badgeFuture;
  private int badgeRequestId;

  public AvatarImageView(Context context) {
    super(context);
    setScaleType(ScaleType.CENTER_CROP);
  }

  public AvatarImageView(Context context, AttributeSet attrs) {
    super(context, attrs);
    setScaleType(ScaleType.CENTER_CROP);

    if (attrs == null) return;

    TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.AvatarImageView);
    try {
      inverted = ta.getBoolean(R.styleable.AvatarImageView_inverted, false);
      showBadge = ta.getBoolean(R.styleable.AvatarImageView_showBadge, false);
    } finally {
      ta.recycle();
    }
  }

  public void setAvatar(final @Nullable Recipients recipients, boolean quickContactEnabled) {
    cancelBadgeTask();

    if (recipients != null) {
      Context context = getContext();
      MasterSecret masterSecret = KeyCachingService.getMasterSecret(context);
      MaterialColor backgroundColor = recipients.getColor();

      setImageDrawable(recipients.getContactPhoto()
              .asDrawable(getContext(),
                      backgroundColor.toConversationColor(getContext()),
                      inverted));
      setAvatarClickHandler(recipients, quickContactEnabled);
      setTag(recipients);

      if (showBadge) {
        scheduleBadgeResolution(context, masterSecret, recipients);
      }

    } else {
      setImageDrawable(ContactPhotoFactory.getDefaultContactPhoto(null)
              .asDrawable(getContext(),
                      ContactColors.UNKNOWN_COLOR.toConversationColor(getContext()),
                      inverted));
      setOnClickListener(null);
      setTag(null);
    }
  }

  public void setAvatar(@Nullable Recipient recipient, boolean quickContactEnabled) {
    if (recipient == null) {
      setAvatar((Recipients) null, quickContactEnabled);
      return;
    }

    setAvatar(RecipientFactory.getRecipientsFor(getContext(), recipient, true), quickContactEnabled);
  }

  private void setAvatarClickHandler(final Recipients recipients, boolean quickContactEnabled) {
    if (!recipients.isGroupRecipient() && quickContactEnabled) {
      setOnClickListener(v -> {
        Recipient recipient = recipients.getPrimaryRecipient();

        if (recipient.getContactUri() != null) {
          ContactsContract.QuickContact.showQuickContact(getContext(),
                  AvatarImageView.this,
                  recipient.getContactUri(),
                  ContactsContract.QuickContact.MODE_LARGE,
                  null);
        } else {
          final Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
          intent.putExtra(ContactsContract.Intents.Insert.PHONE, recipient.getNumber());
          intent.setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE);
          getContext().startActivity(intent);
        }
      });
    } else {
      setOnClickListener(null);
    }
  }

  private void scheduleBadgeResolution(@NonNull Context context,
                                       @Nullable MasterSecret masterSecret,
                                       @NonNull Recipients recipients) {
    final int requestId = BADGE_SEQ.getAndIncrement();
    badgeRequestId = requestId;

    final Context appContext = context.getApplicationContext();

    badgeFuture = BADGE_EXECUTOR.submit(() -> {
      final List<SubscriptionInfoCompat> activeSubscriptions =
              SubscriptionManagerCompat.from(appContext).getActiveSubscriptionInfoList();

      boolean isSecure = false;
      if (masterSecret != null) {
        Recipient primary = recipients.getPrimaryRecipient();
        isSecure = SessionUtil.hasAtLeastOneSession(appContext,
                masterSecret,
                primary.getNumber(),
                activeSubscriptions);
      }

      final boolean finalIsSecure = isSecure;

      post(() -> {
        if (badgeRequestId != requestId) return;
        if (getTag() != recipients) return;
        if (!finalIsSecure) return;

        Drawable badge = ContextCompat.getDrawable(getContext(), R.drawable.badge_drawable);
        Drawable base = getDrawable();
        if (badge != null && base != null) {
          setImageDrawable(new LayerDrawable(new Drawable[]{base, badge}));
        }
      });
    });
  }

  private void cancelBadgeTask() {
    if (badgeFuture != null) {
      badgeFuture.cancel(true);
      badgeFuture = null;
    }
  }
}
