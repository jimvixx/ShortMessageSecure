/*
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2013 Open Whisper Systems
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

import static org.jimvixx.smsecure.TransportOption.Type;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Browser;
import android.provider.ContactsContract;
import android.telephony.SmsMessage;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Pair;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.View.OnKeyListener;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import org.jimvixx.smsecure.color.MaterialColor;
import org.jimvixx.smsecure.components.ComposeText;
import org.jimvixx.smsecure.components.InputAwareLayout;
import org.jimvixx.smsecure.components.KeyboardAwareLinearLayout.OnKeyboardShownListener;
import org.jimvixx.smsecure.components.SendButton;
import org.jimvixx.smsecure.components.SendButtonController;
import org.jimvixx.smsecure.components.emoji.EmojiDrawer;
import org.jimvixx.smsecure.components.emoji.EmojiDrawer.EmojiEventListener;
import org.jimvixx.smsecure.components.emoji.EmojiToggle;
import org.jimvixx.smsecure.crypto.KeyExchangeInitiator;
import org.jimvixx.smsecure.crypto.MasterCipher;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.SecurityEvent;
import org.jimvixx.smsecure.crypto.SessionUtil;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.DraftDatabase;
import org.jimvixx.smsecure.database.DraftDatabase.Draft;
import org.jimvixx.smsecure.database.DraftDatabase.Drafts;
import org.jimvixx.smsecure.database.IdentityDatabase;
import org.jimvixx.smsecure.database.MessageColumns.Types;
import org.jimvixx.smsecure.database.RecipientPreferenceDatabase.RecipientsPreferences;
import org.jimvixx.smsecure.database.ThreadDatabase;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.notifications.MessageNotifier;
import org.jimvixx.smsecure.permissions.Permissions;
import org.jimvixx.smsecure.protocol.AutoInitiate;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.RecipientFormattingException;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.recipients.Recipients.RecipientsModifiedListener;
import org.jimvixx.smsecure.service.KeyCachingService;
import org.jimvixx.smsecure.sms.MessageSender;
import org.jimvixx.smsecure.sms.OutgoingEncryptedMessage;
import org.jimvixx.smsecure.sms.OutgoingTextMessage;
import org.jimvixx.smsecure.util.AppExecutors;
import org.jimvixx.smsecure.util.CharacterCalculator.CharacterState;
import org.jimvixx.smsecure.util.Dialogs;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.TelephonyUtil;
import org.jimvixx.smsecure.util.Util;
import org.jimvixx.smsecure.util.ViewUtil;
import org.jimvixx.smsecure.util.concurrent.ListenableFuture;
import org.jimvixx.smsecure.util.concurrent.SettableFuture;
import org.jimvixx.smsecure.util.dualsim.SubscriptionInfoCompat;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;
import org.jimvixx.smsecure.util.views.Stub;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.List;

public class ConversationActivity extends PassphraseRequiredActionBarActivity
        implements ConversationFragment.ConversationFragmentListener,
        RecipientsModifiedListener,
        OnKeyboardShownListener,
        ComposeText.MediaListener {

  public static final String RECIPIENTS_EXTRA = "recipients";
  public static final String THREAD_ID_EXTRA = "thread_id";
  public static final String IS_ARCHIVED_EXTRA = "is_archived";
  public static final String TEXT_EXTRA = "draft_text";
  public static final String DISTRIBUTION_TYPE_EXTRA = "distribution_type";
  public static final String TIMING_EXTRA = "timing";
  public static final String LAST_SEEN_EXTRA = "last_seen";

  private static final String TAG = ConversationActivity.class.getSimpleName();

  private final java.util.concurrent.Executor backgroundExecutor = AppExecutors.background();
  private final android.os.Handler mainHandler = AppExecutors.mainHandler();
  protected ComposeText composeText;
  protected ConversationTitleView titleView;

  private ActivityResultLauncher<Intent> addContactLauncher;
  private MasterSecret masterSecret;
  private SendButton sendButton;
  private SendButtonController sendButtonController;
  private TextView charactersLeft;
  private ConversationFragment fragment;
  private Button unblockButton;
  private InputAwareLayout container;
  private View composePanel;
  private Toolbar toolbar;
  private BroadcastReceiver securityUpdateReceiver;
  private boolean securityUpdateReceiverRegistered;
  private Stub<EmojiDrawer> emojiDrawerStub;
  private EmojiToggle emojiToggle;
  private Recipients recipients;
  private long threadId;
  private int distributionType;
  private boolean isEncryptedConversation;
  private boolean archived;
  private List<SubscriptionInfoCompat> activeSubscriptions;

  @Override
  protected void onCreate(Bundle state, @NonNull MasterSecret masterSecret) {
    Log.w(TAG, "onCreate()");
    this.masterSecret = masterSecret;
    this.activeSubscriptions = SubscriptionManagerCompat.from(this).getActiveSubscriptionInfoList();

    setContentView(R.layout.conversation_activity);

    initializeToolbar();
    initializeActivityResultLaunchers();

    fragment = initFragment(R.id.fragment_content, new ConversationFragment(),
            masterSecret, getCurrentLocale());

    initializeReceivers();
    initializeActionBar();
    initializeViews();
    initializeBackPressedCallback();
    initializeResources();
    initializeSecurity();
    updateRecipientPreferences();
    initializeDraft();
  }

  private boolean isCurrentRecipientVerified() {
    if (!isSingleConversation()) return false;
    if (recipients == null) return false;

    Recipient p = recipients.getPrimaryRecipient();

    long recipientId = p.getRecipientId();
    if (recipientId <= 0) return false;

    IdentityDatabase db = DatabaseFactory.getIdentityDatabase(this);
    return db != null && db.isVerified(recipientId);
  }

  private void initializeToolbar() {
    toolbar = findViewById(R.id.toolbar);
    if (toolbar == null) return;

    setSupportActionBar(toolbar);

    ActionBar ab = getSupportActionBar();
    if (ab != null) {
      ab.setDisplayHomeAsUpEnabled(true);
      ab.setDisplayShowHomeEnabled(true);
      ab.setDisplayShowTitleEnabled(true);
      ab.setTitle(R.string.app_name);
    }
  }

  private void initializeActivityResultLaunchers() {
    addContactLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                      if (result.getResultCode() == Activity.RESULT_OK) {
                        Log.w(TAG, "Contact add/edit finished OK");
                      }
                    });

  }

  private void initializeBackPressedCallback() {
    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
      @Override
      public void handleOnBackPressed() {
        Log.w(TAG, "System back pressed");

        if (container != null && container.isInputOpen()) {
          container.hideCurrentInput(composeText);

          if (composeText != null) {
            composeText.postDelayed(() -> {
              if (container != null && container.isInputOpen()) {
                Log.w(TAG, "Input is still reported as open after hideCurrentInput(), navigating back");
                navigateBackFromConversation();
              }
            }, 120);
          } else {
            navigateBackFromConversation();
          }

          return;
        }

        navigateBackFromConversation();
      }
    });
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);

    Log.w(TAG, "onNewIntent()");

    if (isFinishing()) {
      Log.w(TAG, "Activity is finishing...");
      return;
    }

    if (!Util.isEmpty(composeText)) {
      saveDraft();
      if (composeText != null) composeText.setText("");
    }

    setIntent(intent);
    initializeResources();
    initializeSecurity();
    updateRecipientPreferences();
    initializeDraft();

    if (fragment != null) fragment.onNewIntent();
  }

  @Override
  protected void onResume() {
    super.onResume();

    initializeEnabledCheck();

    if (composeText != null && sendButtonController != null) {
      composeText.setTransport(sendButtonController.getSelectedTransport());
    }

    if (titleView != null) titleView.setTitle(recipients);
    if (recipients != null) {
      setActionBarColor(recipients.getColor());
      setBlockedUserState(recipients);
    }

    calculateCharactersRemaining();

    MessageNotifier.setVisibleThread(threadId);
    markThreadAsRead();

    supportInvalidateOptionsMenu();
  }

  @Override
  protected void onPause() {
    super.onPause();
    MessageNotifier.setVisibleThread(-1L);
    if (isFinishing()) overridePendingTransition(R.anim.fade_scale_in, R.anim.slide_to_right);
    if (fragment != null) fragment.setLastSeen(System.currentTimeMillis());
    markLastSeen();
  }

  @Override
  public void onConfigurationChanged(@NonNull Configuration newConfig) {
    super.onConfigurationChanged(newConfig);

    if (composeText != null && sendButtonController != null) {
      composeText.setTransport(sendButtonController.getSelectedTransport());
    }

    if (emojiDrawerStub != null && emojiDrawerStub.resolved()
            && container != null && container.getCurrentInput() == emojiDrawerStub.get()) {
      container.hideAttachedInput(true);
    }
  }

  @Override
  protected void onDestroy() {
    saveDraft();

    if (recipients != null) recipients.removeListener(this);

    if (securityUpdateReceiverRegistered) {
      try {
        unregisterReceiver(securityUpdateReceiver);
      } catch (IllegalArgumentException ignored) {
      }
      securityUpdateReceiverRegistered = false;
    }

    super.onDestroy();
  }

  @Override
  public void startActivity(Intent intent) {
    try {
      if (intent.getStringExtra(Browser.EXTRA_APPLICATION_ID) != null) {
        intent.removeExtra(Browser.EXTRA_APPLICATION_ID);
      }
      super.startActivity(intent);
    } catch (ActivityNotFoundException e) {
      Log.w(TAG, "No app found to view the link '" + intent.getDataString() + "', ignoring...");
      Toast.makeText(this, R.string.ConversationActivity_cant_open_link, Toast.LENGTH_SHORT).show();
    }
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuInflater inflater = this.getMenuInflater();
    menu.clear();

    inflater.inflate(R.menu.conversation_menu, menu);

    menu.findItem(R.id.menu_security).setIcon(
            (isEncryptedConversation) ?
                    ((isCurrentRecipientVerified()) ? R.drawable.ic_shield_lock : R.drawable.ic_lock)
                    : R.drawable.ic_lock_open_variant);

    menu.findItem(R.id.menu_verify_identity).setVisible(
            isSingleConversation()
                    && isEncryptedConversation
                    && !(activeSubscriptions.size() > 1));
    menu.findItem(R.id.menu_verify_identity_dual_sim).setVisible(
            isSingleConversation()
                    && isEncryptedConversation
                    && (activeSubscriptions.size() > 1));
    if (isSingleConversation() && isEncryptedConversation && (activeSubscriptions.size() > 1)) {
      inflateSubMenuVerifyIdentity(menu);
    }

    boolean isEncryptedForAllSubscriptionIdsConversation =
            (recipients != null)
                    && SessionUtil.hasSession(this, masterSecret, recipients.getPrimaryRecipient().getNumber(), activeSubscriptions);
    menu.findItem(R.id.menu_start_secure_session).setVisible(
            isSingleConversation()
                    && !isEncryptedForAllSubscriptionIdsConversation
                    && !(activeSubscriptions.size() > 1));
    menu.findItem(R.id.menu_start_secure_session_dual_sim).setVisible(
            isSingleConversation()
                    && !isEncryptedForAllSubscriptionIdsConversation
                    && activeSubscriptions.size() > 1);
    if (isSingleConversation() && !isEncryptedForAllSubscriptionIdsConversation && (activeSubscriptions.size() > 1)) {
      menu.findItem(R.id.menu_start_secure_session_dual_sim).setVisible(
              masterSecret != null
                      && !recipients.getPrimaryRecipient().getNumber().isEmpty()
                      && inflateSubMenu(menu.findItem(R.id.menu_start_secure_session_dual_sim).getSubMenu(), true));
    }

    menu.findItem(R.id.menu_abort_session).setVisible(
            isSingleConversation()
                    && isEncryptedConversation
                    && !(activeSubscriptions.size() > 1));
    menu.findItem(R.id.menu_abort_session_dual_sim).setVisible(
            isSingleConversation()
                    && isEncryptedConversation
                    && (activeSubscriptions.size() > 1));
    if (isSingleConversation() && isEncryptedConversation && activeSubscriptions.size() > 1) {
      menu.findItem(R.id.menu_abort_session_dual_sim).setVisible(
              masterSecret != null
                      && recipients != null
                      && !recipients.getPrimaryRecipient().getNumber().isEmpty()
                      && inflateSubMenu(menu.findItem(R.id.menu_abort_session_dual_sim).getSubMenu(), false)
      );
    }

    menu.findItem(R.id.menu_invite).setVisible(
            isSingleConversation()
                    && !isEncryptedConversation);

    menu.findItem(R.id.menu_call).setVisible(isSingleConversation());

    menu.findItem(R.id.menu_group_recipients).setVisible(
            !isSingleConversation()
                    && isGroupConversation());

    menu.findItem(R.id.menu_mute_notifications).setVisible(
            recipients != null
                    && !recipients.isMuted());
    menu.findItem(R.id.menu_unmute_notifications).setVisible(
            recipients != null
                    && recipients.isMuted());

    menu.findItem(R.id.menu_add_to_contacts).setVisible(
            isSingleConversation()
                    && getRecipients() != null
                    && getRecipients().getPrimaryRecipient().getContactUri() == null);

    menu.findItem(R.id.menu_unarchive_conversation).setVisible(archived);
    menu.findItem(R.id.menu_archive_conversation).setVisible(!archived);

    super.onPrepareOptionsMenu(menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    int id = item.getItemId();
    if (id == R.id.menu_call) {
      handleDial(getRecipients() != null ? getRecipients().getPrimaryRecipient() : null);
      return true;
    } else if (id == R.id.menu_delete_conversation) {
      handleDeleteConversation();
      return true;
    } else if (id == R.id.menu_archive_conversation || id == R.id.menu_unarchive_conversation) {
      handleArchiveConversation();
      return true;
    } else if (id == R.id.menu_add_to_contacts) {
      handleAddToContacts();
      return true;
    } else if (id == R.id.menu_start_secure_session) {
      handleStartSecureSession();
      return true;
    } else if (id == R.id.menu_start_secure_session_dual_sim) {
      handleStartSecureSession();
      return true;
    } else if (id == R.id.menu_abort_session) {
      handleAbortSecureSession();
      return true;
    } else if (id == R.id.menu_abort_session_dual_sim) {
      handleAbortSecureSession();
      return true;
    } else if (id == R.id.menu_verify_identity) {
      handleVerifyIdentity();
      return true;
    } else if (id == R.id.menu_verify_identity_dual_sim) {
      handleVerifyIdentity();
      return true;
    } else if (id == R.id.menu_group_recipients) {
      handleDisplayGroupRecipients();
      return true;
    } else if (id == R.id.menu_invite) {
      handleInviteLink();
      return true;
    } else if (id == R.id.menu_mute_notifications) {
      handleMuteNotifications();
      return true;
    } else if (id == R.id.menu_unmute_notifications) {
      handleUnmuteNotifications();
      return true;
    } else if (id == R.id.menu_conversation_settings) {
      handleConversationSettings();
      return true;
    } else if (id == android.R.id.home) {
      Log.w(TAG, "ActionBar home pressed");
      navigateBackFromConversation();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  public boolean onSupportNavigateUp() {
    Log.w(TAG, "onSupportNavigateUp()");
    navigateBackFromConversation();
    return true;
  }

  @Override
  public void onKeyboardShown() {
    if (emojiToggle != null) emojiToggle.setToEmoji();
  }

  private void inflateSubMenuVerifyIdentity(@NonNull Menu menu) {
    SubMenu identitiesMenu = menu.findItem(R.id.menu_verify_identity_dual_sim).getSubMenu();
    if (identitiesMenu != null) {
      identitiesMenu.clear();
      for (SubscriptionInfoCompat subscriptionInfo : activeSubscriptions) {
        final int subscriptionId = subscriptionInfo.getSubscriptionId();
        identitiesMenu.add(Menu.NONE, Menu.NONE, Menu.NONE, subscriptionInfo.getDisplayName())
                .setOnMenuItemClickListener(mi -> {
                  handleVerifyIdentity(subscriptionId);
                  return true;
                });
      }
    }
  }

  private boolean inflateSubMenu(SubMenu subMenu, boolean startSession) {
    if (subMenu != null) {
      subMenu.clear();

      for (SubscriptionInfoCompat subscriptionInfo : activeSubscriptions) {
        final int subscriptionId = subscriptionInfo.getSubscriptionId();

        if (startSession != SessionUtil.hasSession(
                this,
                masterSecret,
                recipients.getPrimaryRecipient().getNumber(),
                subscriptionId)) {
          subMenu.add(Menu.NONE, Menu.NONE, Menu.NONE, subscriptionInfo.getDisplayName())
                  .setOnMenuItemClickListener(mi -> {
                    if (startSession) {
                      handleStartSecureSession(subscriptionId);
                    } else {
                      handleAbortSecureSession(subscriptionId);
                    }
                    return true;
                  });
        }
      }
      return subMenu.size() > 0;
    }
    return false;
  }

  private void handleReturnToConversationList() {
    Intent intent = new Intent(this, (archived ? ConversationListArchiveActivity.class : ConversationListActivity.class));
    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    startActivity(intent);
    finish();
  }

  private void navigateBackFromConversation() {
    handleReturnToConversationList();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  private void handleMuteNotifications() {
    MuteDialog.show(this, until -> {
      if (recipients == null) return;
      recipients.setMuted(until);

      backgroundExecutor.execute(() -> DatabaseFactory.getRecipientPreferenceDatabase(ConversationActivity.this)
              .setMuted(recipients, until));
    });
  }

  private void handleConversationSettings() {
    if (titleView != null) titleView.performClick();
  }

  private void handleUnmuteNotifications() {
    if (recipients == null) return;
    recipients.setMuted(0);

    backgroundExecutor.execute(() -> DatabaseFactory.getRecipientPreferenceDatabase(ConversationActivity.this)
            .setMuted(recipients, 0));
  }

  private void handleUnblock() {
    new AlertDialog.Builder(this)
            .setTitle(R.string.RecipientPreferenceActivity_unblock_this_contact_question)
            .setMessage(R.string.RecipientPreferenceActivity_are_you_sure_you_want_to_unblock_this_contact)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.RecipientPreferenceActivity_unblock, (dialog, which) -> {
              if (recipients == null) return;
              recipients.setBlocked(false);

              backgroundExecutor.execute(() -> DatabaseFactory.getRecipientPreferenceDatabase(ConversationActivity.this)
                      .setBlocked(recipients, false));
            }).show();
  }

  private void handleInviteLink() {
    if (composeText == null) return;
    composeText.appendInvite(getString(R.string.ConversationActivity_install_smssecure, BuildConfig.INVITE_URL));
  }

  private void handleVerifyIdentity() {
    if (activeSubscriptions.size() < 2) {
      int subscriptionId = activeSubscriptions.get(0).getSubscriptionId();
      handleVerifyIdentity(subscriptionId);
    }
  }

  private void handleVerifyIdentity(int subscriptionId) {
    Recipients r = getRecipients();
    if (r == null) return;

    Intent verifyIdentityIntent = new Intent(this, VerifyIdentityActivity.class);
    verifyIdentityIntent.putExtra("subscription_id", subscriptionId);
    verifyIdentityIntent.putExtra("recipient", r.getPrimaryRecipient().getRecipientId());

    Intent viewIdentityIntent = new Intent(this, ViewIdentityActivity.class);
    viewIdentityIntent.putExtra(ViewIdentityActivity.EXTRA_ENABLE_SCAN, true);

    startActivity(verifyIdentityIntent);
  }

  private void handleStartSecureSession() {
    if (activeSubscriptions.size() < 2) {
      int subscriptionId = activeSubscriptions.get(0).getSubscriptionId();
      handleStartSecureSession(subscriptionId);
    }
  }

  private void handleStartSecureSession(final int subscriptionId) {
    if (getRecipients() == null) {
      Toast.makeText(this, getString(R.string.ConversationActivity_invalid_recipient), Toast.LENGTH_LONG).show();
      return;
    }

    if (recipients == null) return;

    if (TelephonyUtil.isMyPhoneNumber(this, recipients.getPrimaryRecipient().getNumber())) {
      Toast.makeText(this, getString(R.string.ConversationActivity_recipient_self), Toast.LENGTH_LONG).show();
      return;
    }

    final Recipients r = getRecipients();
    final Recipient recipient = r.getPrimaryRecipient();
    String recipientName = (recipient.getName() == null ? recipient.getNumber() : recipient.getName());

    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(R.string.ConversationActivity_initiate_secure_session_question);
    builder.setIconAttribute(R.attr.dialog_info_icon);
    builder.setCancelable(true);
    builder.setMessage(String.format(getString(R.string.ConversationActivity_initiate_secure_session_with_s_question), recipientName));
    builder.setPositiveButton(R.string.Yes, (dialog, which) -> {
      KeyExchangeInitiator.initiate(ConversationActivity.this, masterSecret, r, true, subscriptionId);

      long allocatedThreadId = (threadId == -1)
              ? DatabaseFactory.getThreadDatabase(getApplicationContext()).getThreadIdFor(r)
              : threadId;

      sendComplete(allocatedThreadId);
    });
    builder.setNegativeButton(R.string.No, null);
    builder.show();
  }

  private void handleAbortSecureSession() {
    if (activeSubscriptions.size() < 2) {
      int subscriptionId = activeSubscriptions.get(0).getSubscriptionId();
      handleAbortSecureSession(subscriptionId);
    }
  }

  private void handleAbortSecureSession(final int subscriptionId) {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(R.string.ConversationActivity_abort_secure_session_confirmation);
    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setCancelable(true);
    builder.setMessage(R.string.ConversationActivity_are_you_sure_that_you_want_to_abort_this_secure_session_question);
    builder.setPositiveButton(R.string.Yes, (dialog, which) -> {
      if (!isSingleConversation()) return;
      Recipients r = getRecipients();
      if (r == null) return;

      KeyExchangeInitiator.abort(ConversationActivity.this, masterSecret, r, subscriptionId);

      long allocatedThreadId = (threadId == -1)
              ? DatabaseFactory.getThreadDatabase(getApplicationContext()).getThreadIdFor(r)
              : threadId;

      sendComplete(allocatedThreadId);
    });
    builder.setNegativeButton(R.string.No, null);
    builder.show();
  }

  private void handleDial(@Nullable Recipient recipient) {
    try {
      if (recipient == null) return;
      Intent dialIntent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + recipient.getNumber()));
      startActivity(dialIntent);
    } catch (ActivityNotFoundException e) {
      Log.w(TAG, e);
      Dialogs.showAlertDialog(this,
              getString(R.string.ConversationActivity_calls_not_supported),
              getString(R.string.ConversationActivity_this_device_does_not_appear_to_support_dial_actions));
    }
  }

  private void handleDisplayGroupRecipients() {
    Recipients r = getRecipients();
    if (r == null) return;
    new GroupMembersDialog(this, r).display();
  }

  private void handleDeleteConversation() {
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle(R.string.ConversationActivity_delete_thread_question);
    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setCancelable(true);
    builder.setMessage(R.string.ConversationActivity_this_will_permanently_delete_all_messages_in_this_conversation);
    builder.setPositiveButton(R.string.Delete, (dialog, which) -> {
      if (threadId > 0) {
        DatabaseFactory.getThreadDatabase(ConversationActivity.this).deleteConversation(threadId);
      }
      if (composeText != null) {
        Editable text = composeText.getText();
        if (text != null) text.clear();
      }
      threadId = -1;
      finish();
    });

    builder.setNegativeButton(android.R.string.cancel, null);
    builder.show();
  }

  private void handleArchiveConversation() {
    if (threadId > 0) {
      if (!archived) {
        DatabaseFactory.getThreadDatabase(ConversationActivity.this).archiveConversation(threadId);
      } else {
        DatabaseFactory.getThreadDatabase(ConversationActivity.this).unarchiveConversation(threadId);
      }
    }

    if (composeText != null) {
      Editable text = composeText.getText();
      if (text != null) text.clear();
    }

    threadId = -1;
    finish();
  }

  private void handleAddToContacts() {
    if (recipients == null) return;

    final String number = recipients.getPrimaryRecipient().getNumber();
    if (number.isEmpty()) return;

    try {
      Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
      intent.setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE);
      intent.putExtra(ContactsContract.Intents.Insert.PHONE, number);
      addContactLauncher.launch(intent);
    } catch (ActivityNotFoundException e) {
      Log.w(TAG, e);
    }
  }

  private void initializeDraft() {
    final String draftText = getIntent().getStringExtra(TEXT_EXTRA);

    if (draftText != null && composeText != null) composeText.setText(draftText);

    if (draftText == null) {
      initializeDraftFromDatabase();
    }
  }

  private void initializeEnabledCheck() {
    boolean enabled = !(isPushGroupConversation() && !isActiveGroup());
    if (composeText != null) composeText.setEnabled(enabled);
    if (sendButton != null) sendButton.setEnabled(enabled);
  }

  private void initializeDraftFromDatabase() {
    backgroundExecutor.execute(() -> {
      MasterCipher masterCipher = new MasterCipher(masterSecret);
      DraftDatabase draftDatabase = DatabaseFactory.getDraftDatabase(ConversationActivity.this);
      List<Draft> drafts = draftDatabase.getDrafts(masterCipher, threadId);

      draftDatabase.clearDrafts(threadId);

      mainHandler.post(() -> {
        for (Draft draft : drafts) {
          if (draft.getType().equals(Draft.TEXT)) {
            if (composeText != null) composeText.setText(draft.getValue());
          }
        }
      });
    });
  }

  private void initializeSecurity() {
    final Recipients r = getRecipients();
    final Recipient primaryRecipient = r != null ? r.getPrimaryRecipient() : null;
    final String number = primaryRecipient != null ? primaryRecipient.getNumber() : null;

    boolean secureDestination = false;

    if (number != null && !number.isEmpty() && isSingleConversation()) {
      secureDestination = SessionUtil.hasAtLeastOneSession(this, masterSecret, number, activeSubscriptions);
    }

    isEncryptedConversation = secureDestination;

    if (sendButtonController == null) return;

    sendButtonController.resetAvailableTransports();
    if (!secureDestination) sendButtonController.disableTransport(Type.SECURE_SMS);
    if (r != null && r.isGroupRecipient()) sendButtonController.disableTransport(Type.INSECURE_SMS);

    if (number != null && !number.isEmpty()) {
      sendButtonController.disableTransport(
              Type.SECURE_SMS,
              SessionUtil.getSubscriptionIdWithoutSession(this, masterSecret, number, activeSubscriptions));
    } else {
      sendButtonController.disableTransport(Type.SECURE_SMS);
    }

    sendButtonController.setDefaultTransport(secureDestination ? Type.SECURE_SMS : Type.INSECURE_SMS);

    calculateCharactersRemaining();
    supportInvalidateOptionsMenu();
  }

  private void updateRecipientPreferences() {
    if (recipients == null) return;
    if (recipients.getPrimaryRecipient().getContactUri() != null) {
      runRecipientPreferencesTask(recipients);
    }
  }

  private void runRecipientPreferencesTask(@NonNull Recipients recipientsToCheck) {
    final long[] ids = recipientsToCheck.getIds();

    backgroundExecutor.execute(() -> {
      Optional<RecipientsPreferences> prefs =
              DatabaseFactory.getRecipientPreferenceDatabase(ConversationActivity.this)
                      .getRecipientsPreferences(ids);

      final Pair<Recipients, RecipientsPreferences> result =
              new Pair<>(recipientsToCheck, prefs.orNull());

      mainHandler.post(() -> {
        if (result.first == recipients) {
          updateDefaultSubscriptionId(result.second != null
                  ? result.second.getDefaultSubscriptionId()
                  : SubscriptionManagerCompat.getDefaultMessagingSubscriptionId(this));
        }
      });
    });
  }

  private void updateDefaultSubscriptionId(Optional<Integer> defaultSubscriptionId) {
    if (sendButtonController != null) {
      sendButtonController.setDefaultSubscriptionId(defaultSubscriptionId);
    }
  }

  private void initializeViews() {
    final View root = findViewById(android.R.id.content);

    titleView = null;
    if (toolbar != null) {
      View v = toolbar.findViewById(R.id.conversation_title_root);
      if (v instanceof ConversationTitleView) {
        titleView = (ConversationTitleView) v;
      }
    } else {
      ActionBar actionBar = getSupportActionBar();
      View customView = actionBar != null ? actionBar.getCustomView() : null;
      if (customView instanceof ConversationTitleView) {
        titleView = (ConversationTitleView) customView;
      } else if (customView != null) {
        View v = customView.findViewById(R.id.conversation_title_root);
        if (v instanceof ConversationTitleView) {
          titleView = (ConversationTitleView) v;
        }
      }
    }

    sendButton = ViewUtil.findById(root, R.id.send_button);
    composeText = ViewUtil.findById(root, R.id.embedded_text_editor);
    charactersLeft = ViewUtil.findById(root, R.id.space_left);
    emojiToggle = ViewUtil.findById(root, R.id.emoji_toggle);
    unblockButton = ViewUtil.findById(root, R.id.unblock_button);
    composePanel = ViewUtil.findById(root, R.id.bottom_panel);
    View composeBubble = ViewUtil.findById(root, R.id.compose_bubble);
    container = ViewUtil.findById(root, R.id.layout_container);

    emojiDrawerStub = ViewUtil.findStubById(this, R.id.emoji_drawer_stub, EmojiDrawer.class);

    if (container != null) container.addOnKeyboardShownListener(this);
    if (composeText != null) composeText.setMediaListener(this);

    int[] attributes = new int[]{R.attr.conversation_item_bubble_background_color};
    try (TypedArray colors = obtainStyledAttributes(attributes)) {
      int defaultColor = colors.getColor(0, Color.WHITE);
      Drawable bg = composeBubble != null ? composeBubble.getBackground() : null;
      if (bg != null) bg.setColorFilter(defaultColor, PorterDuff.Mode.MULTIPLY);
    }

    SendButtonListener sendButtonListener = new SendButtonListener();
    ComposeKeyPressedListener composeKeyPressedListener = new ComposeKeyPressedListener();

    if (SMSecurePreferences.isSystemEmojiPreferred(this)) {
      if (emojiToggle != null) emojiToggle.setVisibility(View.GONE);
    } else if (emojiToggle != null && emojiDrawerStub != null) {
      emojiToggle.attach(emojiDrawerStub.get());
      emojiToggle.setOnClickListener(new EmojiToggleListener());
      emojiDrawerStub.get().setEmojiEventListener(new EmojiEventListener() {
        @Override
        public void onKeyEvent(KeyEvent keyEvent) {
          if (composeText != null) composeText.dispatchKeyEvent(keyEvent);
        }

        @Override
        public void onEmojiSelected(String emoji) {
          if (composeText != null) composeText.insertEmoji(emoji);
        }
      });
    }

    if (composeText != null) composeText.setOnEditorActionListener(sendButtonListener);

    if (sendButton != null) {
      sendButtonController = new SendButtonController(this, sendButton);

      sendButton.setOnClickListener(sendButtonListener);
      sendButton.setEnabled(true);

      sendButtonController.addOnTransportChangedListener((newTransport, manuallySelected) -> {
        calculateCharactersRemaining();

        if (composeText != null) {
          composeText.setTransport(newTransport);
        }

        if (manuallySelected) {
          recordSubscriptionIdPreference(newTransport.getSimSubscriptionId());
          sendIfSimCardNotAsked(false);
        }
      });
    }

    if (titleView != null) {
      titleView.setOnClickListener(v -> {
        if (recipients == null) return;
        Intent intent = new Intent(ConversationActivity.this, RecipientPreferenceActivity.class);
        intent.putExtra(RecipientPreferenceActivity.RECIPIENTS_EXTRA, recipients.getIds());
        startActivitySceneTransition(intent, titleView.findViewById(R.id.title));
      });
    }

    if (unblockButton != null) unblockButton.setOnClickListener(v -> handleUnblock());

    if (composeText != null) {
      composeText.setOnKeyListener(composeKeyPressedListener);
      composeText.addTextChangedListener(composeKeyPressedListener);
      composeText.setOnClickListener(composeKeyPressedListener);
      composeText.setOnFocusChangeListener(composeKeyPressedListener);
    }
  }

  protected void initializeActionBar() {
    if (toolbar == null) return;

    ActionBar ab = getSupportActionBar();
    if (ab == null) return;

    ab.setDisplayShowTitleEnabled(false);
    ab.setDisplayShowCustomEnabled(true);

    View custom = LayoutInflater.from(this).inflate(R.layout.conversation_title_view, toolbar, false);

    ActionBar.LayoutParams lp = new ActionBar.LayoutParams(
            ActionBar.LayoutParams.WRAP_CONTENT,
            ActionBar.LayoutParams.WRAP_CONTENT,
            Gravity.START | Gravity.CENTER_VERTICAL
    );

    ab.setCustomView(custom, lp);
  }

  private void initializeResources() {
    if (recipients != null) {
      recipients.removeListener(this);
    }

    long[] recipientIds = getIntent().getLongArrayExtra(RECIPIENTS_EXTRA);
    threadId = getIntent().getLongExtra(THREAD_ID_EXTRA, -1);
    archived = getIntent().getBooleanExtra(IS_ARCHIVED_EXTRA, false);
    distributionType = getIntent().getIntExtra(
            DISTRIBUTION_TYPE_EXTRA,
            ThreadDatabase.DistributionTypes.BROADCAST
    );

    if (recipientIds == null || recipientIds.length == 0) {
      Log.w(TAG, "Missing or empty RECIPIENTS_EXTRA. threadId=" + threadId);

      if (threadId > 0) {
        Recipients threadRecipients =
                DatabaseFactory.getThreadDatabase(this).getRecipientsForThreadId(threadId);

        if (threadRecipients != null) {
          recipients = threadRecipients;
          recipients.addListener(this);
          return;
        }
      }

      Log.w(TAG, "Unable to resolve recipients from intent or threadId. Returning to list.");
      handleReturnToConversationList();
      return;
    }

    recipients = RecipientFactory.getRecipientsForIds(this, recipientIds, true);

    if (recipients != null) {
      recipients.addListener(this);
    }
  }

  @Override
  public void onModified(final Recipients recipients) {
    if (titleView == null) return;
    titleView.post(() -> {
      titleView.setTitle(recipients);
      setBlockedUserState(recipients);
      setActionBarColor(recipients.getColor());
      updateRecipientPreferences();
    });
  }

  private void initializeReceivers() {
    securityUpdateReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        long eventThreadId = intent.getLongExtra("thread_id", -1);
        if (eventThreadId == threadId || eventThreadId == -2) {
          initializeSecurity();
          updateRecipientPreferences();
          calculateCharactersRemaining();
        }
      }
    };

    ContextCompat.registerReceiver(
            this,
            securityUpdateReceiver,
            new IntentFilter(SecurityEvent.SECURITY_UPDATE_EVENT),
            KeyCachingService.KEY_PERMISSION,
            null,
            ContextCompat.RECEIVER_NOT_EXPORTED
    );
    securityUpdateReceiverRegistered = true;
  }

  private Drafts getDraftsForCurrentState() {
    Drafts drafts = new Drafts();

    if (composeText != null) {
      Editable text = composeText.getText();
      if (!TextUtils.isEmpty(text)) drafts.add(new Draft(Draft.TEXT, text.toString()));
    }

    return drafts;
  }

  protected ListenableFuture<Long> saveDraft() {
    final SettableFuture<Long> future = new SettableFuture<>();

    if (recipients == null || recipients.isEmpty()) {
      future.set(threadId);
      return future;
    }

    final Drafts drafts = getDraftsForCurrentState();
    final long thisThreadId = this.threadId;
    final MasterSecret thisMasterSecret = this.masterSecret.parcelClone();
    final int thisDistributionType = this.distributionType;

    backgroundExecutor.execute(() -> {
      ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(ConversationActivity.this);
      DraftDatabase draftDatabase = DatabaseFactory.getDraftDatabase(ConversationActivity.this);
      long id = thisThreadId;

      if (!drafts.isEmpty()) {
        if (id == -1) id = threadDatabase.getThreadIdFor(getRecipients(), thisDistributionType);

        draftDatabase.insertDrafts(new MasterCipher(thisMasterSecret), id, drafts);
        threadDatabase.updateSnippet(id,
                drafts.getSnippet(),
                null,
                System.currentTimeMillis(), Types.BASE_DRAFT_TYPE, true);
      } else if (id > 0) {
        threadDatabase.update(id, false);
      }

      final long result = id;
      mainHandler.post(() -> future.set(result));
    });

    return future;
  }

  private void setActionBarColor(@NonNull MaterialColor color) {
    int barColor = color.toActionBarColor(this);

    if (toolbar != null) {
      toolbar.setBackground(new ColorDrawable(barColor));
    } else {
      ActionBar actionBar = getSupportActionBar();
      if (actionBar != null) {
        actionBar.setBackgroundDrawable(new ColorDrawable(barColor));
      }
    }

    Window window = getWindow();
    window.setStatusBarColor(color.toStatusBarColor(this));
    window.setNavigationBarColor(ContextCompat.getColor(this, android.R.color.black));
  }

  private void setBlockedUserState(@NonNull Recipients recipients) {
    if (unblockButton == null || composePanel == null) return;

    if (recipients.isBlocked()) {
      unblockButton.setVisibility(View.VISIBLE);
      composePanel.setVisibility(View.GONE);
    } else {
      composePanel.setVisibility(View.VISIBLE);
      unblockButton.setVisibility(View.GONE);
    }
  }

  private void calculateCharactersRemaining() {
    if (composeText == null || sendButtonController == null || charactersLeft == null) return;

    Editable editable = composeText.getText();
    String messageBody = editable != null ? editable.toString() : "";

    TransportOption transportOption = sendButtonController.getSelectedTransport();
    CharacterState characterState = transportOption.calculateCharacters(messageBody);

    final boolean isUnicode;
    int[] length = SmsMessage.calculateLength(messageBody, false);
    if (length != null && length.length > 3) {
      int codeUnitSize = length[3];
      isUnicode = (codeUnitSize != 1);
    } else {
      isUnicode = false;
    }

    charactersLeft.setText(String.format(
            java.util.Locale.getDefault(),
            "%d/%d (%d SMS)%s",
            characterState.charactersRemaining,
            characterState.maxMessageSize,
            characterState.messagesSpent,
            isUnicode ? "  Unicode" : ""
    ));
    charactersLeft.setVisibility(View.VISIBLE);
  }

  private boolean isSingleConversation() {
    return getRecipients() != null && getRecipients().isSingleRecipient() && !getRecipients().isGroupRecipient();
  }

  private boolean isActiveGroup() {
    return false;
  }

  private boolean isGroupConversation() {
    return getRecipients() != null &&
            (!getRecipients().isSingleRecipient() || getRecipients().isGroupRecipient());
  }

  private boolean isPushGroupConversation() {
    return getRecipients() != null && getRecipients().isGroupRecipient();
  }

  protected Recipients getRecipients() {
    return this.recipients;
  }

  private String getMessage() throws InvalidMessageException {
    String rawText = "";
    if (composeText != null) {
      Editable editable = composeText.getText();
      rawText = editable != null ? editable.toString() : "";
    }

    if (rawText.isEmpty()) {
      throw new InvalidMessageException(getString(R.string.ConversationActivity_message_is_empty_exclamation));
    }

    if (!isEncryptedConversation &&
            AutoInitiate.isTaggableMessage(rawText) &&
            AutoInitiate.isTaggableDestination(getRecipients())) {
      rawText = AutoInitiate.getTaggedMessage(rawText);
    }

    return rawText;
  }

  private void markThreadAsRead() {
    final long id = threadId;
    backgroundExecutor.execute(() -> {
      DatabaseFactory.getThreadDatabase(ConversationActivity.this).setRead(id);
      MessageNotifier.updateNotification(ConversationActivity.this, masterSecret);
    });
  }

  private void markLastSeen() {
    final long id = threadId;
    backgroundExecutor.execute(() -> DatabaseFactory.getThreadDatabase(ConversationActivity.this).setLastSeen(id));
  }

  protected void sendComplete(long threadId) {
    boolean refreshFragment = (threadId != this.threadId);
    this.threadId = threadId;

    if (fragment == null || !fragment.isVisible() || isFinishing()) return;

    fragment.setLastSeen(0);

    if (refreshFragment) {
      fragment.reload(recipients, threadId);
      initializeSecurity();
      updateRecipientPreferences();
    }

    fragment.scrollToBottom();
  }

  private void sendMessage() {
    if (sendButtonController == null) return;

    TransportOption transportOption = sendButtonController.getSelectedTransport();
    if (transportOption.getType() == Type.DISABLED) return;

    try {
      Recipients r = getRecipients();
      if (r == null) throw new RecipientFormattingException("Badly formatted");

      boolean forcePlaintext = transportOption.isPlaintext();
      int subscriptionId = transportOption.getSimSubscriptionId().or(-1);

      sendTextMessage(forcePlaintext, subscriptionId);

    } catch (RecipientFormattingException ex) {
      Toast.makeText(this,
              R.string.ConversationActivity_recipient_is_not_a_valid_sms_or_email_address_exclamation,
              Toast.LENGTH_LONG).show();
      Log.w(TAG, ex);
    } catch (InvalidMessageException ex) {
      Toast.makeText(this, R.string.ConversationActivity_message_is_empty_exclamation, Toast.LENGTH_SHORT).show();
      Log.w(TAG, ex);
    }
  }

  private void sendTextMessage(boolean forcePlaintext, final int subscriptionId)
          throws InvalidMessageException {

    final Context context = getApplicationContext();
    final String messageBody = getMessage();

    OutgoingTextMessage message =
            (isEncryptedConversation && !forcePlaintext)
                    ? new OutgoingEncryptedMessage(recipients, messageBody, subscriptionId)
                    : new OutgoingTextMessage(recipients, messageBody, subscriptionId);

    Permissions.with(this)
            .request(Manifest.permission.SEND_SMS)
            .ifNecessary()
            .withPermanentDenialDialog(getString(R.string.ConversationActivity_smsecure_needs_sms_permission_in_order_to_send_an_sms))
            .onAllGranted(() -> {
              if (composeText != null) composeText.setText("");

              backgroundExecutor.execute(() -> {
                final long result = MessageSender.send(context, masterSecret, message, threadId, true);
                mainHandler.post(() -> sendComplete(result));
              });
            })
            .execute();
  }

  private void recordSubscriptionIdPreference(final Optional<Integer> subscriptionId) {
    backgroundExecutor.execute(() -> DatabaseFactory.getRecipientPreferenceDatabase(this)
            .setDefaultSubscriptionId(recipients, subscriptionId.or(-1)));
  }

  private boolean sendIfSimCardNotAsked(boolean fromSendButton) {
    if (sendButtonController == null) return false;

    if (!SMSecurePreferences.isSimCardAsked(this) || (!fromSendButton && sendButtonController.isForceSend())) {
      sendMessage();
      return true;
    }

    return false;
  }

  @Override
  public void onMediaSelected(@NonNull Uri uri, String contentType) {
  }

  @Override
  public void setThreadId(long threadId) {
    this.threadId = threadId;
  }

  private class EmojiToggleListener implements OnClickListener {
    @Override
    public void onClick(View v) {
      if (container == null || emojiDrawerStub == null) return;
      if (container.getCurrentInput() == emojiDrawerStub.get()) {
        container.showSoftkey(composeText);
      } else {
        container.show(composeText, emojiDrawerStub.get());
      }
    }
  }

  private class SendButtonListener implements OnClickListener, TextView.OnEditorActionListener {
    @Override
    public void onClick(View v) {
      if (!sendIfSimCardNotAsked(true) && sendButtonController != null) {
        sendButtonController.displayTransports(true);
      }
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
      if (actionId == EditorInfo.IME_ACTION_SEND) {
        sendButton.performClick();
        return true;
      }
      return false;
    }
  }

  private class ComposeKeyPressedListener implements OnKeyListener, OnClickListener, TextWatcher, OnFocusChangeListener {
    int beforeLength;

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
      if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
        if ("send".equals(SMSecurePreferences.getEnterKeyType(ConversationActivity.this))) {
          sendButton.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
          sendButton.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
          return true;
        }
      }
      return false;
    }

    @Override
    public void onClick(View v) {
      if (container != null) container.showSoftkey(composeText);
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
      Editable t = composeText != null ? composeText.getText() : null;
      beforeLength = t != null ? t.length() : 0;
    }

    @Override
    public void afterTextChanged(Editable s) {
      calculateCharactersRemaining();
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void onFocusChange(View v, boolean hasFocus) {
    }
  }
}