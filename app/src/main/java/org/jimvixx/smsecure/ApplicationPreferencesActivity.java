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

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.Preference;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.preferences.AdvancedPreferenceFragment;
import org.jimvixx.smsecure.preferences.AppProtectionPreferenceFragment;
import org.jimvixx.smsecure.preferences.AppearancePreferenceFragment;
import org.jimvixx.smsecure.preferences.ChatsPreferenceFragment;
import org.jimvixx.smsecure.preferences.CorrectedPreferenceFragment;
import org.jimvixx.smsecure.preferences.MessagesPreferenceFragment;
import org.jimvixx.smsecure.preferences.NotificationsPreferenceFragment;
import org.jimvixx.smsecure.util.AboutHtml;
import org.jimvixx.smsecure.util.DynamicTheme;
import org.jimvixx.smsecure.util.SMSecurePreferences;

/**
 * The Activity for application preference display and management.
 * <p>
 * Uses a NoActionBar theme and provides its own Toolbar in the layout.
 */
public class ApplicationPreferencesActivity extends PassphraseRequiredActionBarActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener {

  public static final String PREFERENCE_CATEGORY_APPEARANCE = "preference_category_appearance";
  public static final String EXTRA_START_CATEGORY = "start_category";
  private static final String PREFERENCE_CATEGORY_MESSAGES = "preference_category_messages";
  private static final String PREFERENCE_CATEGORY_NOTIFICATIONS = "preference_category_notifications";
  private static final String PREFERENCE_CATEGORY_APP_PROTECTION = "preference_category_app_protection";
  private static final String PREFERENCE_CATEGORY_CHATS = "preference_category_chats";
  private static final String PREFERENCE_CATEGORY_ADVANCED = "preference_category_advanced";
  private static final String PREFERENCE_ABOUT = "preference_about";
  private static final String PREFERENCE_PRIVACY_POLICY = "preference_privacy_policy";
  private boolean recreating;

  @Override
  protected void onCreate(Bundle icicle, @NonNull MasterSecret masterSecret) {
    setContentView(R.layout.application_preferences_activity);

    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);

    ActionBar actionBar = getSupportActionBar();
    if (actionBar != null) {
      actionBar.setDisplayHomeAsUpEnabled(true);
      actionBar.setDisplayShowHomeEnabled(true);
      actionBar.setTitle(R.string.conversation_list_menu__settings);
    }

    if (icicle == null) {
      String startCategory = getIntent().getStringExtra(EXTRA_START_CATEGORY);

      Fragment fragment = switch (startCategory != null ? startCategory : "") {
        case PREFERENCE_CATEGORY_MESSAGES -> new MessagesPreferenceFragment();
        case PREFERENCE_CATEGORY_NOTIFICATIONS -> new NotificationsPreferenceFragment();
        case PREFERENCE_CATEGORY_APP_PROTECTION -> new AppProtectionPreferenceFragment();
        case PREFERENCE_CATEGORY_APPEARANCE -> new AppearancePreferenceFragment();
        case PREFERENCE_CATEGORY_CHATS -> new ChatsPreferenceFragment();
        case PREFERENCE_CATEGORY_ADVANCED -> new AdvancedPreferenceFragment();
        default -> new ApplicationPreferenceFragment();
      };

      initFragment(R.id.fragment_container, fragment, masterSecret);
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);

    String startCategory = intent.getStringExtra(EXTRA_START_CATEGORY);
    if (startCategory == null) return;

    Fragment fragment = switch (startCategory) {
      case PREFERENCE_CATEGORY_MESSAGES -> new MessagesPreferenceFragment();
      case PREFERENCE_CATEGORY_NOTIFICATIONS -> new NotificationsPreferenceFragment();
      case PREFERENCE_CATEGORY_APP_PROTECTION -> new AppProtectionPreferenceFragment();
      case PREFERENCE_CATEGORY_APPEARANCE -> new AppearancePreferenceFragment();
      case PREFERENCE_CATEGORY_CHATS -> new ChatsPreferenceFragment();
      case PREFERENCE_CATEGORY_ADVANCED -> new AdvancedPreferenceFragment();
      default -> new ApplicationPreferenceFragment();
    };

