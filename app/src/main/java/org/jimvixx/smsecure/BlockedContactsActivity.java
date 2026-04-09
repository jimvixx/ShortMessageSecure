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

import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.loaders.BlockedContactsLoader;

public class BlockedContactsActivity extends PassphraseRequiredActionBarActivity {

  @Override
  public void onCreate(@Nullable Bundle bundle, @NonNull MasterSecret masterSecret) {
    ActionBar actionBar = getSupportActionBar();
    if (actionBar != null) {
      actionBar.setDisplayHomeAsUpEnabled(true);
      actionBar.setTitle(R.string.BlockedContactsActivity_blocked_contacts);
    }

    initFragment(android.R.id.content, new BlockedContactsFragment(), masterSecret);
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  public static class BlockedContactsFragment extends androidx.fragment.app.Fragment
          implements LoaderManager.LoaderCallbacks<Cursor> {

    private static final int LOADER_ID = 0;

    private BlockedContactsAdapter adapter;
    private RecyclerView recyclerView;
    private View emptyView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle bundle) {
      return inflater.inflate(R.layout.blocked_contacts_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle bundle) {
      super.onViewCreated(view, bundle);

      Toolbar toolbar = view.findViewById(R.id.toolbar);
      AppCompatActivity activity = (AppCompatActivity) requireActivity();
      activity.setSupportActionBar(toolbar);

      ActionBar actionBar = activity.getSupportActionBar();
      if (actionBar != null) {
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setTitle(R.string.BlockedContactsActivity_blocked_contacts);
      }

      emptyView = view.findViewById(R.id.empty);
      recyclerView = view.findViewById(R.id.recycler_view);

      recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

      adapter = new BlockedContactsAdapter(requireContext(), recipients -> {
        Intent intent = new Intent(requireContext(), RecipientPreferenceActivity.class);
        intent.putExtra(RecipientPreferenceActivity.RECIPIENTS_EXTRA, recipients.getIds());
        startActivity(intent);
      });

      recyclerView.setAdapter(adapter);

      LoaderManager.getInstance(this).initLoader(LOADER_ID, null, this);
    }

    @NonNull
    @Override
    public Loader<Cursor> onCreateLoader(int id, @Nullable Bundle args) {
      return new BlockedContactsLoader(requireContext());
    }

    @Override
    public void onLoadFinished(@NonNull Loader<Cursor> loader, @Nullable Cursor data) {
      java.util.ArrayList<BlockedContactsAdapter.Item> items = new java.util.ArrayList<>();

      if (data != null && data.moveToFirst()) {
        do {
          String recipientIds = data.getString(1);
          if (recipientIds != null) {
            items.add(new BlockedContactsAdapter.Item(recipientIds));
          }
        } while (data.moveToNext());
      }

      adapter.submitList(items);
      updateEmpty(items.isEmpty());
    }

    @Override
    public void onLoaderReset(@NonNull Loader<Cursor> loader) {
      adapter.submitList(java.util.Collections.emptyList());
      updateEmpty(true);
    }

    private void updateEmpty(boolean isEmpty) {
      emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
      recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }
  }
}
