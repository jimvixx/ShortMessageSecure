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

import android.content.Context;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;

import org.jimvixx.smsecure.color.MaterialColor;
import org.jimvixx.smsecure.color.MaterialColors;
import org.jimvixx.smsecure.components.AvatarImageView;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.preferences.CorrectedPreferenceFragment;
import org.jimvixx.smsecure.preferences.widgets.AdvancedRingtonePreference;
import org.jimvixx.smsecure.preferences.widgets.ColorPickerPreference;
import org.jimvixx.smsecure.preferences.widgets.RingtonePreferenceDialogFragmentCompat;
import org.jimvixx.smsecure.preferences.widgets.SMSecurePreference;
import org.jimvixx.smsecure.preferences.widgets.SMSecureSwitchPreferenceCompat;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;

import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class RecipientPreferenceActivity extends PassphraseRequiredActionBarActivity
        implements Recipients.RecipientsModifiedListener {

  public static final String RECIPIENTS_EXTRA = "recipient_ids";

  private static final String PREFERENCE_MUTED = "pref_key_recipient_mute";
  private static final String PREFERENCE_TONE = "pref_key_recipient_ringtone";
  private static final String PREFERENCE_VIBRATE = "pref_key_recipient_vibrate";
  private static final String PREFERENCE_BLOCK = "pref_key_recipient_block";
  private static final String PREFERENCE_COLOR = "pref_key_recipient_color";

  private AvatarImageView avatar;
  private Toolbar toolbar;
  private TextView title;
  private TextView blockedIndicator;

  @Override
  public void onCreate(@Nullable Bundle instanceState, @NonNull MasterSecret masterSecret) {
    setContentView(R.layout.recipient_preference_activity);

    final long[] recipientIds = getIntent().getLongArrayExtra(RECIPIENTS_EXTRA);
    final Recipients recipients = RecipientFactory.getRecipientsForIds(this, recipientIds, true);

    initializeToolbar();
    setHeader(recipients);
    recipients.addListener(this);

    Bundle bundle = new Bundle();
    bundle.putLongArray(RECIPIENTS_EXTRA, recipientIds);

    initFragment(
            R.id.preference_fragment,
            new RecipientPreferenceFragment(),
            masterSecret,
            Locale.getDefault(),
            bundle
    );
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      getOnBackPressedDispatcher().onBackPressed();
      return true;
    }

    return super.onOptionsItemSelected(item);
  }

  private void initializeToolbar() {
    toolbar = findViewById(R.id.toolbar);
    if (toolbar != null) {
      toolbar.setLogo(null);
      setSupportActionBar(toolbar);
    }

    ActionBar actionBar = getSupportActionBar();
    if (actionBar != null) {
      actionBar.setDisplayHomeAsUpEnabled(true);
      actionBar.setDisplayShowTitleEnabled(false);
    }

    if (toolbar != null) {
      avatar = toolbar.findViewById(R.id.avatar);
      title = toolbar.findViewById(R.id.name);
      blockedIndicator = toolbar.findViewById(R.id.blocked_indicator);
    }
  }

  private void setHeader(@NonNull Recipients recipients) {
    if (avatar != null) avatar.setAvatar(recipients, true);
    if (title != null) title.setText(recipients.toShortString());
    if (toolbar != null) {
      toolbar.setBackgroundColor(recipients.getColor().toActionBarColor(this));
    }

    Window window = getWindow();
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
    window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
    window.setStatusBarColor(recipients.getColor().toStatusBarColor(this));
    window.setNavigationBarColor(ContextCompat.getColor(this, android.R.color.black));

    if (blockedIndicator != null) {
      blockedIndicator.setVisibility(recipients.isBlocked() ? View.VISIBLE : View.GONE);
    }
  }

  @Override
  public void onModified(@NonNull final Recipients recipients) {
    if (title == null) return;
    title.post(() -> setHeader(recipients));
  }

  public static class RecipientPreferenceFragment
          extends CorrectedPreferenceFragment
          implements Recipients.RecipientsModifiedListener {

    private static final String DIALOG_FRAGMENT_TAG = "androidx.preference.PreferenceFragment.DIALOG";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor executor = Executors.newSingleThreadExecutor();

    @Nullable
    private Recipients recipients;

    @Override
    public void onCreate(@Nullable Bundle icicle) {
      super.onCreate(icicle);

      Bundle args = getArguments();
      if (args == null) throw new IllegalStateException("Missing fragment arguments");

      long[] recipientIds = args.getLongArray(RECIPIENTS_EXTRA);
      if (recipientIds == null) throw new IllegalStateException("Missing RECIPIENTS_EXTRA");

      recipients = RecipientFactory.getRecipientsForIds(requireContext(), recipientIds, true);
      recipients.addListener(this);
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
      addPreferencesFromResource(R.xml.recipient_preferences);

      removePerRecipientVibratePreference();

      Preference tone = findPreference(PREFERENCE_TONE);
      if (tone != null) {
        tone.setOnPreferenceChangeListener(new RingtoneChangeListener());
      }

      Preference muted = findPreference(PREFERENCE_MUTED);
      if (muted != null) {
        muted.setOnPreferenceClickListener(new MuteClickedListener());
      }

      Preference block = findPreference(PREFERENCE_BLOCK);
      if (block != null) {
        block.setOnPreferenceClickListener(new BlockClickedListener());
      }

      Preference color = findPreference(PREFERENCE_COLOR);
      if (color != null) {
        color.setOnPreferenceChangeListener(new ColorChangeListener());
      }
    }

    @Override
    public void onResume() {
      super.onResume();
      if (recipients != null) {
        setSummaries(recipients);
      }
    }

    @Override
    public void onDestroy() {
      super.onDestroy();
      if (recipients != null) {
        recipients.removeListener(this);
      }
    }

    @Override
    public void onDisplayPreferenceDialog(@NonNull Preference preference) {
      if (preference instanceof AdvancedRingtonePreference) {
        if (getParentFragmentManager().findFragmentByTag(DIALOG_FRAGMENT_TAG) != null) {
          return;
        }

        RingtonePreferenceDialogFragmentCompat dialog =
                RingtonePreferenceDialogFragmentCompat.newInstance(preference.getKey());

        dialog.show(getParentFragmentManager(), DIALOG_FRAGMENT_TAG);
        return;
      }

      super.onDisplayPreferenceDialog(preference);
    }

    private void setSummaries(@NonNull Recipients recipients) {
      bindMutePreference(recipients);
      bindRingtonePreference(recipients);
      bindSingleRecipientPreferences(recipients);
    }

    private void bindMutePreference(@NonNull Recipients recipients) {
      SMSecureSwitchPreferenceCompat mutePreference = findPreference(PREFERENCE_MUTED);
      if (mutePreference != null) {
        mutePreference.setChecked(recipients.isMuted());
      }
    }

    private void bindRingtonePreference(@NonNull Recipients recipients) {
      AdvancedRingtonePreference ringtonePreference = findPreference(PREFERENCE_TONE);
      if (ringtonePreference == null) return;

      Context context = ringtonePreference.getContext();
      Uri uri = recipients.getRingtone();

      if (uri == null) {
        ringtonePreference.setSummary(context.getString(R.string.RingtonePreference_application_default));
        // Sentinel for first row in dialog; listener converts it back to app default (null).
        ringtonePreference.setCurrentRingtone(Settings.System.DEFAULT_NOTIFICATION_URI);
        return;
      }

      if (uri.toString().isEmpty()) {
        ringtonePreference.setSummary(context.getString(R.string.Silent));
        ringtonePreference.setCurrentRingtone(null);
        return;
      }

      Ringtone ringtone = RingtoneManager.getRingtone(context, uri);
      if (ringtone != null) {
        ringtonePreference.setSummary(ringtone.getTitle(context));
        ringtonePreference.setCurrentRingtone(uri);
      } else {
        ringtonePreference.setSummary(context.getString(R.string.RingtonePreference_application_default));
        ringtonePreference.setCurrentRingtone(Settings.System.DEFAULT_NOTIFICATION_URI);
      }
    }

    private void removePerRecipientVibratePreference() {
      if (getPreferenceScreen() == null) {
        return;
      }

      Preference vibratePreference = findPreference(PREFERENCE_VIBRATE);
      if (vibratePreference != null) {
        getPreferenceScreen().removePreference(vibratePreference);
      }
    }

    private void bindSingleRecipientPreferences(@NonNull Recipients recipients) {
      ColorPickerPreference colorPreference = findPreference(PREFERENCE_COLOR);
      SMSecurePreference blockPreference = findPreference(PREFERENCE_BLOCK);

      boolean showSingleContactPrefs =
              recipients.isSingleRecipient() && !recipients.isGroupRecipient();

      if (!showSingleContactPrefs) {
        if (colorPreference != null) {
          getPreferenceScreen().removePreference(colorPreference);
        }
        if (blockPreference != null) {
          getPreferenceScreen().removePreference(blockPreference);
        }
        return;
      }

      if (colorPreference != null) {
        Context context = colorPreference.getContext();
        colorPreference.setColors(
                MaterialColors.CONVERSATION_PALETTE.asConversationColorArray(context)
        );
        colorPreference.setColor(recipients.getColor().toActionBarColor(context));
      }

      if (blockPreference != null) {
        blockPreference.setTitle(recipients.isBlocked()
                ? R.string.RecipientPreferenceActivity_unblock
                : R.string.RecipientPreferenceActivity_block);
      }
    }

    @Override
    public void onModified(@NonNull final Recipients recipients) {
      handler.post(() -> setSummaries(recipients));
    }

    private class RingtoneChangeListener implements Preference.OnPreferenceChangeListener {
      @Override
      public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
        if (recipients == null) return false;

        Uri value = (Uri) newValue;
        Uri uri;

        if (value == null) {
          uri = Uri.parse("");
        } else if (Settings.System.DEFAULT_NOTIFICATION_URI.equals(value)) {
          // First row in the dialog means application default for recipient settings.
          uri = null;
        } else {
          uri = value;
        }

        recipients.setRingtone(uri);

        Context appContext = preference.getContext().getApplicationContext();
        executor.execute(() -> DatabaseFactory.getRecipientPreferenceDatabase(appContext)
                .setRingtone(recipients, uri));

        handler.post(() -> {
          if (recipients != null) {
            setSummaries(recipients);
          }
        });

        return false;
      }
    }

    private class ColorChangeListener implements Preference.OnPreferenceChangeListener {
      @Override
      public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
        if (recipients == null) return true;

        int value = (Integer) newValue;
        Context context = preference.getContext();

        MaterialColor selectedColor =
                MaterialColors.CONVERSATION_PALETTE.getByColor(context, value);
        MaterialColor currentColor = recipients.getColor();

        if (selectedColor == null) return true;

        if (preference.isEnabled() && !currentColor.equals(selectedColor)) {
          recipients.setColor(selectedColor);

          Context appContext = context.getApplicationContext();
          executor.execute(() -> DatabaseFactory.getRecipientPreferenceDatabase(appContext)
                  .setColor(recipients, selectedColor));
        }

        return true;
      }
    }

    private class MuteClickedListener implements Preference.OnPreferenceClickListener {
      @Override
      public boolean onPreferenceClick(@NonNull Preference preference) {
        if (recipients == null) return true;

        if (recipients.isMuted()) {
          handleUnmute(preference);
        } else {
          handleMute(preference);
        }

        return true;
      }

      private void handleMute(@NonNull Preference preference) {
        if (recipients == null) return;

        Context context = preference.getContext();
        MuteDialog.show(context, until -> setMuted(context, recipients, until));
        setSummaries(recipients);
      }

      private void handleUnmute(@NonNull Preference preference) {
        if (recipients == null) return;
        setMuted(preference.getContext(), recipients, 0);
      }

      private void setMuted(@NonNull Context context,
                            @NonNull Recipients recipients,
                            long until) {
        recipients.setMuted(until);

        Context appContext = context.getApplicationContext();
        executor.execute(() -> DatabaseFactory.getRecipientPreferenceDatabase(appContext)
                .setMuted(recipients, until));
      }
    }

    private class BlockClickedListener implements Preference.OnPreferenceClickListener {
      @Override
      public boolean onPreferenceClick(@NonNull Preference preference) {
        if (recipients == null) return true;

        Context context = preference.getContext();

        if (recipients.isBlocked()) {
          handleUnblock(context);
        } else {
          handleBlock(context);
        }

        return true;
      }

      private void handleBlock(@NonNull Context context) {
        if (recipients == null) return;

        new AlertDialog.Builder(context)
                .setTitle(R.string.RecipientPreferenceActivity_block_this_contact_question)
                .setMessage(R.string.RecipientPreferenceActivity_you_will_no_longer_see_messages_from_this_user)
                .setCancelable(true)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        R.string.RecipientPreferenceActivity_block,
                        (dialog, which) -> setBlocked(context, recipients, true))
                .show();
      }

      private void handleUnblock(@NonNull Context context) {
        if (recipients == null) return;

        new AlertDialog.Builder(context)
                .setTitle(R.string.RecipientPreferenceActivity_unblock_this_contact_question)
                .setMessage(R.string.RecipientPreferenceActivity_are_you_sure_you_want_to_unblock_this_contact)
                .setCancelable(true)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        R.string.RecipientPreferenceActivity_unblock,
                        (dialog, which) -> setBlocked(context, recipients, false))
                .show();
      }

      private void setBlocked(@NonNull Context context,
                              @NonNull Recipients recipients,
                              boolean blocked) {
        recipients.setBlocked(blocked);

        Context appContext = context.getApplicationContext();
        executor.execute(() -> DatabaseFactory.getRecipientPreferenceDatabase(appContext)
                .setBlocked(recipients, blocked));
      }
    }
  }
}