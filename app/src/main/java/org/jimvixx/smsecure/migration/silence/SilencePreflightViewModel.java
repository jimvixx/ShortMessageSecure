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
  public SilencePreflightViewModel(@NonNull Application app) { super(app); }
  public LiveData<SilenceImportPhase> getPhase() { return phase; }
  public SilencePreflightResult getResult() { return result; }

  public void analyze(Uri uri) {
    if (running) return;
    running = true;
    result = null;
    phase.setValue(SilenceImportPhase.ANALYZING);
    executor.execute(() -> {
      SilencePreflightResult outcome;
      try {
        outcome = SilenceImportCoordinator.inspect(
            new SilenceDirectoryBackupSource(getApplication().getContentResolver(), uri),
            getApplication().getCacheDir());
      } catch (IOException | RuntimeException e) {
        // Do not expose backup paths, preference values, message content, or provider errors.
        outcome = SilencePreflightResult.rejected();
      }
      final SilencePreflightResult completed = outcome;
      new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
        result = completed;
        running = false;
        phase.setValue(completed.getStatus() == SilencePreflightResult.Status.STRUCTURALLY_VALID
            ? SilenceImportPhase.COMPLETE : SilenceImportPhase.FAILED);
      });
    });
  }

  @Override protected void onCleared() { executor.shutdownNow(); }
}
