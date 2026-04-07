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

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.providers.PersistentBlobProvider;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.AppExecutors;
import org.jimvixx.smsecure.util.MediaUtil;
import org.jimvixx.smsecure.util.ViewUtil;

import java.io.IOException;
import java.io.InputStream;

/**
 * An activity to quickly share content with contacts.
 */
public class ShareActivity extends PassphraseRequiredActionBarActivity
        implements ShareFragment.ConversationSelectedListener {

  public static final String EXTRA_THREAD_ID = "thread_id";
  public static final String EXTRA_RECIPIENT_IDS = "recipient_ids";
  public static final String EXTRA_DISTRIBUTION_TYPE = "distribution_type";
  private static final String TAG = ShareActivity.class.getSimpleName();
  private final java.util.concurrent.Executor backgroundExecutor = AppExecutors.background();
  private final android.os.Handler mainHandler = AppExecutors.mainHandler();

  private MasterSecret masterSecret;
  private ViewGroup fragmentContainer;
  private View progressWheel;

  @Nullable
  private Uri resolvedExtra;
  @Nullable
  private String mimeType;

  private boolean isPassingAlongMedia;

  // Used to ignore stale async results when multiple intents arrive quickly.
  private int resolveGeneration = 0;

  @Override
  protected void onCreate(Bundle icicle, @NonNull MasterSecret masterSecret) {
    this.masterSecret = masterSecret;

    setContentView(R.layout.share_activity);

    initializeToolbar();

    fragmentContainer = findViewById(R.id.drawer_layout);
    progressWheel = findViewById(R.id.progress_wheel);

    initFragment(R.id.drawer_layout, new ShareFragment(), masterSecret);
    initializeMedia();
  }

  private void initializeToolbar() {
    Toolbar toolbar = findViewById(R.id.toolbar);
    if (toolbar == null) {
      return;
    }

    setSupportActionBar(toolbar);

    ActionBar ab = getSupportActionBar();
    if (ab != null) {
      ab.setDisplayHomeAsUpEnabled(true);
      ab.setDisplayShowHomeEnabled(true);
      ab.setTitle(R.string.ShareActivity_share_with);
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    initializeMedia();
  }

  @Override
  public void onResume() {
    super.onResume();
    org.jimvixx.smsecure.service.DirectShareShortcutsPublisher.refreshAsync(this);
  }

  @Override
  public void onPause() {
    super.onPause();

    if (!isPassingAlongMedia &&
            resolvedExtra != null &&
            PersistentBlobProvider.isAuthority(this, resolvedExtra)) {
      PersistentBlobProvider.getInstance(this).delete(resolvedExtra);
    }

    if (!isFinishing()) {
      finish();
    }
  }

  private void initializeMedia() {
    final int myGeneration = ++resolveGeneration;

    isPassingAlongMedia = false;

    Intent intent = getIntent();
    Uri streamExtra = intent.getParcelableExtra(Intent.EXTRA_STREAM);

    mimeType = getMimeType(streamExtra);

    if (streamExtra != null && isLocalShareUri(streamExtra)) {
      isPassingAlongMedia = true;
      resolvedExtra = streamExtra;
      handleResolvedMedia(intent, false);
      return;
    }

    showLoadingState();
    resolveMediaAsync(streamExtra, mimeType, myGeneration);
  }

  private void showLoadingState() {
    if (fragmentContainer != null) {
      fragmentContainer.setVisibility(View.GONE);
    }

    if (progressWheel != null) {
      progressWheel.setVisibility(View.VISIBLE);
    }
  }

  private void showResolvedState(boolean animate) {
    if (animate) {
      if (fragmentContainer != null) {
        ViewUtil.fadeIn(fragmentContainer, 300);
      }
      if (progressWheel != null) {
        ViewUtil.fadeOut(progressWheel, 300);
      }
    } else {
      if (fragmentContainer != null) {
        fragmentContainer.setVisibility(View.VISIBLE);
      }
      if (progressWheel != null) {
        progressWheel.setVisibility(View.GONE);
      }
    }
  }

  private boolean isLocalShareUri(@NonNull Uri uri) {
    return PersistentBlobProvider.isAuthority(this, uri);
  }

  private void resolveMediaAsync(@Nullable Uri streamUri,
                                 @Nullable String mimeType,
                                 int generation) {
    backgroundExecutor.execute(() -> {
      Uri resolvedUri = null;
      String safeMimeType = mimeType != null ? mimeType : "application/octet-stream";

      try {
        if (streamUri != null) {
          InputStream input = getContentResolver().openInputStream(streamUri);

          if (input != null) {
            try (input) {
              resolvedUri = PersistentBlobProvider.getInstance(this)
                      .create(masterSecret, input, safeMimeType);
            } catch (IOException ignored) {
              // Keep resolvedUri as null.
            }
          }
        }
      } catch (IOException ioe) {
        Log.w(TAG, ioe);
      } catch (RuntimeException re) {
        Log.w(TAG, re);
        resolvedUri = null;
      }

      final Uri finalResolvedUri = resolvedUri;

      mainHandler.post(() -> {
        if (generation != resolveGeneration) {
          return;
        }

        resolvedExtra = finalResolvedUri;
        handleResolvedMedia(getIntent(), true);
      });
    });
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    menu.clear();
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.share, menu);
    super.onPrepareOptionsMenu(menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    int id = item.getItemId();

    if (id == R.id.menu_new_message) {
      handleNewConversation();
      return true;
    }

    if (id == android.R.id.home) {
      finish();
      return true;
    }

    return super.onOptionsItemSelected(item);
  }

  private void handleNewConversation() {
    Intent intent = getBaseShareIntent(NewConversationActivity.class);
    isPassingAlongMedia = true;
    startActivity(intent);
  }

  @Override
  public void onCreateConversation(long threadId,
                                   @NonNull Recipients recipients,
                                   int distributionType) {
    createConversation(threadId, recipients, distributionType);
  }

  private void handleResolvedMedia(@NonNull Intent intent, boolean animate) {
    long threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1);
    long[] recipientIds = intent.getLongArrayExtra(EXTRA_RECIPIENT_IDS);
    int distributionType = intent.getIntExtra(EXTRA_DISTRIBUTION_TYPE, -1);

    boolean hasResolvedDestination =
            threadId != -1 &&
                    recipientIds != null &&
                    distributionType != -1;

    if (!hasResolvedDestination) {
      showResolvedState(animate);
      return;
    }

    createConversation(
            threadId,
            RecipientFactory.getRecipientsForIds(this, recipientIds, true),
            distributionType
    );
  }

  private void createConversation(long threadId,
                                  @NonNull Recipients recipients,
                                  int distributionType) {
    Intent intent = getBaseShareIntent(ConversationActivity.class);
    intent.putExtra(ConversationActivity.RECIPIENTS_EXTRA, recipients.getIds());
    intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, threadId);
    intent.putExtra(ConversationActivity.DISTRIBUTION_TYPE_EXTRA, distributionType);

    isPassingAlongMedia = true;
    startActivity(intent);
  }

  private @NonNull Intent getBaseShareIntent(@NonNull Class<?> target) {
    Intent intent = new Intent(this, target);
    String textExtra = getIntent().getStringExtra(Intent.EXTRA_TEXT);

    intent.putExtra(ConversationActivity.TEXT_EXTRA, textExtra);

    if (resolvedExtra != null) {
      intent.setDataAndType(resolvedExtra, mimeType);
    }

    return intent;
  }

  private @Nullable String getMimeType(@Nullable Uri uri) {
    if (uri != null) {
      String detected = MediaUtil.getMimeType(getApplicationContext(), uri);
      if (detected != null) {
        return detected;
      }
    }

    return MediaUtil.getCorrectedMimeType(getIntent().getType());
  }
}