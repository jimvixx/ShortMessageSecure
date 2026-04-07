package org.jimvixx.smsecure;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.model.ThreadRecord;
import org.jimvixx.smsecure.util.ViewUtil;

import java.util.Set;

public class ConversationListItemArchived extends LinearLayout implements BindableConversationListItem {

  private TextView description;

  public ConversationListItemArchived(Context context) {
    super(context);
  }

  public ConversationListItemArchived(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public ConversationListItemArchived(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @Override
  public void onFinishInflate() {
    super.onFinishInflate();
    this.description = ViewUtil.findById(this, R.id.description);
  }

  @Override
  public void bind(@NonNull MasterSecret masterSecret, @NonNull ThreadRecord thread, @NonNull Set<Long> selectedThreads, boolean batchMode) {
    this.description.setText(getContext().getString(R.string.ConversationListItemAction_archived_conversations_d, thread.getCount()));
  }

  @Override
  public void unbind() {

  }
}
