/*
 * Copyright (C) 2015 Open Whisper Systems
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

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.jimvixx.smsecure.components.RecyclerViewFastScroller;
import org.jimvixx.smsecure.contacts.ContactSelectionListAdapter;
import org.jimvixx.smsecure.contacts.ContactSelectionListItem;
import org.jimvixx.smsecure.contacts.ContactsCursorLoader;
import org.jimvixx.smsecure.database.CursorRecyclerViewAdapter;
import org.jimvixx.smsecure.util.StickyHeaderDecoration;
import org.jimvixx.smsecure.util.ViewUtil;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Fragment for selecting a one or more contacts from a list.
 *
 * @author Moxie
 */
public class ContactSelectionListFragment extends Fragment implements LoaderManager.LoaderCallbacks<Cursor> {

  @SuppressWarnings("unused")
  private static final String TAG = ContactSelectionListFragment.class.getSimpleName();

  private static final int LOADER_ID = 0;

  private static final String[] CONTACT_PERMISSIONS = new String[]{
          Manifest.permission.READ_CONTACTS,
          Manifest.permission.WRITE_CONTACTS
  };

  private TextView emptyText;

  private Map<Long, String> selectedContacts;
  private OnContactSelectedListener onContactSelectedListener;

  private View showContactsLayout;
  private Button showContactsButton;
  private TextView showContactsDescription;

  private String cursorFilter;

  private RecyclerView recyclerView;
  private RecyclerViewFastScroller fastScroller;

  private ActivityResultLauncher<String[]> permissionsLauncher;

  private boolean multi = false;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    permissionsLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                      final boolean readGranted = Boolean.TRUE.equals(result.get(Manifest.permission.READ_CONTACTS));

