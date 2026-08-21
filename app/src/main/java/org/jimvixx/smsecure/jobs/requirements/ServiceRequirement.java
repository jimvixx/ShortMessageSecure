package org.jimvixx.smsecure.jobs.requirements;

import android.content.Context;

import org.jimvixx.smsecure.sms.TelephonyServiceState;
import org.whispersystems.jobqueue.dependencies.ContextDependent;
import org.whispersystems.jobqueue.requirements.Requirement;

public class ServiceRequirement implements Requirement, ContextDependent {

  private transient Context context;

  public ServiceRequirement(Context context) {
    this.context = context;
  }

  @Override
  public void setContext(Context context) {
    this.context = context;
  }

  @Override
  public boolean isPresent() {
    boolean connected = new TelephonyServiceState().isConnected(context);

    if (!connected) {
      ServiceRequirementProvider.listenForServiceState();
      return false;
    }

    return ServiceRequirementProvider.isServiceReady();
  }
}