    getSupportFragmentManager()
            .beginTransaction()
            .setCustomAnimations(
                    R.anim.slide_from_right,
                    R.anim.slide_to_left,
                    R.anim.slide_from_left,
                    R.anim.slide_to_right
            )
            .replace(R.id.fragment_container, fragment)
            .commitAllowingStateLoss();
  }

  @Override
  public boolean onSupportNavigateUp() {
    FragmentManager fragmentManager = getSupportFragmentManager();

    if (fragmentManager.getBackStackEntryCount() > 0) {
      fragmentManager.popBackStack();
    } else {
      Intent intent = new Intent(this, ConversationListActivity.class);
      intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
      startActivity(intent);
      finish();
    }

    return true;
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
    if (recreating) return;

    if (SMSecurePreferences.THEME_PREF.equals(key)) {
      recreating = true;

      @AppCompatDelegate.NightMode int mode = DynamicTheme.resolveNightMode(this);
      if (AppCompatDelegate.getDefaultNightMode() != mode) {
        AppCompatDelegate.setDefaultNightMode(mode);
      }

      overridePendingTransition(0, 0);
      recreate();
      overridePendingTransition(0, 0);
    }
  }

  /**
   * Shows the "About" dialog rendered as HTML in a WebView.
   * The content is generated from Distribution_long_description and injected URLs from BuildConfig.
   */
  public void showAboutDialog() {
    WebView webView = new WebView(this);

    webView.getSettings().setJavaScriptEnabled(false);
    webView.getSettings().setAllowFileAccess(false);
    webView.getSettings().setAllowContentAccess(false);
    webView.getSettings().setSupportZoom(false);
    webView.getSettings().setUseWideViewPort(false);
    webView.getSettings().setLoadWithOverviewMode(false);

    webView.setWebViewClient(new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        openUrlSafely(uri.toString());
        return true;
      }
    });

    String html = AboutHtml.build(this);
    webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);

    new AlertDialog.Builder(this)
            .setTitle(R.string.preferences__about)
            .setView(webView)
            .setPositiveButton(android.R.string.ok, null)
            .show();
  }

  private void openUrlSafely(@NonNull String url) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (ActivityNotFoundException e) {
      Toast.makeText(
              getApplicationContext(),
              R.string.ConversationActivity_cant_open_link,
              Toast.LENGTH_LONG
      ).show();
    }
  }

  public static class ApplicationPreferenceFragment extends CorrectedPreferenceFragment {

    @Override
    public void onCreate(Bundle icicle) {
      super.onCreate(icicle);

      MasterSecret masterSecret = null;
      Bundle args = getArguments();
      if (args != null) {
        masterSecret = args.getParcelable("master_secret");
      }

      setClickListenerIfPresent(
              PREFERENCE_CATEGORY_MESSAGES,
              new CategoryClickListener(masterSecret, PREFERENCE_CATEGORY_MESSAGES)
      );
      setClickListenerIfPresent(
              PREFERENCE_CATEGORY_NOTIFICATIONS,
              new CategoryClickListener(masterSecret, PREFERENCE_CATEGORY_NOTIFICATIONS)
      );
      setClickListenerIfPresent(
              PREFERENCE_CATEGORY_APP_PROTECTION,
              new CategoryClickListener(masterSecret, PREFERENCE_CATEGORY_APP_PROTECTION)
      );
      setClickListenerIfPresent(
              PREFERENCE_CATEGORY_APPEARANCE,
              new CategoryClickListener(masterSecret, PREFERENCE_CATEGORY_APPEARANCE)
      );
      setClickListenerIfPresent(
              PREFERENCE_CATEGORY_CHATS,
              new CategoryClickListener(masterSecret, PREFERENCE_CATEGORY_CHATS)
      );
      setClickListenerIfPresent(
              PREFERENCE_CATEGORY_ADVANCED,
              new CategoryClickListener(masterSecret, PREFERENCE_CATEGORY_ADVANCED)
      );

      setClickListenerIfPresent(PREFERENCE_ABOUT, preference -> {
        ApplicationPreferencesActivity activity = getHostActivity();
        if (activity != null) {
          activity.showAboutDialog();
        }
        return true;
      });

      setClickListenerIfPresent(PREFERENCE_PRIVACY_POLICY, preference -> {
        handlePrivacyPolicy();
        return true;
      });
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
      addPreferencesFromResource(R.xml.preferences);
    }

    @Override
    public void onResume() {
      super.onResume();

      ApplicationPreferencesActivity activity = getHostActivity();
      if (activity != null) {
        ActionBar actionBar = activity.getSupportActionBar();
        if (actionBar != null) {
          actionBar.setTitle(R.string.conversation_list_menu__settings);
        }
      }

      setCategorySummaries();
    }

    private void setClickListenerIfPresent(@NonNull String key,
                                           @NonNull Preference.OnPreferenceClickListener listener) {
      Preference preference = findPreference(key);
      if (preference != null) {
        preference.setOnPreferenceClickListener(listener);
      }
    }

    private void handlePrivacyPolicy() {
      try {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL)));
      } catch (ActivityNotFoundException e) {
        ApplicationPreferencesActivity activity = getHostActivity();
        if (activity != null) {
          Toast.makeText(
                  activity.getApplicationContext(),
                  R.string.ConversationActivity_cant_open_link,
                  Toast.LENGTH_LONG
          ).show();
        }
      }
    }

    private void setCategorySummaries() {
      ApplicationPreferencesActivity activity = getHostActivity();
      if (activity == null) return;

      setSummaryIfPresent(
              PREFERENCE_CATEGORY_MESSAGES,
              MessagesPreferenceFragment.getSummary(activity)
      );

      setSummaryIfPresent(
              PREFERENCE_CATEGORY_NOTIFICATIONS,
              NotificationsPreferenceFragment.getSummary(activity)
      );

      setSummaryIfPresent(
              PREFERENCE_CATEGORY_APP_PROTECTION,
              AppProtectionPreferenceFragment.getSummary(activity)
      );

      setSummaryIfPresent(
              PREFERENCE_CATEGORY_APPEARANCE,
              AppearancePreferenceFragment.getSummary(activity)
      );

      setSummaryIfPresent(
              PREFERENCE_CATEGORY_CHATS,
              ChatsPreferenceFragment.getSummary()
      );

      String version = activity.getString(
              R.string.preferences__about_version,
              BuildConfig.VERSION_NAME
      );
      setSummaryIfPresent(PREFERENCE_ABOUT, version);
    }

    private void setSummaryIfPresent(@NonNull String key, @Nullable CharSequence summary) {
      Preference preference = findPreference(key);
      if (preference != null) {
        preference.setSummary(summary);
      }
    }

    @Nullable
    private ApplicationPreferencesActivity getHostActivity() {
      return (getActivity() instanceof ApplicationPreferencesActivity)
              ? (ApplicationPreferencesActivity) getActivity()
              : null;
    }

    private class CategoryClickListener implements Preference.OnPreferenceClickListener {

      @Nullable
      private final MasterSecret masterSecret;
      @NonNull
      private final String category;

      CategoryClickListener(@Nullable MasterSecret masterSecret, @NonNull String category) {
        this.masterSecret = masterSecret;
        this.category = category;
      }

      @Override
      public boolean onPreferenceClick(@NonNull Preference preference) {
        Fragment fragment = switch (category) {
          case PREFERENCE_CATEGORY_MESSAGES -> new MessagesPreferenceFragment();
          case PREFERENCE_CATEGORY_NOTIFICATIONS -> new NotificationsPreferenceFragment();
          case PREFERENCE_CATEGORY_APP_PROTECTION -> new AppProtectionPreferenceFragment();
          case PREFERENCE_CATEGORY_APPEARANCE -> new AppearancePreferenceFragment();
          case PREFERENCE_CATEGORY_CHATS -> new ChatsPreferenceFragment();
          case PREFERENCE_CATEGORY_ADVANCED -> new AdvancedPreferenceFragment();
          default -> throw new AssertionError("Unknown category: " + category);
        };

        Bundle args = new Bundle();
        args.putParcelable("master_secret", masterSecret);
        fragment.setArguments(args);

        FragmentManager fragmentManager = getParentFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.setCustomAnimations(
                R.anim.slide_from_right,
                R.anim.slide_to_left,
                R.anim.slide_from_left,
                R.anim.slide_to_right
        );
        transaction.replace(R.id.fragment_container, fragment);
        transaction.addToBackStack(null);
        transaction.commit();

        return true;
      }
    }
  }
}