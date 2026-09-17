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

import android.app.Application;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SilencePreflightViewModel extends AndroidViewModel {
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final MutableLiveData<SilenceImportPhase> phase = new MutableLiveData<>(SilenceImportPhase.IDLE);
  private SilencePreflightResult result;
  private boolean running;
  private Uri selectedUri;
  private volatile boolean cleared;
  private volatile char[] pendingPassword;
  public SilencePreflightViewModel(@NonNull Application app) { super(app); }
  public LiveData<SilenceImportPhase> getPhase() { return phase; }
  public SilencePreflightResult getResult() { return result; }

  public void analyze(Uri uri) { analyze(uri, null); }

  /** Takes ownership of the array and wipes it after use, cancellation, or rejection. */
  public void verifyPassword(char[] password) {
    if (selectedUri == null) { wipe(password); return; }
    analyze(selectedUri, password);
  }

  private void analyze(Uri uri, char[] password) {
    if (running || cleared) { wipe(password); return; }
    selectedUri = uri;
    pendingPassword = password;
    running = true;
    result = null;
    phase.setValue(SilenceImportPhase.ANALYZING);
    executor.execute(() -> {
      SilencePreflightResult outcome;
      try {
        outcome = SilenceImportCoordinator.inspect(
            new SilenceDirectoryBackupSource(getApplication().getContentResolver(), uri),
            getApplication().getCacheDir(), password);
      } catch (IOException | RuntimeException e) {
        // Do not expose backup paths, preference values, message content, or provider errors.
        outcome = SilencePreflightResult.rejected();
      } finally {
        wipe(password);
        pendingPassword = null;
      }
      final SilencePreflightResult completed = outcome;
      new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
        if (cleared) return;
        result = completed;
        running = false;
        phase.setValue(completed.getStatus() == SilencePreflightResult.Status.STRUCTURALLY_VALID
            ? SilenceImportPhase.COMPLETE : SilenceImportPhase.FAILED);
      });
    });
  }

  private static void wipe(char[] value) { if (value != null) java.util.Arrays.fill(value, '\0'); }
  @Override protected void onCleared() {
    cleared = true;
    wipe(pendingPassword);
    executor.shutdownNow();
  }
}
