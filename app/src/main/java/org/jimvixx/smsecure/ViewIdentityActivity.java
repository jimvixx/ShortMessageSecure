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

package org.jimvixx.smsecure;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;

import org.jimvixx.smsecure.crypto.IdentityKeyUtil;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;
import org.whispersystems.libsignal.IdentityKey;

public class ViewIdentityActivity extends BaseIdentityActivity {

  public static final String EXTRA_ENABLE_SCAN = "enable_scan";

  @Override
  protected void onCreate(@Nullable Bundle icicle, @NonNull MasterSecret masterSecret) {
    setContentView(R.layout.identity_activity);

    androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
    if (toolbar != null) setSupportActionBar(toolbar);

    var ab = getSupportActionBar();
    if (ab != null) {
      ab.setDisplayHomeAsUpEnabled(true);
      ab.setDisplayShowHomeEnabled(true);
      ab.setTitle(R.string.IdentityActivity__view_identity);
    }

    initBaseIdentityUi(icicle);
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  protected void bindBaseViews() {
    // Spoilers
    toggleFingerprint  = findViewById(R.id.toggle_fingerprint);
    sectionFingerprint = findViewById(R.id.section_fingerprint);

    toggleTextCode  = findViewById(R.id.toggle_text_code);
    sectionTextCode = findViewById(R.id.section_text_code);

    // Local content
    identityFingerprint = findViewById(R.id.identity_fingerprint);
    identityQr          = findViewById(R.id.identity_qr);
    identityTextCode    = findViewById(R.id.identity_text_code);

    // Local actions
    copyFingerprint  = findViewById(R.id.copy_fingerprint);
    shareFingerprint = findViewById(R.id.share_fingerprint);

    shareQrImage = findViewById(R.id.share_qr_image);

    copyTextCode  = findViewById(R.id.copy_text_code);
    shareTextCode = findViewById(R.id.share_text_code);
  }

  @Nullable
  @Override
  protected IdentityKey resolveLocalIdentityKey() {
    int subscriptionId = getIntent().getIntExtra(
            "subscription_id",
            SubscriptionManagerCompat.getDefaultMessagingSubscriptionId().or(-1)
    );
    return IdentityKeyUtil.getIdentityKey(this, subscriptionId);
  }

  @Override
  protected void afterBaseRendered() {
    // Hide remote section entirely
    CardView remoteFingerprintCard = findViewById(R.id.card_view_remote_fingerprint);
    if (remoteFingerprintCard != null) remoteFingerprintCard.setVisibility(View.GONE);

    // Hide scan section if disabled
    CardView scanQrCardLayout = findViewById(R.id.layout_verify_identity);
    if (!getIntent().getBooleanExtra(EXTRA_ENABLE_SCAN, false)) {
      if (scanQrCardLayout != null) scanQrCardLayout.setVisibility(View.GONE);
    }
  }
}