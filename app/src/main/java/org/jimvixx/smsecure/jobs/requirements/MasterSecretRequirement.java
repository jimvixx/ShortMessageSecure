package org.jimvixx.smsecure.jobs.requirements;

import android.content.Context;

import org.jimvixx.smsecure.service.KeyCachingService;
import org.whispersystems.jobqueue.dependencies.ContextDependent;
import org.whispersystems.jobqueue.requirements.Requirement;

public class MasterSecretRequirement implements Requirement, ContextDependent {

  private transient Context context;

  public MasterSecretRequirement(Context context) {
    this.context = context;
  }

  @Override
  public boolean isPresent() {
    return KeyCachingService.getMasterSecret(context) != null;
  }

  @Override
  public void setContext(Context context) {
    this.context = context;
  }
}
