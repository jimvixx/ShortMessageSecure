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
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;

/**
 * An activity to quickly share content with contacts.
 */
public class ShareActivity extends PassphraseRequiredActionBarActivity
        implements ShareFragment.ConversationSelectedListener {

  public static final String EXTRA_THREAD_ID = "thread_id";
  public static final String EXTRA_RECIPIENT_IDS = "recipient_ids";
  public static final String EXTRA_DISTRIBUTION_TYPE = "distribution_type";

  @Override
  protected void onCreate(Bundle icicle, @NonNull MasterSecret masterSecret) {
    setContentView(R.layout.share_activity);

    initializeToolbar();

    initFragment(R.id.drawer_layout, new ShareFragment(), masterSecret);
    handleResolvedDestination(getIntent());
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
    handleResolvedDestination(intent);
  }

  @Override
  public void onResume() {
    super.onResume();
    org.jimvixx.smsecure.service.DirectShareShortcutsPublisher.refreshAsync(this);
  }

  @Override
  public void onPause() {
    super.onPause();

    if (!isFinishing()) {
      finish();
    }
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
    startActivity(intent);
  }

  @Override
  public void onCreateConversation(long threadId,
                                   @NonNull Recipients recipients,
                                   int distributionType) {
    createConversation(threadId, recipients, distributionType);
  }

  private void handleResolvedDestination(@NonNull Intent intent) {
    long threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1);
    long[] recipientIds = intent.getLongArrayExtra(EXTRA_RECIPIENT_IDS);
    int distributionType = intent.getIntExtra(EXTRA_DISTRIBUTION_TYPE, -1);

    boolean hasResolvedDestination =
            threadId != -1 &&
                    recipientIds != null &&
                    distributionType != -1;

    if (!hasResolvedDestination) {
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

    startActivity(intent);
  }

  private @NonNull Intent getBaseShareIntent(@NonNull Class<?> target) {
    Intent intent = new Intent(this, target);
    String textExtra = getIntent().getStringExtra(Intent.EXTRA_TEXT);

    intent.putExtra(ConversationActivity.TEXT_EXTRA, textExtra);

    return intent;
  }
}
