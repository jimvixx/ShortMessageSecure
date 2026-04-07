package org.jimvixx.smsecure;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.model.ThreadRecord;

import java.util.Set;

public interface BindableConversationListItem extends Unbindable {

  void bind(@NonNull MasterSecret masterSecret, @NonNull ThreadRecord thread,
            @NonNull Set<Long> selectedThreads, boolean batchMode);
}
