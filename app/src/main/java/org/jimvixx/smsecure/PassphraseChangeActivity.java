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

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.jimvixx.smsecure.crypto.InvalidPassphraseException;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.MasterSecretUtil;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.util.SMSecurePreferences;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activity for creating & changing a user's local encryption passphrase.
 */
public class PassphraseChangeActivity extends PassphraseActivity {

  private static final String TAG = PassphraseChangeActivity.class.getSimpleName();

  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  private TextInputEditText originalPassphrase;
  private TextInputEditText newPassphrase;
  private TextInputEditText repeatPassphrase;
  private Button okButton;

  @NonNull
  private static String safeText(@Nullable EditText editText) {
    if (editText == null) return "";
    Editable text = editText.getText();
    return text == null ? "" : text.toString();
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.passphrase_activity);

    Toolbar toolbar = findViewById(R.id.toolbar);
    if (toolbar != null) setSupportActionBar(toolbar);

    ActionBar actionBar = getSupportActionBar();
    if (actionBar != null) {
      actionBar.setDisplayHomeAsUpEnabled(true);
      actionBar.setDisplayShowHomeEnabled(true);
    }

    initializeResources();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    executor.shutdownNow();
  }

  @Override
  public boolean onSupportNavigateUp() {
    finish();
    return true;
  }

  private void initializeResources() {
    TextInputLayout newPassphraseTextInputLayout = findViewById(R.id.new_passphrase_layout);
    if (newPassphraseTextInputLayout != null)
      newPassphraseTextInputLayout.setVisibility(View.VISIBLE);

    TextInputLayout repeatPassphraseTextInputLayout = findViewById(R.id.repeat_passphrase_layout);
    if (repeatPassphraseTextInputLayout != null)
      repeatPassphraseTextInputLayout.setVisibility(View.VISIBLE);

    Button cancelButton = findViewById(R.id.cancel_button);
    if (cancelButton != null) {
      cancelButton.setVisibility(View.VISIBLE);
      cancelButton.setOnClickListener(new CancelButtonClickListener());
    }

    okButton = findViewById(R.id.ok_button);
    if (okButton != null) {
      okButton.setVisibility(View.VISIBLE);
      okButton.setOnClickListener(new OkButtonClickListener());
    }

    originalPassphrase = findViewById(R.id.old_passphrase);
    newPassphrase = findViewById(R.id.new_passphrase);
    repeatPassphrase = findViewById(R.id.repeat_passphrase);

    TextInputLayout oldPassphraseTextInputLayout = findViewById(R.id.old_passphrase_layout);
    TextView appTitle = findViewById(R.id.app_title);
    if (SMSecurePreferences.isPasswordDisabled(this)) {
      oldPassphraseTextInputLayout.setVisibility(View.GONE);
      if (appTitle != null) appTitle.setText(R.string.PassphraseChangeActivity_create_passphrase);
    } else {
      oldPassphraseTextInputLayout.setVisibility(View.VISIBLE);
      if (appTitle != null) appTitle.setText(R.string.PassphraseChangeActivity_change_passphrase);
    }
  }

  private void verifyAndSavePassphrases() {
    String passphrase = safeText(newPassphrase);
    String passphraseRepeat = safeText(repeatPassphrase);

    String original;
    original = (SMSecurePreferences.isPasswordDisabled(this) ?
            MasterSecretUtil.UNENCRYPTED_PASSPHRASE :
            safeText(originalPassphrase));

    if (!passphrase.equals(passphraseRepeat)) {
      newPassphrase.setText("");
      repeatPassphrase.setText("");
      newPassphrase.setError(
              getString(R.string.PassphraseChangeActivity_passphrases_dont_match_exclamation)
      );
      newPassphrase.requestFocus();
      return;
    }

    if (passphrase.isEmpty()) {
      newPassphrase.setError(
              getString(R.string.PassphraseChangeActivity_enter_new_passphrase_exclamation)
      );
      newPassphrase.requestFocus();
      return;
    }

    okButton.setEnabled(false);
    executor.execute(new ChangePassphraseRunner(this, original, passphrase));
  }

  private void onChangeFinished(@Nullable MasterSecret masterSecret) {
    okButton.setEnabled(true);

    if (masterSecret != null) {
      setMasterSecret(masterSecret);
      return;
    }

    if (originalPassphrase != null) {
      originalPassphrase.setText("");
      originalPassphrase.setError(
              getString(R.string.PassphraseChangeActivity_incorrect_old_passphrase_exclamation)
      );
      originalPassphrase.requestFocus();
    }
  }

  @Override
  protected void cleanup() {
    originalPassphrase = null;
    newPassphrase = null;
    repeatPassphrase = null;
    System.gc();
  }

  /**
   * Static worker to avoid leaking the Activity.
   */
  private static final class ChangePassphraseRunner implements Runnable {

    private final WeakReference<PassphraseChangeActivity> activityRef;
    private final String original;
    private final String passphrase;

    ChangePassphraseRunner(@NonNull PassphraseChangeActivity activity,
                           @NonNull String original,
                           @NonNull String passphrase) {
      this.activityRef = new WeakReference<>(activity);
      this.original = original;
      this.passphrase = passphrase;
    }

    @Override
    public void run() {
      PassphraseChangeActivity activity = activityRef.get();
      if (activity == null) return;

      Context appContext = activity.getApplicationContext();
      MasterSecret masterSecret = null;

      try {
        masterSecret =
                MasterSecretUtil.changeMasterSecretPassphrase(appContext, original, passphrase);
        SMSecurePreferences.setPasswordDisabled(appContext, false);
      } catch (InvalidPassphraseException e) {
        Log.w(TAG, e);
      } catch (RuntimeException e) {
        Log.w(TAG, "Unexpected error changing passphrase", e);
      }

      MasterSecret result = masterSecret;

      activity.mainHandler.post(() -> {
        PassphraseChangeActivity a = activityRef.get();
        if (a == null || a.isFinishing()) return;
        a.onChangeFinished(result);
      });
    }
  }

  private class CancelButtonClickListener implements OnClickListener {
    @Override
    public void onClick(View v) {
      finish();
    }
  }

  private class OkButtonClickListener implements OnClickListener {
    @Override
    public void onClick(View v) {
      verifyAndSavePassphrases();
    }
  }
}
