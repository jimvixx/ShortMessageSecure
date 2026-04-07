package org.whispersystems.jobqueue.requirements;

import android.Manifest;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;

public class NetworkRequirementProvider implements RequirementProvider {

  private final NetworkRequirement requirement;
  private volatile RequirementListener listener;

  @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
  public NetworkRequirementProvider(Context context) {
    this.requirement = new NetworkRequirement(context);
    Context appContext = context.getApplicationContext();

    ConnectivityManager cm =
            (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);

    if (cm == null) return;

    //noinspection MissingPermission
    cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
      @Override
      public void onAvailable(@NonNull Network network) {
        //noinspection MissingPermission
        notifyIfSatisfied();
      }

      @Override
      public void onLost(@NonNull Network network) {
        //noinspection MissingPermission
        notifyIfSatisfied();
      }
    });
  }

  @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
  private void notifyIfSatisfied() {
    RequirementListener l = listener;
    if (l != null && requirement.isPresent()) {
      l.onRequirementStatusChanged();
    }
  }

  @Override
  public void setListener(RequirementListener listener) {
    this.listener = listener;
  }
}
