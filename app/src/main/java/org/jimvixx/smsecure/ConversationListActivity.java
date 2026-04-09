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

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.loader.app.LoaderManager;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.notifications.MessageNotifier;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.service.KeyCachingService;
import org.jimvixx.smsecure.util.AppExecutors;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.ServiceUtil;
import org.jimvixx.smsecure.util.dualsim.SubscriptionInfoCompat;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;

import java.util.Collections;
import java.util.List;

public class ConversationListActivity extends PassphraseRequiredActionBarActivity
        implements ConversationListFragment.ConversationSelectedListener {

  private static final String TAG = ConversationListActivity.class.getSimpleName();

  private final Handler contactsHandler = new Handler(Looper.getMainLooper());

  @Nullable
  private ConversationListFragment fragment;

  private final Runnable refreshAfterContactsChange = new Runnable() {
    @Override
    public void run() {
      Log.w(TAG, "Contacts changed, refreshing conversation list via Loader");
      RecipientFactory.clearCache();

      if (fragment != null && fragment.isAdded()) {
        LoaderManager.getInstance(fragment).restartLoader(0, null, fragment);
      }
    }
  };

  @Nullable
  private ContentObserver observer;

  @Nullable
  private MasterSecret masterSecret;

  @NonNull
  private List<SubscriptionInfoCompat> activeSubscriptions = Collections.emptyList();

  @Nullable
  private View titleContainer;
  @Nullable
  private AppCompatEditText searchEdit;
  @Nullable
  private ImageView actionIcon;

  private boolean searchVisible = false;

  @Nullable
  private OnBackPressedCallback searchBackCallback;

  @Override
  protected void onCreate(Bundle icicle, @NonNull MasterSecret masterSecret) {
    this.masterSecret = masterSecret;

    refreshActiveSubscriptions();

    setContentView(R.layout.conversation_list_activity);

    initializeToolbar();
    initializeBackBehavior();

    fragment = initFragment(R.id.fragment_content,
            new ConversationListFragment(),
            masterSecret,
            getCurrentLocale());

    initializeContactUpdatesReceiver();
  }

  @Override
  protected void onResume() {
    super.onResume();

    refreshActiveSubscriptions();
    invalidateOptionsMenu();

    org.jimvixx.smsecure.service.DirectShareShortcutsPublisher.refreshAsync(this);
  }

  private void refreshActiveSubscriptions() {
    try {
      activeSubscriptions = SubscriptionManagerCompat.from(this).updateActiveSubscriptionInfoList();
      Log.w(TAG, "Active subscriptions refreshed: " + activeSubscriptions.size());
    } catch (Throwable t) {
      Log.w(TAG, "Failed to refresh active subscriptions", t);
      activeSubscriptions = Collections.emptyList();
    }
  }

  private void initializeToolbar() {
    Toolbar toolbar = findViewById(R.id.toolbar);
    if (toolbar == null) return;

    setSupportActionBar(toolbar);

    this.titleContainer = findViewById(R.id.title_container);
    this.searchEdit = findViewById(R.id.search_view);
    this.actionIcon = findViewById(R.id.action_icon);

    if (actionIcon != null) {
      actionIcon.setOnClickListener(v -> {
        if (searchVisible) hideSearch(true);
        else showSearch();
      });
    }

    if (searchEdit != null) {
      searchEdit.setPaintFlags(searchEdit.getPaintFlags() & ~android.graphics.Paint.UNDERLINE_TEXT_FLAG);

      searchEdit.addTextChangedListener(new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
          if (fragment != null) fragment.setQueryFilter(s == null ? "" : s.toString());
        }
      });
    }

    updateActionIcon();
  }

  private void initializeBackBehavior() {
    searchBackCallback = new OnBackPressedCallback(false) {
      @Override
      public void handleOnBackPressed() {
        hideSearch(true);
      }
    };

    getOnBackPressedDispatcher().addCallback(this, searchBackCallback);
  }

  private void updateActionIcon() {
    if (actionIcon == null) return;

    if (searchVisible) {
      actionIcon.setImageResource(R.drawable.ic_close);
      actionIcon.setContentDescription(getString(R.string.Clear));
    } else {
      actionIcon.setImageResource(R.drawable.ic_magnify);
      actionIcon.setContentDescription(getString(R.string.Search));
    }
  }

  private void showSearch() {
    if (searchEdit == null) return;

    searchVisible = true;
    if (searchBackCallback != null) searchBackCallback.setEnabled(true);

    updateActionIcon();

    if (titleContainer != null) {
      titleContainer.animate()
              .alpha(0f)
              .setDuration(120)
              .withEndAction(() -> titleContainer.setVisibility(View.GONE))
              .start();
    }

    searchEdit.setAlpha(0f);
    searchEdit.setVisibility(View.VISIBLE);
    searchEdit.animate()
            .alpha(1f)
            .setDuration(140)
            .start();

    searchEdit.requestFocus();
    ServiceUtil.getInputMethodManager(this).showSoftInput(searchEdit, 0);
  }

  private void hideSearch(boolean clearText) {
    if (searchEdit == null) return;

    searchVisible = false;
    if (searchBackCallback != null) searchBackCallback.setEnabled(false);

    updateActionIcon();

    ServiceUtil.getInputMethodManager(this)
            .hideSoftInputFromWindow(searchEdit.getWindowToken(), 0);

    searchEdit.animate()
            .alpha(0f)
            .setDuration(120)
            .withEndAction(() -> {
              searchEdit.setVisibility(View.GONE);
              searchEdit.setAlpha(1f);
            })
            .start();

    if (titleContainer != null) {
      titleContainer.setAlpha(0f);
      titleContainer.setVisibility(View.VISIBLE);
      titleContainer.animate()
              .alpha(1f)
              .setDuration(140)
              .start();
    }

    if (clearText) {
      searchEdit.setText("");
      if (fragment != null) fragment.resetQueryFilter();
    }

    searchEdit.clearFocus();
  }

  private void closeSearchIfOpen() {
    if (searchVisible) hideSearch(true);
  }

  @Override
  public void onDestroy() {
    if (observer != null) {
      try {
        getContentResolver().unregisterContentObserver(observer);
      } catch (Throwable t) {
        Log.w(TAG, "Failed to unregister contact observer (ignored)", t);
      } finally {
        observer = null;
      }
    }
    super.onDestroy();
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuInflater inflater = this.getMenuInflater();
    menu.clear();

    inflater.inflate(R.menu.conversation_list_menu, menu);

    MenuItem clearPassphrase = menu.findItem(R.id.menu_lock);
    if (clearPassphrase != null) {
      clearPassphrase.setVisible(!SMSecurePreferences.isPasswordDisabled(this));
    }

    inflateViewIdentities(menu);

    super.onPrepareOptionsMenu(menu);
    return true;
  }

  private void inflateViewIdentities(@NonNull Menu menu) {
    MenuItem singleIdentity = menu.findItem(R.id.menu_my_identity);
    MenuItem dualSimItem = menu.findItem(R.id.menu_my_identity_dual_sim);

    if (activeSubscriptions.size() > 1) {
      if (singleIdentity != null) singleIdentity.setVisible(false);

      if (dualSimItem != null) {
        dualSimItem.setVisible(true);
        SubMenu identitiesMenu = dualSimItem.getSubMenu();
        if (identitiesMenu != null) {
          identitiesMenu.clear();

          for (SubscriptionInfoCompat subscriptionInfo : activeSubscriptions) {
            final int subscriptionId = subscriptionInfo.getSubscriptionId();
            identitiesMenu.add(Menu.NONE, Menu.NONE, Menu.NONE, subscriptionInfo.getDisplayName())
                    .setOnMenuItemClickListener(item -> {
                      closeSearchIfOpen();
                      handleMyIdentity(subscriptionId);
                      return true;
                    });
          }
        }
      }
    } else {
      if (dualSimItem != null) dualSimItem.setVisible(false);
      if (singleIdentity != null) singleIdentity.setVisible(true);
    }
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();

    if (id == R.id.menu_archived_conversations) {
      closeSearchIfOpen();
      handleSwitchToArchive();
      return true;
    } else if (id == R.id.menu_new_group) {
      closeSearchIfOpen();
      createGroup();
      return true;
    } else if (id == R.id.menu_settings) {
      closeSearchIfOpen();
      handleDisplaySettings();
      return true;
    } else if (id == R.id.menu_lock) {
      closeSearchIfOpen();
      handleClearPassphrase();
      return true;
    } else if (id == R.id.menu_mark_all_read) {
      closeSearchIfOpen();
      handleMarkAllRead();
      return true;
    } else if (id == R.id.menu_import_export) {
      closeSearchIfOpen();
      handleImportExport();
      return true;
    } else if (id == R.id.menu_my_identity) {
      closeSearchIfOpen();
      handleMyIdentity();
      return true;
    } else if (id == R.id.menu_blocked_contacts) {
      closeSearchIfOpen();
      handleBlockedContacts();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private void handleBlockedContacts() {
    startActivity(new Intent(this, BlockedContactsActivity.class));
  }

  @Override
  public void onCreateConversation(long threadId, Recipients recipients, int distributionType, long lastSeen) {
    closeSearchIfOpen();

    Intent intent = new Intent(this, ConversationActivity.class);
    intent.putExtra(ConversationActivity.RECIPIENTS_EXTRA, recipients.getIds());
    intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, threadId);
    intent.putExtra(ConversationActivity.DISTRIBUTION_TYPE_EXTRA, distributionType);
    intent.putExtra(ConversationActivity.TIMING_EXTRA, System.currentTimeMillis());
    intent.putExtra(ConversationActivity.LAST_SEEN_EXTRA, lastSeen);

    startActivity(intent);
    overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out);
  }

  @Override
  public void onSwitchToArchive() {
    closeSearchIfOpen();
    Intent intent = new Intent(this, ConversationListArchiveActivity.class);
    startActivity(intent);
  }

  private void createGroup() {
    Intent intent = new Intent(this, GroupCreateActivity.class);
    startActivity(intent);
  }

  private void handleSwitchToArchive() {
    onSwitchToArchive();
  }

  private void handleDisplaySettings() {
    Intent preferencesIntent = new Intent(this, ApplicationPreferencesActivity.class);
    startActivity(preferencesIntent);
  }

  private void handleClearPassphrase() {
    Intent intent = new Intent(this, KeyCachingService.class);
    intent.setAction(KeyCachingService.CLEAR_KEY_ACTION);
    intent.putExtra(KeyCachingService.EXTRA_CLEAR_REASON, KeyCachingService.CLEAR_REASON_USER);
    startService(intent);
  }

  private void handleImportExport() {
    startActivity(new Intent(this, ImportExportActivity.class));
  }

  private void handleMyIdentity() {
    if (activeSubscriptions.isEmpty()) {
      Log.w(TAG, "No active subscriptions; cannot open identity");
      Toast.makeText(this, "No active subscriptions", Toast.LENGTH_SHORT).show();
      return;
    }

    int subscriptionId = activeSubscriptions.get(0).getSubscriptionId();
    handleMyIdentity(subscriptionId);
  }

  private void handleMyIdentity(int subscriptionId) {
    Intent intent = new Intent(this, ViewIdentityActivity.class);
    intent.putExtra(ViewIdentityActivity.EXTRA_ENABLE_SCAN, false);
    intent.putExtra("subscription_id", subscriptionId);
    startActivity(intent);
  }

  private void handleMarkAllRead() {
    final Context appContext = getApplicationContext();
    final MasterSecret secret = masterSecret;

    AppExecutors.DB.execute(() -> {
      DatabaseFactory.getThreadDatabase(appContext).setAllThreadsRead();
      if (secret != null) {
        MessageNotifier.updateNotification(appContext, secret);
      }
    });
  }

  private boolean hasContactsPermission() {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED;
  }

  private void initializeContactUpdatesReceiver() {
    if (!hasContactsPermission()) {
      Log.w(TAG, "Skipping contact observer: missing READ_CONTACTS/WRITE_CONTACTS");
      return;
    }

    observer = new ContentObserver(contactsHandler) {
      @Override
      public void onChange(boolean selfChange, @Nullable Uri uri) {
        contactsHandler.removeCallbacks(refreshAfterContactsChange);
        contactsHandler.postDelayed(refreshAfterContactsChange, 500);
      }

      @Override
      public void onChange(boolean selfChange) {
        onChange(selfChange, null);
      }
    };

    try {
      getContentResolver().registerContentObserver(
              ContactsContract.Contacts.CONTENT_URI,
              true,
              observer);
    } catch (SecurityException e) {
      Log.w(TAG, "Failed to register Contacts observer (permission/provider restriction).", e);
      observer = null;
    } catch (Throwable t) {
      Log.w(TAG, "Failed to register Contacts observer (unexpected).", t);
      observer = null;
    }
  }
}