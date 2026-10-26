/*
 * Copyright (C) 2026 Jimvixx
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

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;

public final class MmsImageViewerActivity extends PassphraseRequiredActionBarActivity {
  public static final String EXTRA_MMS_ID = "mms_image_mms_id";
  public static final String EXTRA_PART_ID = "mms_image_part_id";
  public static final String EXTRA_UNIQUE_ID = "mms_image_unique_id";

  private long mmsId = -1;
  private long partId = -1;
  private long uniqueId = -1;
  @Nullable private MasterSecret masterSecret;
  @Nullable private MmsImageViewerLoader loader;
  @Nullable private MmsImageViewerLoader.Request request;
  @Nullable private ImageView imageView;
  @Nullable private ProgressBar progress;
  @Nullable private TextView messageView;
  @Nullable private Bitmap displayedBitmap;
  private long loadGeneration;
  private boolean resumedOnce;

  @Override
  protected void onCreate(@Nullable Bundle state, @NonNull MasterSecret secret) {
    super.onCreate(state, secret);
    setContentView(R.layout.activity_mms_image_viewer);
    masterSecret = secret;
    mmsId = getIntent().getLongExtra(EXTRA_MMS_ID, -1);
    partId = getIntent().getLongExtra(EXTRA_PART_ID, -1);
    uniqueId = getIntent().getLongExtra(EXTRA_UNIQUE_ID, -1);

    Toolbar toolbar = findViewById(R.id.mms_image_viewer_toolbar);
    toolbar.setTitle(R.string.MmsImageViewer__title);
    toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
    toolbar.setNavigationContentDescription(R.string.Close);
    toolbar.setNavigationOnClickListener(view -> finish());
    imageView = findViewById(R.id.mms_image_viewer_image);
    progress = findViewById(R.id.mms_image_viewer_progress);
    messageView = findViewById(R.id.mms_image_viewer_message);
    loader = new MmsImageViewerLoader(DatabaseFactory.getAttachmentDatabase(this),
            getResources().getDisplayMetrics());
    beginLoad();
  }

  private void beginLoad() {
    if (loader == null || masterSecret == null || isFinishing() || isDestroyed()) return;
    cancelLoadAndClearBitmap();
    long generation = ++loadGeneration;
    setLoadingState();
    request = loader.load(mmsId, partId, uniqueId, masterSecret, result -> {
      if (generation != loadGeneration || isFinishing() || isDestroyed() || masterSecret == null) {
        result.close();
        return;
      }
      request = null;
      Bitmap bitmap = result.takeBitmap();
      result.close();
      if (bitmap == null) {
        setUnavailableState();
        return;
      }
      displayedBitmap = bitmap;
      if (imageView != null) imageView.setImageBitmap(bitmap);
      if (progress != null) progress.setVisibility(View.GONE);
      if (messageView != null) messageView.setVisibility(View.GONE);
    });
  }

  private void setLoadingState() {
    if (imageView != null) imageView.setVisibility(View.VISIBLE);
    if (progress != null) progress.setVisibility(View.VISIBLE);
    if (messageView != null) messageView.setVisibility(View.GONE);
  }

  private void setUnavailableState() {
    if (progress != null) progress.setVisibility(View.GONE);
    if (messageView != null) {
      messageView.setText(R.string.MmsImageViewer__unavailable);
      messageView.setVisibility(View.VISIBLE);
    }
  }

  private void cancelLoadAndClearBitmap() {
    loadGeneration++;
    if (request != null) {
      request.cancel();
      request = null;
    }
    if (imageView != null) imageView.setImageDrawable(null);
    MmsImageThumbnailLoader.eraseAndRecycle(displayedBitmap);
    displayedBitmap = null;
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (resumedOnce && masterSecret != null && request == null && displayedBitmap == null) {
      beginLoad();
    }
    resumedOnce = true;
  }

  @Override
  protected void onPause() {
    cancelLoadAndClearBitmap();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    cancelLoadAndClearBitmap();
    masterSecret = null;
    super.onDestroy();
  }

  @Override
  public void onMasterSecretCleared() {
    masterSecret = null;
    cancelLoadAndClearBitmap();
    setUnavailableState();
    super.onMasterSecretCleared();
  }
}
