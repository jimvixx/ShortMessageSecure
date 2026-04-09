/*
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2013 Open Whisper Systems
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

package org.jimvixx.smsecure;

import android.content.Context;
import android.graphics.PorterDuff.Mode;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.util.ViewUtil;

import java.util.List;

public class TransportOptionsAdapter extends BaseAdapter {
  private final LayoutInflater inflater;

  private List<TransportOption> enabledTransports;

  public TransportOptionsAdapter(@NonNull Context context,
                                 @NonNull List<TransportOption> enabledTransports) {
    super();
    this.inflater = LayoutInflater.from(context);
    this.enabledTransports = enabledTransports;
  }

  public void setEnabledTransports(List<TransportOption> enabledTransports) {
    this.enabledTransports = enabledTransports;
  }

  @Override
  public int getCount() {
    return enabledTransports.size();
  }

  @Override
  public Object getItem(int position) {
    return enabledTransports.get(position);
  }

  @Override
  public long getItemId(int position) {
    return position;
  }

  @Override
  public View getView(int position, View convertView, ViewGroup parent) {
    if (convertView == null) {
      convertView = inflater.inflate(R.layout.transport_selection_list_item, parent, false);
    }

    TransportOption transport = (TransportOption) getItem(position);
    ImageView imageView = ViewUtil.findById(convertView, R.id.icon);
    TextView textView = ViewUtil.findById(convertView, R.id.text);
    TextView subtextView = ViewUtil.findById(convertView, R.id.subtext);

    imageView.getBackground().setColorFilter(transport.getBackgroundColor(), Mode.MULTIPLY);
    imageView.setImageResource(transport.getDrawable());
    textView.setText(transport.getDescription());

    if (transport.getSimName().isPresent()) {
      subtextView.setText(transport.getSimName().get());
      subtextView.setVisibility(View.VISIBLE);
    } else {
      subtextView.setVisibility(View.GONE);
    }

    return convertView;
  }
}
