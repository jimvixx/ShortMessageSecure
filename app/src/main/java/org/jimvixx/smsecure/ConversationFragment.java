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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.jimvixx.smsecure.ConversationAdapter.HeaderViewHolder;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.MessageDatabase;
import org.jimvixx.smsecure.database.loaders.ConversationLoader;
import org.jimvixx.smsecure.database.model.MessageRecord;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.sms.MessageSender;
import org.jimvixx.smsecure.util.AppExecutors;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.StickyHeaderDecoration;
import org.jimvixx.smsecure.util.ViewUtil;
import org.jimvixx.smsecure.util.task.ProgressDialogTask;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ConversationFragment extends Fragment
        implements LoaderManager.LoaderCallbacks<Cursor> {

  private static final String TAG = ConversationFragment.class.getSimpleName();
  private static final long PARTIAL_CONVERSATION_LIMIT = 500L;

  private final ActionModeCallback actionModeCallback = new ActionModeCallback();
  private final ConversationAdapter.ItemClickListener selectionClickListener =
          new ConversationFragmentItemClickListener();

  private ConversationFragmentListener listener;

  private MasterSecret masterSecret;
  private Recipients recipients;
  private long threadId;
  private long lastSeen;
  private boolean firstLoad;
  private ActionMode actionMode;
  private Locale locale;

  private RecyclerView list;
  private RecyclerView.ItemDecoration lastSeenDecoration;
  private View loadMoreView;
  private View composeDivider;
  private View scrollToBottomButton;
  private TextView scrollDateHeader;

  @Override
  public void onCreate(@Nullable Bundle icicle) {
    super.onCreate(icicle);

    Bundle args = getArguments();
    if (args != null) {
      masterSecret = args.getParcelable("master_secret");
      locale = (Locale) args.getSerializable(PassphraseRequiredActionBarActivity.LOCALE_EXTRA);
    }
  }

  @Override
  public @NonNull View onCreateView(@NonNull LayoutInflater inflater,
                                    @Nullable ViewGroup container,
                                    @Nullable Bundle bundle) {
    final View view = inflater.inflate(R.layout.conversation_fragment, container, false);

    list = ViewUtil.findById(view, android.R.id.list);
    composeDivider = ViewUtil.findById(view, R.id.compose_divider);
    scrollToBottomButton = ViewUtil.findById(view, R.id.scroll_to_bottom_button);
    scrollDateHeader = ViewUtil.findById(view, R.id.scroll_date_header);

    scrollToBottomButton.setOnClickListener(v -> scrollToBottom());

    final LinearLayoutManager layoutManager =
            new LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, true);
    list.setHasFixedSize(false);
    list.setLayoutManager(layoutManager);

    loadMoreView = inflater.inflate(R.layout.load_more_header, container, false);
    loadMoreView.setOnClickListener(v -> {
      Bundle args = new Bundle();
      args.putLong("limit", 0);
      LoaderManager.getInstance(this).restartLoader(0, args, this);
    });

    return view;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    initializeResources();
    initializeListAdapter();
  }

  @Override
  public void onAttach(@NonNull Context context) {
    super.onAttach(context);
    if (context instanceof ConversationFragmentListener) {
      this.listener = (ConversationFragmentListener) context;
    } else {
      throw new ClassCastException("Host activity must implement ConversationFragmentListener");
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    RecyclerView.Adapter<?> a = list.getAdapter();
    if (a != null) a.notifyDataSetChanged();
  }

  public void onNewIntent() {
    if (actionMode != null) actionMode.finish();
    initializeResources();
    initializeListAdapter();

    if (threadId == -1) {
      LoaderManager.getInstance(this).restartLoader(0, Bundle.EMPTY, this);
    }
  }

  private void initializeResources() {
    final Intent i = requireActivity().getIntent();
    final Context ctx = requireContext();

    recipients = RecipientFactory.getRecipientsForIds(
            ctx,
            i.getLongArrayExtra("recipients"),
            true
    );

    threadId = i.getLongExtra("thread_id", -1);
    lastSeen = i.getLongExtra(ConversationActivity.LAST_SEEN_EXTRA, -1);
    firstLoad = true;

    list.clearOnScrollListeners();
    list.addOnScrollListener(new ConversationScrollListener(ctx));
  }

  private void initializeListAdapter() {
    if (recipients != null && threadId != -1) {
      ConversationAdapter<?> adapter =
              new ConversationAdapter<>(requireContext(), masterSecret, locale,
                      selectionClickListener, null, recipients);

      list.setAdapter(adapter);
      list.addItemDecoration(new StickyHeaderDecoration<>(adapter, false, false));

      setLastSeen(lastSeen);

      LoaderManager.getInstance(this).restartLoader(0, Bundle.EMPTY, this);

      RecyclerView.ItemAnimator animator = list.getItemAnimator();
      if (animator != null) animator.setMoveDuration(120);
    }
  }

  private @Nullable ConversationAdapter<?> getListAdapter() {
    RecyclerView.Adapter<?> a = list.getAdapter();
    return (a instanceof ConversationAdapter) ? (ConversationAdapter<?>) a : null;
  }

  private void setCorrectMenuVisibility(@NonNull Menu menu) {
    ConversationAdapter<?> adapter = getListAdapter();
    if (adapter == null) return;

    Set<MessageRecord> messageRecords = adapter.getSelectedItems();

    if (actionMode != null && messageRecords.isEmpty()) {
      actionMode.finish();
      return;
    }

    if (messageRecords.size() > 1) {
      menu.findItem(R.id.menu_context_forward).setVisible(false);
      menu.findItem(R.id.menu_context_share_message).setVisible(false);
      menu.findItem(R.id.menu_context_details).setVisible(false);
      menu.findItem(R.id.menu_context_resend).setVisible(false);
    } else if (messageRecords.size() == 1) {
      MessageRecord messageRecord = messageRecords.iterator().next();

      menu.findItem(R.id.menu_context_resend).setVisible(messageRecord.isFailed());

      menu.findItem(R.id.menu_context_forward).setVisible(true);
      menu.findItem(R.id.menu_context_share_message).setVisible(true);
      menu.findItem(R.id.menu_context_details).setVisible(true);
      menu.findItem(R.id.menu_context_copy).setVisible(true);
    }
  }

  private @NonNull MessageRecord getSelectedMessageRecordOrThrow() {
    ConversationAdapter<?> adapter = getListAdapter();
    if (adapter == null) throw new IllegalStateException("Adapter missing");

    Set<MessageRecord> records = adapter.getSelectedItems();
    if (records.size() == 1) return records.iterator().next();

    throw new AssertionError("Expected exactly one selected item");
  }

  public void reload(@NonNull Recipients recipients, long threadId) {
    this.recipients = recipients;
    if (this.threadId != threadId) {
      this.threadId = threadId;
      initializeListAdapter();
    }
  }

  public void scrollToBottom() {
    list.scrollToPosition(0);
  }

  public void setLastSeen(long lastSeen) {
    this.lastSeen = lastSeen;
    if (lastSeenDecoration != null) {
      list.removeItemDecoration(lastSeenDecoration);
    }

    Context context = requireContext().getApplicationContext();
    if (!SMSecurePreferences.hideUnreadMessageDivider(context)) {
      ConversationAdapter<?> adapter = getListAdapter();
      if (adapter != null) {
        lastSeenDecoration = new ConversationAdapter.LastSeenHeader(adapter, lastSeen);
        list.addItemDecoration(lastSeenDecoration);
      }
    }
  }

  private void handleCopyMessage(@NonNull Set<MessageRecord> messageRecords) {
    List<MessageRecord> messageList = new LinkedList<>(messageRecords);

    // no Comparator.comparingLong (API24); keep API23 safe
    Collections.sort(messageList, (lhs, rhs) -> {
      long a = lhs.getDateReceived();
      long b = rhs.getDateReceived();
      return Long.compare(a, b);
    });

    StringBuilder bodyBuilder = new StringBuilder();
    boolean first = true;

    for (MessageRecord r : messageList) {
      CharSequence bodyCs = r.getDisplayBody();
      String body = bodyCs != null ? bodyCs.toString() : "";
      if (body.isEmpty()) continue;

      if (!first) bodyBuilder.append('\n');
      bodyBuilder.append(body);
      first = false;
    }

    String result = bodyBuilder.toString();
    if (result.isEmpty()) return;

    ClipboardManager clipboard =
            (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard != null) {
      clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.Copy), result));
      Toast.makeText(requireContext(), R.string.Copied_to_clipboard, Toast.LENGTH_SHORT).show();
    }
  }

  private void handleDeleteMessages(@NonNull Set<MessageRecord> messageRecords) {
    int messagesCount = messageRecords.size();

    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setTitle(messagesCount > 1 ?
            R.string.ConversationFragment_delete_selected_messages :
            R.string.ConversationFragment_delete_selected_message);

    builder.setMessage(getResources().getQuantityString(
            R.plurals.ConversationFragment_this_will_permanently_delete_all_n_selected_messages,
            messagesCount,
            messagesCount));

    builder.setCancelable(true);

    builder.setPositiveButton(R.string.Yes, (dialog, which) -> {
      final Context ctx = requireContext();

      // run background with modal progress dialog
      ProgressDialogTask.run(
              ctx,
              R.string.Deleting,
              R.string.ConversationFragment_deleting_messages,
              () -> {
                boolean anyThreadDeleted = false;

                for (MessageRecord r : messageRecords) {
                  boolean threadDeleted;
                  threadDeleted = DatabaseFactory.getSmsDatabase(ctx).deleteMessage(r.getId());
                  if (threadDeleted) anyThreadDeleted = true;
                }

                return anyThreadDeleted;
              },
              anyThreadDeleted -> {
                if (Boolean.TRUE.equals(anyThreadDeleted)) {
                  threadId = -1;
                  if (listener != null) listener.setThreadId(threadId);
                }

                // Loader will refresh cursor; just exit CAB
                if (actionMode != null) actionMode.finish();
              },
              error -> Log.w(TAG, "Delete failed", error)
      );
    });

    builder.setNegativeButton(R.string.No, null);
    builder.show();
  }

  private void handleDisplayDetails(@NonNull MessageRecord message) {
    Intent intent = new Intent(requireContext(), MessageDetailsActivity.class);
    intent.putExtra(MessageDetailsActivity.MASTER_SECRET_EXTRA, masterSecret);
    intent.putExtra(MessageDetailsActivity.MESSAGE_ID_EXTRA, message.getId());
    intent.putExtra(MessageDetailsActivity.THREAD_ID_EXTRA, threadId);
    intent.putExtra(MessageDetailsActivity.TYPE_EXTRA,
            MessageDatabase.SMS_TRANSPORT);
    intent.putExtra(MessageDetailsActivity.RECIPIENTS_IDS_EXTRA, recipients.getIds());
    startActivity(intent);
  }

  private void handleForwardMessage(@NonNull MessageRecord message) {
    Intent composeIntent = new Intent(requireContext(), ShareActivity.class);
    CharSequence bodyCs = message.getDisplayBody();
    if (bodyCs != null) composeIntent.putExtra(Intent.EXTRA_TEXT, bodyCs.toString());

    startActivity(composeIntent);
  }

  private void handleShareMessage(@NonNull MessageRecord message) {
    CharSequence bodyCs = message.getDisplayBody();
    String body = bodyCs != null ? bodyCs.toString() : "";

    if (body.isEmpty()) {
      Toast.makeText(requireContext(), R.string.ConversationFragment_empty_message, Toast.LENGTH_SHORT).show();
      return;
    }

    Intent shareIntent = new Intent(Intent.ACTION_SEND);
    shareIntent.setType("text/plain");
    shareIntent.putExtra(Intent.EXTRA_TEXT, body);

    startActivity(Intent.createChooser(
            shareIntent,
            getString(R.string.conversation_context__menu_share_message)
    ));
  }

  private void handleResendMessage(@NonNull MessageRecord message) {
    final Context context = requireContext().getApplicationContext();
    AppExecutors.background().execute(() -> MessageSender.resend(context, masterSecret, message));
  }

  // -------------------------
  // Loader callbacks
  // -------------------------

  @Override
  public @NonNull Loader<Cursor> onCreateLoader(int id, @Nullable Bundle args) {
    long limit = (args != null) ? args.getLong("limit", PARTIAL_CONVERSATION_LIMIT) : PARTIAL_CONVERSATION_LIMIT;
    return new ConversationLoader(requireContext(), threadId, limit, lastSeen);
  }

  @Override
  public void onLoadFinished(@NonNull Loader<Cursor> cursorLoader, Cursor cursor) {
    ConversationLoader loader = (ConversationLoader) cursorLoader;

    ConversationAdapter<?> adapter = getListAdapter();
    if (adapter == null) return;

    if (cursor.getCount() >= PARTIAL_CONVERSATION_LIMIT && loader.hasLimit()) {
      adapter.setFooterView(loadMoreView);
    } else {
      adapter.setFooterView(null);
    }

    if (lastSeen == -1) {
      setLastSeen(loader.getLastSeen());
    }

    adapter.changeCursor(cursor);

    int lastSeenPosition = adapter.findLastSeenPosition(lastSeen);

    if (firstLoad) {
      scrollToLastSeenPosition(lastSeenPosition);
      firstLoad = false;
    }

    if (lastSeenPosition <= 0) {
      setLastSeen(0);
    }
  }

  @Override
  public void onLoaderReset(@NonNull Loader<Cursor> loader) {
    ConversationAdapter<?> adapter = getListAdapter();
    if (adapter != null) adapter.changeCursor(null);
  }

  private void scrollToLastSeenPosition(final int lastSeenPosition) {
    if (lastSeenPosition <= 0) return;

    list.post(() -> {
      RecyclerView.LayoutManager lm = list.getLayoutManager();
      if (!(lm instanceof LinearLayoutManager)) return;
      ((LinearLayoutManager) lm).scrollToPositionWithOffset(lastSeenPosition, list.getHeight());
    });
  }

  // -------------------------
  // Listener
  // -------------------------

  public interface ConversationFragmentListener {
    void setThreadId(long threadId);
  }

  // -------------------------
  // Scroll listener (Signal-like)
  // -------------------------

  private static class ConversationDateHeader extends HeaderViewHolder {

    private final Animation animateIn;
    private final Animation animateOut;

    private boolean pendingHide = false;

    private ConversationDateHeader(@NonNull Context context, @NonNull TextView textView) {
      super(textView);
      this.animateIn = AnimationUtils.loadAnimation(context, R.anim.slide_from_top);
      this.animateOut = AnimationUtils.loadAnimation(context, R.anim.slide_to_top);

      this.animateIn.setDuration(100);
      this.animateOut.setDuration(100);
    }

    public void show() {
      if (pendingHide) {
        pendingHide = false;
      } else {
        ViewUtil.animateIn(getTextView(), animateIn);
      }
    }

    public void hide() {
      pendingHide = true;

      getTextView().postDelayed(() -> {
        if (pendingHide) {
          pendingHide = false;
          ViewUtil.animateOut(getTextView(), animateOut, View.GONE);
        }
      }, 400);
    }
  }

  // -------------------------
  // Selection click listener (Signal-like: notifyItemChanged only)
  // -------------------------

  private class ConversationScrollListener extends RecyclerView.OnScrollListener {

    private final Animation scrollButtonInAnimation;
    private final Animation scrollButtonOutAnimation;
    private final ConversationDateHeader conversationDateHeader;

    private boolean wasAtBottom = true;
    private boolean wasAtZoomScrollHeight = false;
    private int lastPositionId = -1;

    ConversationScrollListener(@NonNull Context context) {
      this.scrollButtonInAnimation = AnimationUtils.loadAnimation(context, R.anim.fade_scale_in);
      this.scrollButtonOutAnimation = AnimationUtils.loadAnimation(context, R.anim.fade_scale_out);
      this.conversationDateHeader = new ConversationDateHeader(context, scrollDateHeader);

      this.scrollButtonInAnimation.setDuration(100);
      this.scrollButtonOutAnimation.setDuration(50);
    }

    @Override
    public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
      boolean currentlyAtBottom = isAtBottom();
      boolean currentlyAtZoomScrollHeight = isAtZoomScrollHeight();
      int positionId = getHeaderPositionId();

      if (currentlyAtBottom && !wasAtBottom) {
        ViewUtil.fadeOut(composeDivider, 50, View.INVISIBLE);
        ViewUtil.animateOut(scrollToBottomButton, scrollButtonOutAnimation, View.INVISIBLE);
      } else if (!currentlyAtBottom && wasAtBottom) {
        ViewUtil.fadeIn(composeDivider, 500);
      }

      if (currentlyAtZoomScrollHeight && !wasAtZoomScrollHeight) {
        ViewUtil.animateIn(scrollToBottomButton, scrollButtonInAnimation);
      }

      if (positionId != lastPositionId) {
        bindScrollHeader(conversationDateHeader, positionId);
      }

      wasAtBottom = currentlyAtBottom;
      wasAtZoomScrollHeight = currentlyAtZoomScrollHeight;
      lastPositionId = positionId;
    }

    @Override
    public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
      if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
        conversationDateHeader.show();
      } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
        conversationDateHeader.hide();
      }
    }

    private boolean isAtBottom() {
      if (list.getChildCount() == 0) return true;

      RecyclerView.LayoutManager lm = list.getLayoutManager();
      if (!(lm instanceof LinearLayoutManager)) return true;

      View bottomView = list.getChildAt(0);
      int firstVisible = ((LinearLayoutManager) lm).findFirstVisibleItemPosition();
      boolean atBottom = (firstVisible == 0);

      return atBottom && bottomView.getBottom() <= list.getHeight();
    }

    private boolean isAtZoomScrollHeight() {
      RecyclerView.LayoutManager lm = list.getLayoutManager();
      if (!(lm instanceof LinearLayoutManager)) return false;
      return ((LinearLayoutManager) lm).findFirstCompletelyVisibleItemPosition() > 4;
    }

    private int getHeaderPositionId() {
      RecyclerView.LayoutManager lm = list.getLayoutManager();
      if (!(lm instanceof LinearLayoutManager)) return -1;
      return ((LinearLayoutManager) lm).findLastVisibleItemPosition();
    }

    private void bindScrollHeader(@NonNull HeaderViewHolder headerViewHolder, int positionId) {
      ConversationAdapter<?> adapter = getListAdapter();
      if (adapter == null) return;

      if (positionId < 0 || positionId >= adapter.getItemCount()) return;

      if (adapter.getHeaderId(positionId) != -1) {
        adapter.onBindHeaderViewHolder(headerViewHolder, positionId);
      }
    }
  }

  private class ConversationFragmentItemClickListener
          implements ConversationAdapter.ItemClickListener {

    @Override
    public void onItemClick(@NonNull ConversationItem item, int adapterPosition) {
      if (actionMode == null) return;

      final ConversationAdapter<?> adapter = getListAdapter();
      if (adapter == null) return;

      final MessageRecord record = item.getMessageRecord();
      if (record == null) return;

      adapter.toggleSelection(record, adapterPosition);
      adapter.notifyItemChanged(adapterPosition);

      setCorrectMenuVisibility(actionMode.getMenu());
    }

    @Override
    public void onItemLongClick(@NonNull ConversationItem item, int adapterPosition) {
      final ConversationAdapter<?> adapter = getListAdapter();
      if (adapter == null) return;

      final MessageRecord record = item.getMessageRecord();
      if (record == null) return;

      if (actionMode == null) {
        actionMode = ((AppCompatActivity) requireActivity())
                .startSupportActionMode(actionModeCallback);
      }

      adapter.toggleSelection(record, adapterPosition);
      adapter.notifyItemChanged(adapterPosition);

      if (actionMode != null) {
        setCorrectMenuVisibility(actionMode.getMenu());
      }
    }
  }

  // -------------------------
  // Floating date header (needs access to HeaderViewHolder.getTextView())
  // -------------------------

  // -------------------------
  // CAB
  // -------------------------
  private class ActionModeCallback implements ActionMode.Callback {

//    private int statusBarColor;

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
      MenuInflater inflater = mode.getMenuInflater();
      inflater.inflate(R.menu.conversation_cab, menu);

      mode.setTitle(R.string.conversation_fragment_cab__batch_selection_mode);
      mode.setSubtitle(null);

      setCorrectMenuVisibility(menu);
      return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
      return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
      ConversationAdapter<?> adapter = getListAdapter();
      if (adapter != null) {
        int[] changed = adapter.clearSelectionAndGetPositions();
        for (int p : changed) {
          if (p != RecyclerView.NO_POSITION) adapter.notifyItemChanged(p);
        }
      }

      actionMode = null;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
      int id = item.getItemId();

      if (id == R.id.menu_context_copy) {
        ConversationAdapter<?> adapter = getListAdapter();
        if (adapter != null) handleCopyMessage(adapter.getSelectedItems());
        mode.finish();
        return true;
      }

      if (id == R.id.menu_context_delete_message) {
        ConversationAdapter<?> adapter = getListAdapter();
        if (adapter != null) handleDeleteMessages(adapter.getSelectedItems());
        mode.finish();
        return true;
      }

      if (id == R.id.menu_context_details) {
        handleDisplayDetails(getSelectedMessageRecordOrThrow());
        mode.finish();
        return true;
      }

      if (id == R.id.menu_context_forward) {
        handleForwardMessage(getSelectedMessageRecordOrThrow());
        mode.finish();
        return true;
      }

      if (id == R.id.menu_context_share_message) {
        handleShareMessage(getSelectedMessageRecordOrThrow());
        mode.finish();
        return true;
      }

      if (id == R.id.menu_context_resend) {
        handleResendMessage(getSelectedMessageRecordOrThrow());
        mode.finish();
        return true;
      }

      return false;
    }
  }
}
