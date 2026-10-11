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

package org.jimvixx.smsecure.search;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.components.AvatarImageView;
import org.jimvixx.smsecure.database.model.ThreadRecord;
import org.jimvixx.smsecure.util.DateUtils;
import org.jimvixx.smsecure.util.ThemeUtil;

import java.util.ArrayList;
import java.util.List;

public final class GlobalSearchAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
  private static final int TYPE_HEADER = 0;
  private static final int TYPE_RESULT = 1;

  public interface Listener {
    void onSearchResultClicked(@NonNull GlobalSearchResult result);
  }

  private final LayoutInflater inflater;
  private final Listener listener;
  private final List<GlobalSearchResult> results = new ArrayList<>();
  private String query = "";

  public GlobalSearchAdapter(@NonNull Context context, @NonNull Listener listener) {
    this.inflater = LayoutInflater.from(context);
    this.listener = listener;
    setHasStableIds(true);
  }

  public void submit(@NonNull String query, @NonNull List<GlobalSearchResult> values) {
    this.query = query;
    this.results.clear();
    this.results.addAll(values);
    notifyDataSetChanged();
  }

  @Override
  public int getItemViewType(int position) {
    return results.get(position).getType() == GlobalSearchResult.Type.HEADER
            ? TYPE_HEADER : TYPE_RESULT;
  }

  @Override
  public long getItemId(int position) {
    GlobalSearchResult result = results.get(position);
    if (result.getType() == GlobalSearchResult.Type.HEADER) return -position - 1L;
    ThreadRecord thread = result.getThread();
    if (thread == null) return -position - 1L;
    return result.getType() == GlobalSearchResult.Type.CHAT
            ? thread.getThreadId()
            : (Long.MIN_VALUE ^ result.getMessageId());
  }

  @Override
  public @NonNull RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    if (viewType == TYPE_HEADER) {
      return new HeaderHolder(inflater.inflate(R.layout.global_search_header, parent, false));
    }
    return new ResultHolder(inflater.inflate(R.layout.global_search_result_item, parent, false));
  }

  @Override
  public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
    GlobalSearchResult result = results.get(position);
    if (holder instanceof HeaderHolder headerHolder) {
      headerHolder.title.setText(result.getHeader());
    } else if (holder instanceof ResultHolder resultHolder) {
      resultHolder.bind(result, query, listener);
    }
  }

  @Override
  public int getItemCount() {
    return results.size();
  }

  private static final class HeaderHolder extends RecyclerView.ViewHolder {
    private final TextView title;

    HeaderHolder(@NonNull View itemView) {
      super(itemView);
      title = itemView.findViewById(R.id.search_header);
    }
  }

  private static final class ResultHolder extends RecyclerView.ViewHolder {
    private final AvatarImageView avatar;
    private final TextView title;
    private final TextView excerpt;
    private final TextView date;
    private final TextView archived;

    ResultHolder(@NonNull View itemView) {
      super(itemView);
      avatar = itemView.findViewById(R.id.search_avatar);
      title = itemView.findViewById(R.id.search_title);
      excerpt = itemView.findViewById(R.id.search_excerpt);
      date = itemView.findViewById(R.id.search_date);
      archived = itemView.findViewById(R.id.search_archived);
    }

    void bind(@NonNull GlobalSearchResult result, @NonNull String query,
              @NonNull Listener listener) {
      ThreadRecord thread = result.getThread();
      if (thread == null) return;

      int accent = ThemeUtil.resolveThemeColor(itemView.getContext(), R.attr.appColorAccent);
      CharSequence recipientName = thread.getRecipients().toShortString();
      title.setText(result.getType() == GlobalSearchResult.Type.CHAT
              ? SearchHighlightUtil.highlight(recipientName, query, accent)
              : recipientName);
      avatar.setAvatar(thread.getRecipients(), true);

      CharSequence body;
      if (result.getType() == GlobalSearchResult.Type.MESSAGE) {
        String messageBody = result.getMessageBody() == null ? "" : result.getMessageBody();
        body = SearchHighlightUtil.excerptAroundMatch(messageBody, query);
      } else {
        body = thread.getDisplayBody();
      }
      excerpt.setText(result.getType() == GlobalSearchResult.Type.MESSAGE
              ? SearchHighlightUtil.highlight(body == null ? "" : body, query, accent)
              : body);

      long timestamp = result.getType() == GlobalSearchResult.Type.MESSAGE
              ? result.getMessageDate() : thread.getDate();
      date.setText(timestamp > 0
              ? DateUtils.getBriefRelativeTimeSpanString(itemView.getContext(), timestamp)
              : "");
      archived.setVisibility(thread.isArchived() ? View.VISIBLE : View.GONE);
      itemView.setOnClickListener(v -> listener.onSearchResultClicked(result));
    }
  }
}
