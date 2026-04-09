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

package org.jimvixx.smsecure.components;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.jimvixx.smsecure.R;

public class DeliveryStatusView extends FrameLayout {

  @SuppressWarnings("unused")
  private static final String TAG = DeliveryStatusView.class.getSimpleName();

  private final ViewGroup pendingIndicatorContainer;
  private final ImageView sentIndicator;
  private final ImageView deliveredIndicator;
  private final PendingDotsView pendingIndicator;

  public DeliveryStatusView(Context context) {
    this(context, null);
  }

  public DeliveryStatusView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public DeliveryStatusView(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);

    inflate(context, R.layout.delivery_status_view, this);

    this.deliveredIndicator = findViewById(R.id.delivered_indicator);
    this.sentIndicator = findViewById(R.id.sent_indicator);
    this.pendingIndicatorContainer = findViewById(R.id.pending_indicator_container);

    final int iconColor;
    if (attrs != null) {
      try (TypedArray ta = context.getTheme()
              .obtainStyledAttributes(attrs, R.styleable.DeliveryStatusView, 0, 0)) {
        iconColor = ta.getColor(R.styleable.DeliveryStatusView_iconColor, Color.GRAY);
      }
    } else {
      iconColor = Color.GRAY;
    }

    deliveredIndicator.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
    sentIndicator.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);

    inflate(context, R.layout.conversation_item_pending, pendingIndicatorContainer);
    this.pendingIndicator = findViewById(R.id.pending_indicator);
    this.pendingIndicator.setDotColor(iconColor);
  }

  public void setNone() {
    setVisibility(View.GONE);
    pendingIndicator.stop();
  }

  public void setPending() {
    setVisibility(View.VISIBLE);
    pendingIndicatorContainer.setVisibility(View.VISIBLE);
    sentIndicator.setVisibility(View.GONE);
    deliveredIndicator.setVisibility(View.GONE);
    pendingIndicator.start();
  }

  public void setSent() {
    setVisibility(View.VISIBLE);
    pendingIndicatorContainer.setVisibility(View.GONE);
    sentIndicator.setVisibility(View.VISIBLE);
    deliveredIndicator.setVisibility(View.GONE);
    pendingIndicator.stop();
  }

  public void setDelivered() {
    setVisibility(View.VISIBLE);
    pendingIndicatorContainer.setVisibility(View.GONE);
    sentIndicator.setVisibility(View.GONE);
    deliveredIndicator.setVisibility(View.VISIBLE);
    pendingIndicator.stop();
  }
}