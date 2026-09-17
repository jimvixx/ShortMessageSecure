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
    select.setOnClickListener(view -> picker.launch(null));
    model.getPhase().observe(this, phase -> {
      select.setEnabled(phase != SilenceImportPhase.ANALYZING);
      if (phase == SilenceImportPhase.ANALYZING) status.setText(R.string.silence_preflight_running);
      else if (phase == SilenceImportPhase.FAILED) status.setText(R.string.silence_preflight_failed);
      else if (phase == SilenceImportPhase.COMPLETE) {
        SilenceBackupInfo info = model.getResult().getInfo();
        status.setText(getString(R.string.silence_preflight_result, info.getDatabaseVersion(),
            info.getSmsCount(), info.getMmsCount(), info.getCryptoFileCount()));
      } else status.setText(R.string.silence_preflight_description);
    });
  }
}
