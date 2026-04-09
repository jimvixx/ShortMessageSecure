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

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import org.jimvixx.smsecure.components.PushRecipientsPanel;
import org.jimvixx.smsecure.contacts.RecipientsEditor;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.ThreadDatabase;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.util.AppExecutors;
import org.jimvixx.smsecure.util.SelectedRecipientsAdapter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Activity to create SMS broadcast groups.
 */
public class GroupCreateActivity extends PassphraseRequiredActionBarActivity {

  private static final String TAG = GroupCreateActivity.class.getSimpleName();

  private ActivityResultLauncher<Intent> pickContactLauncher;

  private Toolbar toolbar;
  private ListView lv;

  private Set<Recipient> selectedContacts;

  private static <T> ArrayList<T> setToArrayList(Set<T> set) {
    ArrayList<T> arrayList = new ArrayList<>(set.size());
    arrayList.addAll(set);
    return arrayList;
  }

  @Override
  protected void onCreate(Bundle state, @NonNull MasterSecret masterSecret) {
    super.onCreate(state, masterSecret);

    setContentView(R.layout.group_create_activity);

    initializeToolbar();

    selectedContacts = new HashSet<>();
    initializeResources();
    initializeContactPicker();
  }

  private void initializeToolbar() {
    toolbar = findViewById(R.id.toolbar);
    if (toolbar == null) return;

    setSupportActionBar(toolbar);

    ActionBar ab = getSupportActionBar();
    if (ab != null) {
      ab.setDisplayHomeAsUpEnabled(true);
      ab.setDisplayShowHomeEnabled(true);
    }
  }

  private void initializeContactPicker() {
    pickContactLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                      if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                        return;
                      }

                      Intent data = result.getData();
                      List<String> selected = data.getStringArrayListExtra("contacts");
                      if (selected == null) return;

                      for (String contact : selected) {
                        Recipient recipient =
                                RecipientFactory.getRecipientsFromString(
                                        GroupCreateActivity.this,
                                        contact,
                                        false
                                ).getPrimaryRecipient();

                        if (!selectedContacts.contains(recipient)) {
                          addSelectedContact(recipient);
                        }
                      }

                      syncAdapterWithSelectedContacts();
                    }
            );
  }

  @Override
  public void onResume() {
    super.onResume();

    ActionBar ab = getSupportActionBar();
    if (ab != null) {
      ab.setTitle(R.string.GroupCreateActivity_actionbar_title);
    } else if (toolbar != null) {
      toolbar.setTitle(R.string.GroupCreateActivity_actionbar_title);
    }
  }

  private void addSelectedContact(Recipient contact) {
    if (contact == null) return;
    selectedContacts.add(contact);
  }

  private void addAllSelectedContacts(Collection<Recipient> contacts) {
    if (contacts == null) return;
    for (Recipient contact : contacts) addSelectedContact(contact);
  }

  private void removeSelectedContact(Recipient contact) {
    if (contact == null) return;
    selectedContacts.remove(contact);
  }

  private void initializeResources() {
    lv = findViewById(R.id.selected_contacts_list);
    PushRecipientsPanel recipientsPanel = findViewById(R.id.recipients);

    SelectedRecipientsAdapter adapter =
            new SelectedRecipientsAdapter(this, android.R.id.text1, new ArrayList<>());

    adapter.setOnRecipientDeletedListener(this::removeSelectedContact);

    if (lv != null) lv.setAdapter(adapter);

    if (recipientsPanel != null) {
      recipientsPanel.setPanelChangeListener(recipients -> {
        Log.i(TAG, "onRecipientsPanelUpdate received.");
        if (recipients != null) {
          addAllSelectedContacts(recipients.getRecipientsList());
          syncAdapterWithSelectedContacts();
        }
      });
    }

    View button = findViewById(R.id.contacts_button);
    if (button != null) button.setOnClickListener(new AddRecipientButtonListener());

    RecipientsEditor editor = findViewById(R.id.recipients_text);
    if (editor != null) editor.setHint(R.string.recipients_panel__add_member);
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuInflater inflater = this.getMenuInflater();
    menu.clear();

    inflater.inflate(R.menu.selection_finished_menu, menu);

    super.onPrepareOptionsMenu(menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    int id = item.getItemId();

    if (id == android.R.id.home) {
      finish();
      return true;
    } else if (id == R.id.menu_selection_finished) {
      handleGroupCreate();
      return true;
    }

    return super.onOptionsItemSelected(item);
  }

  private void handleGroupCreate() {
    if (selectedContacts == null || selectedContacts.isEmpty()) {
      Log.i(TAG, getString(R.string.GroupCreateActivity_contacts_no_members));
      Toast.makeText(
              getApplicationContext(),
              R.string.GroupCreateActivity_contacts_no_members,
              Toast.LENGTH_SHORT
      ).show();
      return;
    }

    AppExecutors.background().execute(() -> {
      final long threadId = handleCreateBroadcastGroup(selectedContacts);

      AppExecutors.mainHandler().post(() -> {
        if (threadId > -1) {
          Intent intent = new Intent(GroupCreateActivity.this, ConversationActivity.class);
          intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, threadId);
          intent.putExtra(
                  ConversationActivity.DISTRIBUTION_TYPE_EXTRA,
                  ThreadDatabase.DistributionTypes.BROADCAST
          );

          ArrayList<Recipient> selectedContactsList = setToArrayList(selectedContacts);
          intent.putExtra(
                  ConversationActivity.RECIPIENTS_EXTRA,
                  RecipientFactory.getRecipientsFor(
                          GroupCreateActivity.this,
                          selectedContactsList,
                          true
                  ).getIds()
          );

          startActivity(intent);
          finish();
        } else {
          Toast.makeText(
                  getApplicationContext(),
                  R.string.GroupCreateActivity_contacts_mms_exception,
                  Toast.LENGTH_LONG
          ).show();
          finish();
        }
      });
    });
  }

  private void syncAdapterWithSelectedContacts() {
    if (lv == null) return;
    SelectedRecipientsAdapter adapter = (SelectedRecipientsAdapter) lv.getAdapter();
    if (adapter == null) return;

    adapter.clear();
    for (Recipient contact : selectedContacts) {
      if (contact != null)
        adapter.add(new SelectedRecipientsAdapter.RecipientWrapper(contact, true));
    }
    adapter.notifyDataSetChanged();
  }

  private long handleCreateBroadcastGroup(Set<Recipient> members) {
    Recipients recipients = RecipientFactory.getRecipientsFor(this, new LinkedList<>(members), false);
    return DatabaseFactory.getThreadDatabase(this)
            .getThreadIdFor(recipients, ThreadDatabase.DistributionTypes.BROADCAST);
  }

  private class AddRecipientButtonListener implements View.OnClickListener {
    @Override
    public void onClick(View v) {
      Intent intent = new Intent(GroupCreateActivity.this, PushContactSelectionActivity.class);
      pickContactLauncher.launch(intent);
    }
  }
}