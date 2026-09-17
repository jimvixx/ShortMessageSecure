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

package org.jimvixx.smsecure.migration.silence;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.view.View;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import org.jimvixx.smsecure.PassphraseRequiredActionBarActivity;
import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.crypto.MasterSecret;

/** Analysis-only UI; no apply action or reference to the normal restore pipeline. */
public final class SilencePreflightActivity extends PassphraseRequiredActionBarActivity {
  private SilencePreflightViewModel model;
  private final ActivityResultLauncher<android.net.Uri> picker = registerForActivityResult(
      new ActivityResultContracts.OpenDocumentTree() {
        @NonNull @Override public android.content.Intent createIntent(@NonNull android.content.Context context,
                                                                       @Nullable android.net.Uri input) {
          return super.createIntent(context, input)
              .setFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
      }, uri -> {
        if (uri != null) model.analyze(uri);
      });

  @Override protected void onCreate(@Nullable Bundle state, @NonNull MasterSecret masterSecret) {
    setContentView(R.layout.silence_preflight);
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    model = new ViewModelProvider(this).get(SilencePreflightViewModel.class);
    Button select = findViewById(R.id.silence_select);
    TextView status = findViewById(R.id.silence_status);
    EditText password = findViewById(R.id.silence_password);
    Button verify = findViewById(R.id.silence_verify);
    verify.setOnClickListener(view -> {
      char[] value = new char[password.length()];
      password.getText().getChars(0, value.length, value, 0);
      password.getText().clear();
      model.verifyPassword(value);
    });
    select.setOnClickListener(view -> {
      password.getText().clear();
      picker.launch(null);
    });
    model.getPhase().observe(this, phase -> {
      select.setEnabled(phase != SilenceImportPhase.ANALYZING);
      verify.setEnabled(phase != SilenceImportPhase.ANALYZING);
      password.setEnabled(phase != SilenceImportPhase.ANALYZING);
      password.setVisibility(View.GONE);
      verify.setVisibility(View.GONE);
      if (phase == SilenceImportPhase.ANALYZING) status.setText(R.string.silence_preflight_running);
      else if (phase == SilenceImportPhase.FAILED) status.setText(R.string.silence_preflight_failed);
      else if (phase == SilenceImportPhase.COMPLETE) {
        SilenceBackupInfo info = model.getResult().getInfo();
        status.setText(getString(R.string.silence_preflight_result, info.getDatabaseVersion(),
            info.getSmsCount(), info.getMmsCount(), info.getCryptoFileCount()));
        SilenceCryptoVerificationInfo crypto = model.getResult().getCryptoVerification();
        if (crypto != null) {
          if (crypto.getStatus() == SilenceCryptoVerificationInfo.Status.VERIFIED) {
            status.append("\n\n" + getString(R.string.silence_crypto_verified,
                crypto.getVerifiedSmsCount(), crypto.getUncheckedSmsCount()));
            SilenceIdentityInfo identities = crypto.getIdentities();
            if (identities != null) {
              int message = identities.getStatus() == SilenceIdentityInfo.Status.VERIFIED
                  ? R.string.silence_identity_verified : identities.getStatus() == SilenceIdentityInfo.Status.ABSENT
                  ? R.string.silence_identity_absent : R.string.silence_identity_rejected;
              status.append("\n\n" + getString(message, identities.getCount()));
            }
            SilenceCryptoFileInfo files = crypto.getFiles();
            if (files != null) {
              status.append("\n\n" + (files.getStatus() == SilenceCryptoFileInfo.Status.READABLE
                  ? getString(R.string.silence_crypto_files_readable, files.getSessionCount(),
                      files.getPreKeyCount(), files.getSignedPreKeyCount())
                  : getString(R.string.silence_crypto_files_rejected)));
              SilenceRemoteIdentityInfo remote = files.getRemoteIdentities();
              if (remote != null) status.append("\n\n" + (remote.getStatus() == SilenceRemoteIdentityInfo.Status.AUTHENTICATED
                  ? getString(R.string.silence_remote_identities_checked, remote.getRecordCount(), remote.getCurrentMatches(),
                      remote.getCurrentMissing(), remote.getCurrentDifferent(), remote.getArchivedMatches(), remote.getArchivedUnmatched())
                  : getString(R.string.silence_remote_identities_rejected)));
            }
          } else {
            status.append("\n\n" + getString(crypto.getStatus() == SilenceCryptoVerificationInfo.Status.PASSWORD_REQUIRED
                ? R.string.silence_crypto_password_required : R.string.silence_crypto_rejected));
            if (!info.isPassphraseDisabled()) {
              password.setVisibility(View.VISIBLE);
              verify.setVisibility(View.VISIBLE);
            }
          }
        }
        SilenceMigrationPlan plan = model.getResult().getMigrationPlan();
        if (plan != null) {
          status.append("\n\n" + getString(R.string.silence_plan_summary, plan.getSubscriptions().getSources().size(),
              plan.getSubscriptions().getSmsWithoutSubscription(), plan.getPreferences().getCandidates().size(),
              plan.getPreferences().getDeferredCount()));
          if (!plan.isCryptoInventoryChecked()) status.append("\n" + getString(R.string.silence_plan_partial));
        }
      } else status.setText(R.string.silence_preflight_description);
    });
  }
  @Override protected void onResume() {
    super.onResume();
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
  }

  @Override protected void onDestroy() {
    EditText password = findViewById(R.id.silence_password);
    if (password != null) password.getText().clear();
    super.onDestroy();
  }

}
