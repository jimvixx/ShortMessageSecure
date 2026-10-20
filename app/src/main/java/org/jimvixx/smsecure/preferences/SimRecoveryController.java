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

package org.jimvixx.smsecure.preferences;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import org.jimvixx.smsecure.ApplicationContext;
import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.jobs.GenerateKeysJob;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.util.dualsim.SubscriptionInfoCompat;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;
import org.jimvixx.smsecure.util.dualsim.SubscriptionRecoveryPlan;
import org.whispersystems.libsignal.util.guava.Optional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Confirmation dialogs are discarded on pause; no stale choice is restored after rotation. */
final class SimRecoveryController {
  private final Fragment host;
  private AlertDialog dialog;
  private boolean running;

  SimRecoveryController(Fragment host) {
    this.host = host;
  }

  void show() {
    if (running || !host.isAdded()) return;
    List<SubscriptionInfoCompat> active = SubscriptionManagerCompat.from(host.requireContext())
            .getActiveSubscriptionInfoList();
    if (active.isEmpty()) {
      message(R.string.preferences__sim_recovery_no_active);
      return;
    }
    Map<Integer, List<String>> groups = new LinkedHashMap<>();
    for (SubscriptionInfoCompat info : active) {
      if (info.hasMappingConflict()) {
        groups.computeIfAbsent(info.getSubscriptionId(), ignored -> new ArrayList<>()).add(label(info));
      }
    }
    if (groups.isEmpty()) {
      message(R.string.preferences__sim_recovery_no_conflicts);
      return;
    }
    List<Integer> ids = new ArrayList<>(groups.size());
    String[] labels = new String[groups.size()];
    int index = 0;
    for (Map.Entry<Integer, List<String>> group : groups.entrySet()) {
      ids.add(group.getKey());
      labels[index++] = android.text.TextUtils.join(", ", Objects.requireNonNull(group.getValue()));
    }
    dismiss();
    dialog = new AlertDialog.Builder(host.requireContext())
            .setTitle(R.string.preferences__sim_recovery_choose_group)
            .setItems(labels, (ignored, which) -> chooseOwner(ids.get(which)))
            .setNegativeButton(android.R.string.cancel, null).show();
  }

  private void chooseOwner(int appId) {
    Optional<SubscriptionRecoveryPlan> pending = SubscriptionManagerCompat.from(host.requireContext()).prepareRecovery(appId);
    if (!pending.isPresent()) {
      message(R.string.preferences__sim_recovery_changed);
      return;
    }
    SubscriptionRecoveryPlan plan = pending.get();
    List<SubscriptionInfoCompat> candidates = plan.getCandidates();
    String[] labels = new String[candidates.size()];
    for (int i = 0; i < candidates.size(); i++) labels[i] = label(candidates.get(i));
    dismiss();
    dialog = new AlertDialog.Builder(host.requireContext())
            .setTitle(R.string.preferences__sim_recovery_choose_owner)
            .setItems(labels, (ignored, which) -> confirm(plan, candidates.get(which)))
            .setNegativeButton(android.R.string.cancel, null).show();
  }

  private void confirm(SubscriptionRecoveryPlan plan, SubscriptionInfoCompat selected) {
    dismiss();
    dialog = new AlertDialog.Builder(host.requireContext())
            .setTitle(host.getString(R.string.preferences__sim_recovery_confirm_title, label(selected)))
            .setMessage(R.string.preferences__sim_recovery_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.preferences__sim_recovery_apply, (ignored, which) -> apply(plan, selected.getDeviceSubscriptionId()))
            .show();
  }

  private void apply(SubscriptionRecoveryPlan plan, int deviceId) {
    if (running) return;
    running = true;
    Context context = host.requireContext().getApplicationContext();
    // Applying may wait for an in-flight SMS to be handed to Android under the same lock.
    new Thread(() -> {
      int result;
      try {
        boolean applied = SubscriptionManagerCompat.from(context).applyRecovery(plan, deviceId);
        result = applied ? R.string.preferences__sim_recovery_success : R.string.preferences__sim_recovery_changed;
        if (applied) {
          try {
            ApplicationContext.getInstance(context).getJobManager().add(new GenerateKeysJob(context));
          } catch (RuntimeException failure) {
            Log.w("SimRecovery", "Key initialization could not be queued");
            result = R.string.preferences__sim_recovery_keys_pending;
          }
        }
      } catch (RuntimeException failure) {
        Log.w("SimRecovery", "Recovery failed: " + failure.getClass().getSimpleName());
        result = R.string.preferences__sim_recovery_failed;
      }
      int resultMessage = result;
      new Handler(Looper.getMainLooper()).post(() -> {
        running = false;
        if (host.isAdded() && host.isResumed()) message(resultMessage);
      });
    }, "sim-recovery").start();
  }

  private String label(SubscriptionInfoCompat info) {
    return host.getString(R.string.preferences__sim_recovery_sim_label, info.getIccSlot(), info.getDisplayName());
  }

  private void message(int text) {
    dismiss();
    dialog = new AlertDialog.Builder(host.requireContext())
            .setTitle(R.string.preferences__sim_recovery_title).setMessage(text)
            .setPositiveButton(android.R.string.ok, null).show();
  }

  void dismiss() {
    if (dialog != null) {
      dialog.dismiss();
      dialog = null;
    }
  }
}
