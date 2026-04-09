package org.jimvixx.smsecure.jobs.requirements;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

import org.jimvixx.smsecure.service.KeyCachingService;
import org.whispersystems.jobqueue.requirements.RequirementListener;
import org.whispersystems.jobqueue.requirements.RequirementProvider;

public class MasterSecretRequirementProvider implements RequirementProvider {

  private final BroadcastReceiver newKeyReceiver;

  private RequirementListener listener;

  public MasterSecretRequirementProvider(Context context) {
    this.newKeyReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        if (listener != null) {
          listener.onRequirementStatusChanged();
        }
      }
    };

    IntentFilter filter = new IntentFilter(KeyCachingService.NEW_KEY_EVENT);
    ContextCompat.registerReceiver(
            context,
            newKeyReceiver,
            filter,
            KeyCachingService.KEY_PERMISSION,
            null,
            ContextCompat.RECEIVER_NOT_EXPORTED
    );
  }

  @Override
  public void setListener(RequirementListener listener) {
    this.listener = listener;
  }
}
