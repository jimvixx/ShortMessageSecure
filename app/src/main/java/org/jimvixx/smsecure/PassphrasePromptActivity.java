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

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.textfield.TextInputLayout;

import org.jimvixx.smsecure.crypto.InvalidPassphraseException;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.MasterSecretUtil;
import org.jimvixx.smsecure.logging.Log;

/**
 * Activity that prompts the user for a passphrase to unlock the local key.
 */
public class PassphrasePromptActivity extends PassphraseActivity {

  private static final String TAG = PassphrasePromptActivity.class.getSimpleName();

  private EditText passphraseText;
  private Button okButton;

  /**
   * Prevents duplicate unlock attempts from fast taps / IME / repeated callbacks.
   */
  private boolean unlockInProgress;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.passphrase_activity);

    Toolbar toolbar = findViewById(R.id.toolbar);
    if (toolbar != null) setSupportActionBar(toolbar);

    ActionBar actionBar = getSupportActionBar();
    if (actionBar != null) {
      actionBar.setDisplayShowTitleEnabled(true);
      actionBar.setDisplayHomeAsUpEnabled(false);
      actionBar.setDisplayShowHomeEnabled(true);
      actionBar.setTitle(R.string.app_name);
    }

    bindViews();
    initializeResources();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
  }

  @Override
  public boolean onSupportNavigateUp() {
    finish();
    return true;
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuInflater inflater = getMenuInflater();
    menu.clear();

    inflater.inflate(R.menu.log_submit, menu);
    return super.onPrepareOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == R.id.menu_submit_debug_logs) {
      handleLogSubmit();
      return true;
    }

    return super.onOptionsItemSelected(item);
  }

  private void handleLogSubmit() {
    Intent intent = new Intent(this, LogSubmitActivity.class);
    startActivity(intent);
  }

  private void handlePassphrase() {
    if (unlockInProgress) {
      Log.w(TAG, "handlePassphrase(): unlock already in progress, ignoring");
      return;
    }

    try {
      Editable text = passphraseText.getText();
      String passphrase = (text == null ? "" : text.toString());
      MasterSecret masterSecret = MasterSecretUtil.getMasterSecret(this, passphrase);

      unlockInProgress = true;
      setUnlockUiEnabled(false);
      setMasterSecret(masterSecret);
    } catch (InvalidPassphraseException ipe) {
      unlockInProgress = false;
      setUnlockUiEnabled(true);

      passphraseText.setText("");
      passphraseText.setError(
              getString(R.string.PassphrasePromptActivity_invalid_passphrase_exclamation)
      );
      passphraseText.requestFocus();
    }
  }

  private void initializeResources() {
    okButton = findViewById(R.id.enter_button);
    passphraseText = findViewById(R.id.passphrase_edit);

    if (okButton != null) {
      okButton.setOnClickListener(new OkButtonClickListener());
    }

    if (passphraseText != null) {
      passphraseText.setOnEditorActionListener(new PassphraseActionListener());

      passphraseText.setImeActionLabel(
              getString(R.string.prompt_passphrase_activity__unlock),
              EditorInfo.IME_ACTION_DONE
      );
    }
  }

  private void setUnlockUiEnabled(boolean enabled) {
    if (okButton != null) {
      okButton.setEnabled(enabled);
    }

    if (passphraseText != null) {
      passphraseText.setEnabled(enabled);
    }
  }

  @Override
  protected void cleanup() {
    if (passphraseText != null) {
      passphraseText.setText("");
    }

    System.gc();
  }

  private void bindViews() {
    ImageView appLogo = findViewById(R.id.app_logo);
    if (appLogo != null) appLogo.setVisibility(View.VISIBLE);

    ImageView watermark = findViewById(R.id.watermark);
    if (watermark != null) watermark.setVisibility(View.VISIBLE);

    TextInputLayout textInputLayout = findViewById(R.id.passphrase_input_layout);
    if (textInputLayout != null) textInputLayout.setVisibility(View.VISIBLE);

    Button button = findViewById(R.id.enter_button);
    if (button != null) button.setVisibility(View.VISIBLE);
  }

  private class PassphraseActionListener implements TextView.OnEditorActionListener {
    @Override
    public boolean onEditorAction(TextView view, int actionId, KeyEvent keyEvent) {
      if ((keyEvent == null && actionId == EditorInfo.IME_ACTION_DONE) ||
              (keyEvent != null && keyEvent.getAction() == KeyEvent.ACTION_DOWN &&
                      actionId == EditorInfo.IME_NULL)) {
        handlePassphrase();
        return true;
      }

      return keyEvent != null &&
              keyEvent.getAction() == KeyEvent.ACTION_UP &&
              actionId == EditorInfo.IME_NULL;
    }
  }

  private class OkButtonClickListener implements View.OnClickListener {
    @Override
    public void onClick(View v) {
      handlePassphrase();
    }
  }
}