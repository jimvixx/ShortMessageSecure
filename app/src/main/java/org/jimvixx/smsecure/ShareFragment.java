/*
 * Copyright (C) 2014 Open Whisper Systems
 * Copyright (C) 2025 Jimvixx
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
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cursoradapter.widget.CursorAdapter;
import androidx.fragment.app.ListFragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.loaders.ConversationListLoader;
import org.jimvixx.smsecure.recipients.Recipients;

/**
 * A fragment to select and share to open conversations
 */
public class ShareFragment extends ListFragment implements LoaderManager.LoaderCallbacks<Cursor> {

  private static final String ARG_MASTER_SECRET = "master_secret";

  private ConversationSelectedListener listener;
  @Nullable
  private MasterSecret masterSecret;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    final Bundle args = getArguments();
    if (args != null) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        masterSecret = args.getParcelable(ARG_MASTER_SECRET, MasterSecret.class);
      } else {
        masterSecret = args.getParcelable(ARG_MASTER_SECRET);
      }
    }
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.share_fragment, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    initializeListAdapter();
    LoaderManager.getInstance(this).initLoader(0, null, this);
  }

  @Override
  public void onAttach(@NonNull Context context) {
    super.onAttach(context);

    if (context instanceof ConversationSelectedListener) {
      this.listener = (ConversationSelectedListener) context;
    } else {
      throw new ClassCastException(context + " must implement ConversationSelectedListener");
    }
  }

  @Override
  public void onDetach() {
    super.onDetach();
    listener = null;
  }

  @Override
  public void onListItemClick(@NonNull ListView l, @NonNull View v, int position, long id) {
    super.onListItemClick(l, v, position, id);

    if (v instanceof ShareListItem headerView && listener != null) {
      handleCreateConversation(headerView.getThreadId(),
              headerView.getRecipients(),
              headerView.getDistributionType());
    }
  }

  private void initializeListAdapter() {
    final ShareListAdapter adapter = new ShareListAdapter(requireContext(), null, masterSecret);
    setListAdapter(adapter);
    getListView().setRecyclerListener(adapter);
  }

  private void handleCreateConversation(long threadId, Recipients recipients, int distributionType) {
    if (listener != null) {
      listener.onCreateConversation(threadId, recipients, distributionType);
    }
  }

  @NonNull
  @Override
  public Loader<Cursor> onCreateLoader(int id, @Nullable Bundle args) {
    return new ConversationListLoader(requireContext(), masterSecret, null, false);
  }

  @Override
  public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor cursor) {
    final CursorAdapter adapter = getCursorAdapter();
    if (adapter != null) {
      adapter.changeCursor(cursor);
    }
  }

  @Override
  public void onLoaderReset(@NonNull Loader<Cursor> loader) {
    final CursorAdapter adapter = getCursorAdapter();
    if (adapter != null) {
      adapter.changeCursor(null);
    }
  }

  @Nullable
  private CursorAdapter getCursorAdapter() {
    if (getListAdapter() instanceof CursorAdapter adapter) {
      return adapter;
    }
    return null;
  }

  public interface ConversationSelectedListener {
    void onCreateConversation(long threadId, Recipients recipients, int distributionType);
  }
}