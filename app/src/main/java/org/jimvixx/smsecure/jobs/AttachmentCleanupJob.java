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
package org.jimvixx.smsecure.jobs;

import android.content.Context;
import org.jimvixx.smsecure.database.AttachmentCleanupProcessor;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.whispersystems.jobqueue.JobParameters;

/** Runs one bounded pass over durable attachment-cleanup work. */
public final class AttachmentCleanupJob extends ContextJob {
  public AttachmentCleanupJob(Context context) {
    super(context, JobParameters.newBuilder().withPersistence().withRetryCount(0).create());
  }

  @Override public void onAdded() {}

  @Override public void onRun() {
    AttachmentCleanupProcessor.processBatch(context,
            DatabaseFactory.getDatabaseHelper(context), System.currentTimeMillis(), 32);
  }

  @Override public boolean onShouldRetry(Exception exception) {
    return false;
  }

  @Override public void onCanceled() {}
}
