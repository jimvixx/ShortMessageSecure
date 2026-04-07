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

import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import org.jimvixx.smsecure.components.AnimatingToggle;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.permissions.Permissions;
import org.jimvixx.smsecure.util.ServiceUtil;

/**
 * Base activity container for selecting a list of contacts.
 */
public abstract class ContactSelectionActivity extends PassphraseRequiredActionBarActivity
        implements ContactSelectionListFragment.OnContactSelectedListener {
  @SuppressWarnings("unused")
  private static final String TAG = ContactSelectionActivity.class.getSimpleName();
  protected ContactSelectionListFragment contactsFragment;
  protected ImageView action;
  private EditText searchText;
  private AnimatingToggle toggle;
  private ImageView keyboardToggle;
  private ImageView dialpadToggle;
  private ImageView clearToggle;

  @Override
  protected void onCreate(Bundle icicle, @NonNull MasterSecret masterSecret) {
    setContentView(R.layout.contact_selection_activity);

    initializeToolbar();
    initializeResources();
    initializeSearch();
  }

  @Override
  public void onResume() {
    super.onResume();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode,
                                         @NonNull String[] permissions,
                                         @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  @Override
  public boolean onSupportNavigateUp() {
    getOnBackPressedDispatcher().onBackPressed();
    return true;
  }

  private void initializeToolbar() {
    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);

    ActionBar ab = getSupportActionBar();
    if (ab != null) {
      ab.setDisplayHomeAsUpEnabled(true);
      ab.setDisplayShowTitleEnabled(false);
      getSupportActionBar().setIcon(null);
      getSupportActionBar().setLogo(null);
    }
  }

  private void initializeResources() {
    this.action = findViewById(R.id.action_icon);
    this.searchText = findViewById(R.id.search_view);
    this.toggle = findViewById(R.id.button_toggle);
    this.keyboardToggle = findViewById(R.id.search_keyboard);
    this.dialpadToggle = findViewById(R.id.search_dialpad);
    this.clearToggle = findViewById(R.id.search_clear);

    contactsFragment = (ContactSelectionListFragment)
            getSupportFragmentManager().findFragmentById(R.id.contact_selection_list_fragment);

    if (!isActionIconEnabled()) {
      action.setVisibility(View.INVISIBLE);
    }

    if (contactsFragment == null) {
      throw new IllegalStateException("ContactSelectionListFragment not found in layout. " +
              "Expected <fragment> with id contact_selection_list_fragment.");
    }

    contactsFragment.setOnContactSelectedListener(this);

    this.keyboardToggle.setOnClickListener(v -> {
      searchText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
      ServiceUtil.getInputMethodManager(ContactSelectionActivity.this).showSoftInput(searchText, 0);
      toggle.display(dialpadToggle);
    });

    this.dialpadToggle.setOnClickListener(v -> {
      searchText.setInputType(InputType.TYPE_CLASS_PHONE);
      ServiceUtil.getInputMethodManager(ContactSelectionActivity.this).showSoftInput(searchText, 0);
      toggle.display(keyboardToggle);
    });

    this.clearToggle.setOnClickListener(v -> {
      searchText.setText("");

      if (SearchUtil.isTextInput(searchText)) toggle.display(dialpadToggle);
      else toggle.display(keyboardToggle);
    });

  }

  private void initializeSearch() {
    this.searchText.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {

      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {

      }

      @Override
      public void afterTextChanged(Editable s) {
        if (!SearchUtil.isEmpty(searchText)) toggle.display(clearToggle);
        else if (SearchUtil.isTextInput(searchText)) toggle.display(dialpadToggle);
        else if (SearchUtil.isPhoneInput(searchText)) toggle.display(keyboardToggle);

        contactsFragment.setQueryFilter(searchText.getText().toString());
      }
    });
  }

  @Override
  public void onContactSelected(String number) {
  }

  protected boolean isActionIconEnabled() {
    return true;
  }

  private static class SearchUtil {

    public static boolean isTextInput(EditText editText) {
      return (editText.getInputType() & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT;
    }

    public static boolean isPhoneInput(EditText editText) {
      return (editText.getInputType() & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_PHONE;
    }

    public static boolean isEmpty(EditText editText) {
      return editText.getText().length() <= 0;
    }
  }
}
