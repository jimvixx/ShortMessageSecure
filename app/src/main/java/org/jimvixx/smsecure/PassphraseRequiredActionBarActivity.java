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

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.PersistableBundle;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentSanitizer;
import androidx.fragment.app.Fragment;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.MasterSecretUtil;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.service.KeyCachingService;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.Util;

import java.util.Locale;

public abstract class PassphraseRequiredActionBarActivity extends BaseActionBarActivity implements MasterSecretListener {
  public static final String LOCALE_EXTRA = "locale_extra";
  private static final String TAG = PassphraseRequiredActionBarActivity.class.getSimpleName();
  private static final int STATE_NORMAL = 0;
  private static final int STATE_CREATE_PASSPHRASE = 1;
  private static final int STATE_PROMPT_PASSPHRASE = 2;
  private static final int STATE_UPGRADE_DATABASE = 3;
  private static final int STATE_WELCOME = 4;

  private BroadcastReceiver clearKeyReceiver;
  private boolean isVisible;
  private volatile boolean clearKeyHandled;
  private boolean passphraseActivityRegistered;
  private boolean stateRoutingHandled;

  @Nullable
  private static Intent getParcelableExtraCompat(@NonNull Intent intent, @NonNull String key) {
    return intent.getParcelableExtra(key);
  }

  @Override
  protected final void onCreate(@Nullable Bundle savedInstanceState) {
    Log.w(TAG, "onCreate(" + savedInstanceState + ")");
    onPreCreate();
    super.onCreate(savedInstanceState);
    afterSuperOnCreate(savedInstanceState);
  }

  @Override
  public final void onCreate(@Nullable Bundle savedInstanceState, @Nullable PersistableBundle persistentState) {
    Log.w(TAG, "onCreate(" + savedInstanceState + ", persistable)");
    onPreCreate();
    super.onCreate(savedInstanceState, persistentState);
    afterSuperOnCreate(savedInstanceState);
  }

  private void afterSuperOnCreate(@Nullable Bundle savedInstanceState) {
    KeyCachingService.enforceTimeoutIfExpired(this);

    final MasterSecret masterSecret = KeyCachingService.getMasterSecret(this);

    routeApplicationState(masterSecret);
    if (isFinishing() || isDestroyed()) return;

    if (masterSecret == null) {
      Log.w(TAG, "MasterSecret is null after routing — finishing activity");
      finish();
      return;
    }

    stateRoutingHandled = false;
    clearKeyHandled = false;
    initializeClearKeyReceiver();
    onCreate(savedInstanceState, masterSecret);
  }

  protected void onPreCreate() {
  }

  protected void onCreate(@Nullable Bundle savedInstanceState, @NonNull MasterSecret masterSecret) {
  }

  @Override
  protected void onStart() {
    super.onStart();

    if (!isFinishing() && !isDestroyed() && !passphraseActivityRegistered) {
      KeyCachingService.registerPassphraseActivityStarted(this);
      passphraseActivityRegistered = true;
      Log.w(TAG, "Registered PassphraseRequiredActionBarActivity as started");
    }
  }

  @Override
  protected void onResume() {
    Log.w(TAG, "onResume()");

    if (isFinishing() || isDestroyed()) {
      return;
    }

    KeyCachingService.enforceTimeoutIfExpired(this);

    if (KeyCachingService.getMasterSecret(this) == null) {
      routeApplicationState(null);
      return;
    }

    super.onResume();

    isVisible = true;
    clearKeyHandled = false;
  }

  @Override
  protected void onPause() {
    Log.w(TAG, "onPause()");
    isVisible = false;
    super.onPause();
  }

  @Override
  protected void onStop() {
    Log.w(TAG, "onStop()");

    if (passphraseActivityRegistered) {
      KeyCachingService.registerPassphraseActivityStopped(this);
      passphraseActivityRegistered = false;
      Log.w(TAG, "Registered PassphraseRequiredActionBarActivity as stopped");
    }

    super.onStop();
  }

  @Override
  protected void onDestroy() {
    Log.w(TAG, "onDestroy()");
    super.onDestroy();
    removeClearKeyReceiver(this);
  }

  @Override
  public void onMasterSecretCleared() {
    Log.w(TAG, "onMasterSecretCleared()");
    if (isVisible) routeApplicationState(null);
    else finish();
  }

