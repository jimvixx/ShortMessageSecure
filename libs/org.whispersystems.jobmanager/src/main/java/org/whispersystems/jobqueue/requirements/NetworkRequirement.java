package org.whispersystems.jobqueue.requirements;

import android.Manifest;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.RequiresPermission;

import org.whispersystems.jobqueue.dependencies.ContextDependent;

public class NetworkRequirement implements Requirement, ContextDependent {

  private transient Context context;

  public NetworkRequirement(Context context) {
    this.context = context;
  }

  public NetworkRequirement() {
  }

  @Override
  @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
  public boolean isPresent() {
    ConnectivityManager cm =
            (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

    if (cm == null) return false;

    Network network = cm.getActiveNetwork();
    if (network == null) return false;

    NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
    if (capabilities == null) return false;

    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
  }

  @Override
  public void setContext(Context context) {
    this.context = context;
  }
}