                      if (readGranted) {
                        handleContactPermissionGranted();
                      } else {
                        // If READ_CONTACTS was denied permanently, prompt user to open app settings.
                        if (isPermanentlyDenied(Manifest.permission.READ_CONTACTS) ||
                                isPermanentlyDenied(Manifest.permission.WRITE_CONTACTS)) {
                          showPermanentDenialDialog();
                        }
                        initializeNoContactsPermission();
                      }
                    }
            );
  }

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.contact_selection_list_fragment, container, false);

    emptyText = ViewUtil.findById(view, android.R.id.empty);
    recyclerView = ViewUtil.findById(view, R.id.recycler_view);
    fastScroller = ViewUtil.findById(view, R.id.fast_scroller);
    showContactsLayout = view.findViewById(R.id.show_contacts_container);
    showContactsButton = view.findViewById(R.id.show_contacts_button);
    showContactsDescription = view.findViewById(R.id.show_contacts_description);

    recyclerView.setLayoutManager(new LinearLayoutManager(requireActivity()));

    return view;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    initializeCursor();
  }

  @Override
  public void onStart() {
    super.onStart();

    if (hasContactsPermissions()) {
      handleContactPermissionGranted();
    } else {
      initializeNoContactsPermission();
      permissionsLauncher.launch(CONTACT_PERMISSIONS);
    }
  }

  public @Nullable List<String> getSelectedContacts() {
    if (selectedContacts == null) return null;

    return new LinkedList<>(selectedContacts.values());
  }

  public void setMultiSelect(boolean multi) {
    this.multi = multi;
  }

  public void setQueryFilter(@Nullable String filter) {
    this.cursorFilter = filter;
    LoaderManager.getInstance(this).restartLoader(LOADER_ID, null, this);
  }

  private void initializeCursor() {
    ContactSelectionListAdapter adapter = new ContactSelectionListAdapter(
            requireActivity(),
            null,
            new ListClickListener(),
            multi
    );

    selectedContacts = adapter.getSelectedContacts();

    recyclerView.setAdapter(adapter);

    // Sticky header decoration is now generic; diamond operator infers the correct type.
    recyclerView.addItemDecoration(new StickyHeaderDecoration<>(adapter, true, true));
  }

  private void initializeNoContactsPermission() {
    requireActivity().getWindow()
            .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

    emptyText.setVisibility(View.GONE);
    showContactsLayout.setVisibility(View.VISIBLE);
    showContactsDescription.setText(
            R.string.contact_selection_list_fragment__smsecure_needs_access_to_your_contacts_in_order_to_display_them
    );
    showContactsButton.setVisibility(View.VISIBLE);

    showContactsButton.setOnClickListener(v -> {
      // If the user previously denied permanently, show settings dialog; otherwise request again.
      if (isPermanentlyDenied(Manifest.permission.READ_CONTACTS) ||
              isPermanentlyDenied(Manifest.permission.WRITE_CONTACTS)) {
        showPermanentDenialDialog();
      } else {
        permissionsLauncher.launch(CONTACT_PERMISSIONS);
      }
    });
  }

  private boolean hasContactsPermissions() {
    // WRITE_CONTACTS is optional for showing the list, but we keep original behavior: request both.
    // READ_CONTACTS is the real requirement for displaying contacts.
    return requireContext().checkSelfPermission(Manifest.permission.READ_CONTACTS)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
  }

  private boolean isPermanentlyDenied(@NonNull String permission) {
    // If permission is denied and we should NOT show rationale, it's typically a permanent denial.
    if (requireContext().checkSelfPermission(permission)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
      return false;
    }
    return !shouldShowRequestPermissionRationale(permission);
  }

  private void showPermanentDenialDialog() {
    new AlertDialog.Builder(requireContext())
            .setTitle(R.string.Permissions_permission_required)
            .setMessage(getString(
                    R.string.ContactSelectionListFragment_smsecure_requires_the_contacts_permission_in_order_to_display_your_contacts
            ))
            .setPositiveButton(R.string.Continue, (dialog, which) -> openAppSettings())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
  }

  private void openAppSettings() {
    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
    Uri uri = Uri.fromParts("package", requireContext().getPackageName(), null);
    intent.setData(uri);
    startActivity(intent);
  }

  @Override
  public @NonNull Loader<Cursor> onCreateLoader(int id, @Nullable Bundle args) {
    return new ContactsCursorLoader(requireActivity(), true, cursorFilter);
  }

  @Override
  public void onLoadFinished(@NonNull Loader<Cursor> loader, @Nullable Cursor data) {
    showContactsLayout.setVisibility(View.GONE);

    RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
    if (adapter instanceof CursorRecyclerViewAdapter) {
      //noinspection rawtypes
      ((CursorRecyclerViewAdapter) adapter).changeCursor(data);
    }

    emptyText.setText(R.string.contact_selection_group_activity__no_contacts);

    int count = adapter != null ? adapter.getItemCount() : 0;
    if (count > 1) emptyText.setVisibility(View.GONE);

    boolean useFastScroller = count > 20;
    recyclerView.setVerticalScrollBarEnabled(!useFastScroller);

    if (useFastScroller) {
      fastScroller.setVisibility(View.VISIBLE);
      fastScroller.setRecyclerView(recyclerView);
    }
  }

  @Override
  public void onLoaderReset(@NonNull Loader<Cursor> loader) {
    RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
    if (adapter instanceof CursorRecyclerViewAdapter) {
      //noinspection rawtypes
      ((CursorRecyclerViewAdapter) adapter).changeCursor(null);
    }
    fastScroller.setVisibility(View.GONE);
  }

  @SuppressLint("StaticFieldLeak")
  private void handleContactPermissionGranted() {
    LoaderManager.getInstance(this).initLoader(LOADER_ID, null, this);
    showContactsLayout.setVisibility(View.GONE);
    emptyText.setVisibility(View.GONE);
  }

  public void setOnContactSelectedListener(@Nullable OnContactSelectedListener onContactSelectedListener) {
    this.onContactSelectedListener = onContactSelectedListener;
  }

  public interface OnContactSelectedListener {
    void onContactSelected(String number);
  }

  private class ListClickListener implements ContactSelectionListAdapter.ItemClickListener {
    @Override
    public void onItemClick(ContactSelectionListItem contact) {
      if (selectedContacts == null) return;

      if (!multi || !selectedContacts.containsKey(contact.getContactId())) {
        selectedContacts.put(contact.getContactId(), contact.getNumber());
        contact.setChecked(true);
        if (onContactSelectedListener != null) {
          onContactSelectedListener.onContactSelected(contact.getNumber());
        }
      } else {
        selectedContacts.remove(contact.getContactId());
        contact.setChecked(false);
      }
    }
  }
}