  protected <T extends Fragment> void initFragment(@IdRes int target,
                                                   @NonNull T fragment,
                                                   @NonNull MasterSecret masterSecret) {
    initFragment(target, fragment, masterSecret, null);
  }

  protected <T extends Fragment> T initFragment(@IdRes int target,
                                                @NonNull T fragment,
                                                @NonNull MasterSecret masterSecret,
                                                @Nullable Locale locale) {
    return initFragment(target, fragment, masterSecret, locale, null);
  }

  protected <T extends Fragment> T initFragment(@IdRes int target,
                                                @NonNull T fragment,
                                                @NonNull MasterSecret masterSecret,
                                                @Nullable Locale locale,
                                                @Nullable Bundle extras) {
    Bundle args = new Bundle();
    args.putParcelable("master_secret", masterSecret);
    args.putSerializable(LOCALE_EXTRA, locale);

    if (extras != null) {
      args.putAll(extras);
    }

    fragment.setArguments(args);
    getSupportFragmentManager().beginTransaction()
            .replace(target, fragment)
            .commit();

    return fragment;
  }

  private void routeApplicationState(@Nullable MasterSecret masterSecret) {
    if (stateRoutingHandled) {
      Log.w(TAG, "routeApplicationState() already handled, ignoring");
      return;
    }

    final int state = getApplicationState(masterSecret);
    final Intent intent = getIntentForState(masterSecret, state);

    if (intent != null) {
      stateRoutingHandled = true;
      Log.w(TAG, "routeApplicationState() launching state=" + state + ", component=" + intent.getComponent());
      startActivity(intent);
      finish();
    }
  }

  private Intent getIntentForState(@Nullable MasterSecret masterSecret, int state) {
    Log.w(TAG, "routeApplicationState(), state: " + state);

    return switch (state) {
      case STATE_CREATE_PASSPHRASE -> getCreatePassphraseIntent();
      case STATE_PROMPT_PASSPHRASE -> getPromptPassphraseIntent();
      case STATE_UPGRADE_DATABASE -> getUpgradeDatabaseIntent(masterSecret);
      case STATE_WELCOME -> getWelcomeIntent();
      default -> null;
    };
  }

  private int getApplicationState(@Nullable MasterSecret masterSecret) {
    if (shouldDisplayWelcomeActivity()) {
      return STATE_WELCOME;
    } else if (!MasterSecretUtil.isPassphraseInitialized(this)) {
      return STATE_CREATE_PASSPHRASE;
    } else if (masterSecret == null) {
      return STATE_PROMPT_PASSPHRASE;
    } else if (DatabaseUpgradeActivity.isUpdate(this)) {
      return STATE_UPGRADE_DATABASE;
    } else {
      return STATE_NORMAL;
    }
  }

  private boolean shouldDisplayWelcomeActivity() {
    return SMSecurePreferences.isFirstRun(this) || Util.missingMandatoryPermissions(this);
  }

  private Intent getCreatePassphraseIntent() {
    Intent intent = getRoutedIntent(PassphraseCreateActivity.class, resolveNextIntentForRouting(), null);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    return intent;
  }

  private Intent getPromptPassphraseIntent() {
    Intent intent = getRoutedIntent(PassphrasePromptActivity.class, resolveNextIntentForRouting(), null);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    return intent;
  }

  private Intent getUpgradeDatabaseIntent(@Nullable MasterSecret masterSecret) {
    return getRoutedIntent(DatabaseUpgradeActivity.class, getConversationListIntent(), masterSecret);
  }

  private Intent getConversationListIntent() {
    return new Intent(this, ConversationListActivity.class);
  }

  private Intent getWelcomeIntent() {
    Intent intent = new Intent(this, WelcomeActivity.class);
    intent.putExtra(WelcomeActivity.EXTRA_NEXT_SCREEN, WelcomeActivity.NEXT_SCREEN_CONVERSATION_LIST);
    return intent;
  }

