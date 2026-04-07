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
import androidx.annotation.NonNull;
import androidx.appcompat.widget.ListPopupWindow;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;

import java.util.LinkedList;
import java.util.List;

public class TransportOptionsPopup extends ListPopupWindow implements ListView.OnItemClickListener {

  private final TransportOptionsAdapter adapter;
  private final SelectedListener        listener;

  private boolean forceSend = false;

  public TransportOptionsPopup(@NonNull Context context, @NonNull View anchor, @NonNull SelectedListener listener) {
    super(context);
    this.listener = listener;
    this.adapter  = new TransportOptionsAdapter(context, new LinkedList<>());

    setVerticalOffset(context.getResources().getDimensionPixelOffset(R.dimen.transport_selection_popup_yoff));
    setHorizontalOffset(context.getResources().getDimensionPixelOffset(R.dimen.transport_selection_popup_xoff));
    setInputMethodMode(ListPopupWindow.INPUT_METHOD_NOT_NEEDED);
    setModal(true);
    setAnchorView(anchor);
    setAdapter(adapter);
    setContentWidth(context.getResources().getDimensionPixelSize(R.dimen.transport_selection_popup_width));

    setOnItemClickListener(this);
  }

  public void display(List<TransportOption> enabledTransports) {
    adapter.setEnabledTransports(enabledTransports);
    adapter.notifyDataSetChanged();
    show();
  }

  @Override
  public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
    listener.onSelected((TransportOption)adapter.getItem(position));
  }

  public interface SelectedListener {
    void onSelected(TransportOption option);
  }

}
