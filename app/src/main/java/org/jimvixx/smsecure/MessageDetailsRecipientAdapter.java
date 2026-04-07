package org.jimvixx.smsecure;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.model.MessageRecord;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.Recipients;

public class MessageDetailsRecipientAdapter extends BaseAdapter implements AbsListView.RecyclerListener {

  private final Context       context;
  private final MasterSecret  masterSecret;
  private final MessageRecord record;
  private final Recipients    recipients;

  public MessageDetailsRecipientAdapter(Context context, MasterSecret masterSecret,
                                        MessageRecord record, Recipients recipients)
  {
    this.context      = context;
    this.masterSecret = masterSecret;
    this.record       = record;
    this.recipients   = recipients;
  }

  @Override
  public int getCount() {
    return recipients.getRecipientsList().size();
  }

  @Override
  public Object getItem(int position) {
    return recipients.getRecipientsList().get(position);
  }

  @Override
  public long getItemId(int position) {
    return recipients.getRecipientsList().get(position).getRecipientId();
  }

  @Override
  public View getView(int position, View convertView, ViewGroup parent) {
    if (convertView == null) {
      convertView = LayoutInflater.from(context).inflate(R.layout.message_recipient_list_item, parent, false);
    }

    Recipient recipient = recipients.getRecipientsList().get(position);
    ((MessageRecipientListItem)convertView).set(masterSecret, record, recipient);
    return convertView;
  }

  @Override
  public void onMovedToScrapHeap(View view) {
    ((MessageRecipientListItem)view).unbind();
  }

}