  @Nullable
  private Intent resolveNextIntentForRouting() {
    final Intent current = getIntent();
    if (current == null) return null;

    final Intent nested = getParcelableExtraCompat(current, PassphraseActivity.EXTRA_NEXT_INTENT);
    final Intent candidate = nested != null ? nested : current;

    final Intent sanitized = sanitizeNextIntent(candidate);
    if (sanitized == null) {
      Log.w(TAG, "resolveNextIntentForRouting(): sanitized candidate is null");
      return null;
    }

    if (isPassphraseFlowIntent(sanitized)) {
      Log.w(TAG, "resolveNextIntentForRouting(): ignoring passphrase/self intent " + sanitized.getComponent());
      return null;
    }

    Intent copy = new Intent(sanitized);
    copy.removeExtra(PassphraseActivity.EXTRA_NEXT_INTENT);
    return copy;
  }

  private boolean isPassphraseFlowIntent(@NonNull Intent intent) {
    final ComponentName component = intent.getComponent();
    if (component == null) return false;

    final String className = component.getClassName();
    return PassphrasePromptActivity.class.getName().equals(className)
            || PassphraseCreateActivity.class.getName().equals(className);
  }

  private Intent getRoutedIntent(Class<?> destination,
                                 @Nullable Intent nextIntent,
                                 @Nullable MasterSecret masterSecret) {
    final Intent intent = new Intent(this, destination);

    if (nextIntent != null) {
      intent.putExtra(PassphraseActivity.EXTRA_NEXT_INTENT, nextIntent);
    }

    if (masterSecret != null) {
      intent.putExtra("master_secret", masterSecret);
    }

    return intent;
  }

  @Nullable
  private Intent sanitizeNextIntent(@NonNull Intent unsafe) {
    if (unsafe.getComponent() == null) return null;

    IntentSanitizer sanitizer = new IntentSanitizer.Builder()
            .allowComponent(cn -> cn != null && getPackageName().equals(cn.getPackageName()))
            .allowFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .allowExtra(ConversationActivity.RECIPIENTS_EXTRA, long[].class)
            .allowExtra(ConversationActivity.THREAD_ID_EXTRA, Long.class)
            .allowExtra(ConversationActivity.IS_ARCHIVED_EXTRA, Boolean.class)
            .allowExtra(ConversationActivity.TEXT_EXTRA, String.class)
            .allowExtra(ConversationActivity.DISTRIBUTION_TYPE_EXTRA, Integer.class)
            .allowExtra(ConversationActivity.TIMING_EXTRA, Long.class)
            .allowExtra(ConversationActivity.LAST_SEEN_EXTRA, Long.class)
            .build();

    Intent sanitized = sanitizer.sanitizeByFiltering(unsafe);
    return sanitized.getComponent() != null ? sanitized : null;
  }

  private void initializeClearKeyReceiver() {
    Log.w(TAG, "initializeClearKeyReceiver()");

    clearKeyReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        final int reason = (intent != null)
                ? intent.getIntExtra(KeyCachingService.EXTRA_CLEAR_REASON, KeyCachingService.CLEAR_REASON_OTHER)
                : KeyCachingService.CLEAR_REASON_OTHER;

        Log.w(TAG, "onReceive() for clear key event, reason=" + reason);

        if (clearKeyHandled) {
          Log.w(TAG, "CLEAR_KEY_EVENT already handled -> ignoring");
          return;
        }
        clearKeyHandled = true;

        if (isFinishing() || isDestroyed()) {
          Log.w(TAG, "Activity finishing/destroyed -> ignoring clear key event");
          return;
        }

        if (reason == KeyCachingService.CLEAR_REASON_PANIC) {
          Log.w(TAG, "Panic clear -> exit and remove from recents");
          ExitActivity.exitAndRemoveFromRecentApps(PassphraseRequiredActionBarActivity.this);
          finish();
          return;
        }

        onMasterSecretCleared();
      }
    };

    IntentFilter filter = new IntentFilter(KeyCachingService.CLEAR_KEY_EVENT);

    ContextCompat.registerReceiver(
            this,
            clearKeyReceiver,
            filter,
            KeyCachingService.KEY_PERMISSION,
            null,
            ContextCompat.RECEIVER_NOT_EXPORTED
    );
  }

  private void removeClearKeyReceiver(Context context) {
    if (clearKeyReceiver != null) {
      try {
        context.unregisterReceiver(clearKeyReceiver);
      } catch (IllegalArgumentException e) {
        Log.w(TAG, "clearKeyReceiver already unregistered", e);
      } finally {
        clearKeyReceiver = null;
      }
    }
  }
}