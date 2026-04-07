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

import static org.jimvixx.smsecure.util.ThemeUtil.resolveThemeColor;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.core.os.BundleCompat;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import org.jimvixx.smsecure.ConversationListAdapter.ItemClickListener;
import org.jimvixx.smsecure.components.reminder.DefaultSmsReminder;
import org.jimvixx.smsecure.components.reminder.DeliveryReportsReminder;
import org.jimvixx.smsecure.components.reminder.Reminder;
import org.jimvixx.smsecure.components.reminder.ReminderView;
import org.jimvixx.smsecure.components.reminder.StoreRatingReminder;
import org.jimvixx.smsecure.components.reminder.SystemSmsImportReminder;
import org.jimvixx.smsecure.crypto.MasterCipher;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.SessionUtil;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.DraftDatabase;
import org.jimvixx.smsecure.database.ThreadDatabase;
import org.jimvixx.smsecure.database.loaders.ConversationListLoader;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.notifications.MessageNotifier;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.sms.MessageSender;
import org.jimvixx.smsecure.sms.OutgoingEncryptedMessage;
import org.jimvixx.smsecure.sms.OutgoingTextMessage;
import org.jimvixx.smsecure.util.NotificationIconUtil;
import org.jimvixx.smsecure.util.Util;
import org.jimvixx.smsecure.util.ViewUtil;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class ConversationListFragment extends Fragment
        implements LoaderManager.LoaderCallbacks<Cursor>, ActionMode.Callback, ItemClickListener {

  public static final String ARCHIVE = "archive";
  private static final String TAG = ConversationListFragment.class.getSimpleName();
  private final Executor backgroundExecutor = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final Paint swipePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  // Swipe label rendering (Archive/Unarchive).
  private final Paint swipeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private MasterSecret masterSecret;
  private ActionMode actionMode;
  private RecyclerView list;
  private ReminderView reminderView;
  private FloatingActionButton fab;
  private String queryFilter = "";
  private boolean archive;
  // Launcher for the "set default SMS app" flow (RoleManager / ACTION_CHANGE_DEFAULT).
  private ActivityResultLauncher<Intent> defaultSmsLauncher;
  // Used to avoid showing stale reminder after async finishes.
  private int reminderRequestSerial = 0;
  // Swipe rendering caches (avoid allocations/decodes on every frame).
  @Nullable
  private Bitmap swipeIconArchive;   // ic_package_down
  @Nullable
  private Bitmap swipeIconUnarchive; // ic_package_up
  private int swipeBackgroundColor = 0;
  private int swipeTextIconGapPx = 0;

  private static void sendTextDraft(@NonNull Context context,
                                    @NonNull MasterSecret masterSecret,
                                    @NonNull Recipients recipients,
                                    boolean isSecureDestination,
                                    @NonNull DraftDatabase.Draft draft,
                                    long threadId) {
    OutgoingTextMessage message =
            isSecureDestination
                    ? new OutgoingEncryptedMessage(recipients, draft.getValue(), -1)
                    : new OutgoingTextMessage(recipients, draft.getValue(), -1);

    MessageSender.send(context, masterSecret, message, threadId, false);
  }

  private static AlertDialog showBlockingProgressDialog(@NonNull Context context,
                                                        @NonNull CharSequence title,
                                                        @NonNull CharSequence message) {
    ProgressBar progressBar = new ProgressBar(context);
    progressBar.setIndeterminate(true);

    return new AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setView(progressBar)
            .setCancelable(false)
            .show();
  }

  @Override
  public void onCreate(@Nullable Bundle icicle) {
    super.onCreate(icicle);

    // Arguments are required for this fragment.
    final Bundle args = requireArguments();

    // BundleCompat avoids API 33+ typed getParcelable boilerplate and fixes nullability warnings.
    masterSecret = BundleCompat.getParcelable(args, "master_secret", MasterSecret.class);

    archive = args.getBoolean(ARCHIVE, false);

    defaultSmsLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                      initializeReminders();
                      // Adapter may show different UI depending on default-SMS status.
                      RecyclerView.Adapter<?> a = (list != null) ? list.getAdapter() : null;
                      if (a != null) a.notifyItemRangeChanged(0, a.getItemCount());
                    });
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle bundle) {
    final View view = inflater.inflate(R.layout.conversation_list_fragment, container, false);

    reminderView = ViewUtil.findById(view, R.id.reminder);
    list = ViewUtil.findById(view, R.id.list);
    fab = ViewUtil.findById(view, R.id.fab);

    fab.setVisibility(archive ? View.GONE : View.VISIBLE);

    list.setHasFixedSize(true);
    list.setLayoutManager(new LinearLayoutManager(requireContext()));

    new ItemTouchHelper(new ArchiveListenerCallback()).attachToRecyclerView(list);

    return view;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    setupMenu();

    fab.setOnClickListener(v ->
            startActivity(new Intent(requireActivity(), NewConversationActivity.class)));

    initializeListAdapter();

    // Prepare swipe icons and paint once; refresh in onResume for theme changes.
    prepareSwipeIcons();
  }

  @Override
  public void onResume() {
    super.onResume();
    initializeReminders();

    // If theme changed while paused, swipe icons and colors may need refresh.
    prepareSwipeIcons();

    RecyclerView.Adapter<?> a = (list != null) ? list.getAdapter() : null;
    if (a != null) a.notifyItemRangeChanged(0, a.getItemCount());
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    // Release view-related references.
    list = null;
    reminderView = null;
    fab = null;

    // Bitmaps are cached inside NotificationIconUtil, but we keep only references here.
    swipeIconArchive = null;
    swipeIconUnarchive = null;
  }

  private void setupMenu() {
    MenuHost host = requireActivity();

    host.addMenuProvider(new MenuProvider() {
      @Override
      public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
      }

      @Override
      public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
        return false;
      }
    }, getViewLifecycleOwner());
  }

  public ConversationListAdapter getListAdapter() {
    RecyclerView.Adapter<?> a = (list != null) ? list.getAdapter() : null;
    return (a instanceof ConversationListAdapter) ? (ConversationListAdapter) a : null;
  }

  public void setQueryFilter(String query) {
    this.queryFilter = query;
    LoaderManager.getInstance(this).restartLoader(0, null, this);
  }

  public void resetQueryFilter() {
    if (!TextUtils.isEmpty(this.queryFilter)) setQueryFilter("");
  }

  @NonNull
  private Context requireAppContext() {
    return requireContext().getApplicationContext();
  }

  private void prepareSwipeIcons() {
    final Context ctx = requireContext();

    final int sizeDp = ViewUtil.pxToDp(
            getResources(),
            getResources().getDimension(R.dimen.conversation_list_fragment__swipe_icon_size)
    );

    // Cache colors to avoid resolveThemeColor() in the hot path (onChildDraw).
    swipeBackgroundColor = resolveThemeColor(ctx, R.attr.appColorSwipeBackground);
    final int controlColor = resolveThemeColor(ctx, R.attr.appColorToolbarControl);

    swipeIconArchive = NotificationIconUtil.getLargeIcon(
            ctx, R.drawable.ic_package_down, sizeDp, controlColor);

    swipeIconUnarchive = NotificationIconUtil.getLargeIcon(
            ctx, R.drawable.ic_package_up, sizeDp, controlColor);

    // Configure swipe label paint (Archive / Unarchive).
    swipeTextPaint.setTextSize(getResources().getDimension(R.dimen.conversation_list_fragment__swipe_text_size));
    swipeTextPaint.setColor(controlColor);
    swipeTextPaint.setFakeBoldText(true);

    // Gap between icon and text.
    swipeTextIconGapPx = ViewUtil.dpToPx(getResources(), getResources().getDimension(R.dimen.conversation_list_fragment__swipe_text_start_padding));
  }

  private void initializeReminders() {
    if (reminderView == null) return;

    reminderView.hide();

    final int requestId = ++reminderRequestSerial;

    final Context activity = getActivity();
    if (activity == null) return;

    final Context appContext = activity.getApplicationContext();

    backgroundExecutor.execute(() -> {
      final ReminderType type;
      if (DefaultSmsReminder.isEligible(appContext)) {
        type = ReminderType.DEFAULT_SMS;
      } else if (Util.isDefaultSmsProvider(appContext) && SystemSmsImportReminder.isEligible(appContext)) {
        type = ReminderType.SYSTEM_SMS_IMPORT;
      } else if (DeliveryReportsReminder.isEligible(appContext)) {
        type = ReminderType.DELIVERY_REPORTS;
      } else if (StoreRatingReminder.isEligible(appContext)) {
        type = ReminderType.STORE_RATING;
      } else {
        type = ReminderType.NONE;
      }

      mainHandler.post(() -> {
        if (!isAdded() || isRemoving() || isDetached() || requestId != reminderRequestSerial) {
          return;
        }

        final Context context = getActivity();
        if (context == null) return;

        final Reminder reminder = switch (type) {
          case DEFAULT_SMS ->
                  new DefaultSmsReminder(context, intent -> defaultSmsLauncher.launch(intent));
          case SYSTEM_SMS_IMPORT -> new SystemSmsImportReminder(context, masterSecret);
          case DELIVERY_REPORTS -> new DeliveryReportsReminder(context);
          case STORE_RATING -> new StoreRatingReminder(context);
          default -> null;
        };

        if (reminder != null) reminderView.showReminder(reminder);
        else reminderView.hide();
      });
    });
  }

  private void initializeListAdapter() {
    if (list == null) return;
    list.setAdapter(new ConversationListAdapter(requireContext(), masterSecret, null, this));
    LoaderManager.getInstance(this).restartLoader(0, null, this);
  }

  private void handleArchiveSelected() {
    final ConversationListAdapter adapter = getListAdapter();
    final View rootView = getView();
    if (adapter == null || rootView == null) return;

    final Set<Long> selectedConversations = new HashSet<>(adapter.getBatchSelections());
    if (selectedConversations.isEmpty()) return;

    final Context appContext = requireAppContext();
    final boolean archiveLocal = this.archive;

    final int pluralResId =
            archiveLocal
                    ? R.plurals.ConversationListFragment_moved_conversations_to_inbox
                    : R.plurals.ConversationListFragment_conversations_archived;

    final int count = selectedConversations.size();
    final String title =
            getResources().getQuantityString(pluralResId, count, count);

    // Execute action immediately, allow undo.
    backgroundExecutor.execute(() -> {
      ThreadDatabase db = DatabaseFactory.getThreadDatabase(appContext);
      for (long threadId : selectedConversations) {
        if (!archiveLocal) db.archiveConversation(threadId);
        else db.unarchiveConversation(threadId);
      }
    });

    Snackbar snackbar =
            Snackbar.make(rootView, title, Snackbar.LENGTH_LONG)
                    .setAction(R.string.ConversationListFragment_undo, v -> backgroundExecutor.execute(() -> {
                      ThreadDatabase db = DatabaseFactory.getThreadDatabase(appContext);
                      for (long threadId : selectedConversations) {
                        if (!archiveLocal) db.unarchiveConversation(threadId);
                        else db.archiveConversation(threadId);
                      }
                    }));

    snackbar.show();

    if (actionMode != null) {
      actionMode.finish();
      actionMode = null;
    }
  }

  private void handleDeleteSelected() {
    final ConversationListAdapter adapter = getListAdapter();
    if (adapter == null) return;

    final Set<Long> selected = new HashSet<>(adapter.getBatchSelections());
    final int conversationsCount = selected.size();
    if (conversationsCount == 0) return;

    final Context ctx = requireContext();

    AlertDialog.Builder alert = new AlertDialog.Builder(ctx);
    alert.setIconAttribute(R.attr.dialog_alert_icon);
    alert.setTitle(conversationsCount > 1 ?
            R.string.ConversationListFragment_delete_selected_conversations :
            R.string.ConversationListFragment_delete_selected_conversation);
    alert.setMessage(ctx.getResources().getQuantityString(
            R.plurals.ConversationListFragment_this_will_permanently_delete_all_n_selected_conversations,
            conversationsCount, conversationsCount));
    alert.setCancelable(true);

    alert.setPositiveButton(R.string.Delete, (dialog, which) -> {
      final AlertDialog progress = showBlockingProgressDialog(
              ctx,
              ctx.getString(R.string.Deleting),
              ctx.getString(R.string.ConversationListFragment_deleting_selected_conversations)
      );

      backgroundExecutor.execute(() -> {
        try {
          DatabaseFactory.getThreadDatabase(ctx).deleteConversations(selected);
          if (masterSecret != null) {
            MessageNotifier.updateNotification(ctx, masterSecret);
          }
        } finally {
          mainHandler.post(() -> {
            progress.dismiss();
            if (actionMode != null) {
              actionMode.finish();
              actionMode = null;
            }
          });
        }
      });
    });

    alert.setNegativeButton(android.R.string.cancel, null);
    alert.show();
  }

  private void handleSelectAll() {
    ConversationListAdapter adapter = getListAdapter();
    if (adapter == null || actionMode == null) return;

    adapter.selectAllThreads();

    actionMode.setSubtitle(getString(
            R.string.conversation_fragment_cab__batch_selection_amount,
            adapter.getBatchSelections().size()));
  }

  private void handleCreateConversation(long threadId,
                                        Recipients recipients,
                                        int distributionType,
                                        long lastSeen) {
    if (!(getActivity() instanceof ConversationSelectedListener listener)) return;
    listener.onCreateConversation(threadId, recipients, distributionType, lastSeen);
  }

  private void handleSendDrafts() {
    final ConversationListAdapter adapter = getListAdapter();
    if (adapter == null) return;

    final Set<Long> selectedConversations = new HashSet<>(adapter.getBatchSelections());
    if (selectedConversations.isEmpty() || masterSecret == null) return;

    final Context ctx = requireContext();
    final MasterCipher masterCipher = new MasterCipher(masterSecret);

    AlertDialog.Builder alert = new AlertDialog.Builder(ctx);
    alert.setIconAttribute(R.attr.dialog_alert_icon);
    alert.setTitle(getString(R.string.ConversationListFragment_send_drafts));
    alert.setMessage(getString(R.string.ConversationListFragment_this_will_send_drafts_of_selected_conversations));
    alert.setCancelable(true);

    alert.setPositiveButton(R.string.Send, (dialog, which) -> {
      final AlertDialog progress = showBlockingProgressDialog(
              ctx,
              ctx.getString(R.string.ConversationListFragment_sending),
              ctx.getString(R.string.ConversationListFragment_sending_selected_drafts)
      );

      backgroundExecutor.execute(() -> {
        try {
          DraftDatabase draftDatabase = DatabaseFactory.getDraftDatabase(ctx);

          for (long threadId : selectedConversations) {
            List<DraftDatabase.Draft> drafts = draftDatabase.getDrafts(masterCipher, threadId);
            Recipients recipients = adapter.getRecipientsFromThreadId(threadId);

            if (recipients != null) {
              int subscriptionId = SubscriptionManagerCompat.getDefaultMessagingSubscriptionId().or(-1);
              boolean isSingleConversation = recipients.isSingleRecipient() && !recipients.isGroupRecipient();
              boolean isSecureDestination = isSingleConversation
                      && SessionUtil.hasSession(ctx, masterSecret, recipients.getPrimaryRecipient().getNumber(), subscriptionId);

              Log.w(TAG, "Number of drafts: " + drafts.size());

              for (DraftDatabase.Draft draft : drafts) {
                sendTextDraft(ctx, masterSecret, recipients, isSecureDestination, draft, threadId);
              }
            } else {
              Log.w(TAG, "Null recipients when sending drafts.");
            }

            draftDatabase.clearDrafts(threadId);
          }
        } finally {
          mainHandler.post(() -> {
            progress.dismiss();
            if (actionMode != null) {
              actionMode.finish();
              actionMode = null;
            }
          });
        }
      });
    });

    alert.setNegativeButton(android.R.string.cancel, null);
    alert.show();
  }

  @NonNull
  @Override
  public Loader<Cursor> onCreateLoader(int id, @Nullable Bundle args) {
    return new ConversationListLoader(requireContext(), masterSecret, queryFilter, archive);
  }

  @Override
  public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor cursor) {
    ConversationListAdapter adapter = getListAdapter();
    if (adapter != null) adapter.changeCursor(cursor);
  }

  @Override
  public void onLoaderReset(@NonNull Loader<Cursor> loader) {
    ConversationListAdapter adapter = getListAdapter();
    if (adapter != null) adapter.changeCursor(null);
  }

  @Override
  public void onItemClick(@NonNull ConversationListItem item, int position) {
    if (actionMode == null) {
      handleCreateConversation(item.getThreadId(), item.getRecipients(),
              item.getDistributionType(), item.getLastSeen());
      return;
    }

    ConversationListAdapter adapter = getListAdapter();
    if (adapter == null) return;

    adapter.toggleThreadInBatchSet(item.getThreadId());
    adapter.populateRecipients(item.getThreadId(), item.getRecipients());

    if (adapter.getBatchSelections().isEmpty()) {
      actionMode.finish();
      actionMode = null;
    } else {
      actionMode.setSubtitle(getString(
              R.string.conversation_fragment_cab__batch_selection_amount,
              adapter.getBatchSelections().size()));
    }

    if (position >= 0) {
      adapter.notifyItemChanged(position);
    } else {
      int count = adapter.getItemCount();
      if (count > 0) adapter.notifyItemRangeChanged(0, count);
    }
  }

  // Called when a list row is long-clicked (starts selection mode).
  @Override
  public void onItemLongClick(@NonNull ConversationListItem item, int position) {
    if (!(requireActivity() instanceof AppCompatActivity activity)) return;

    if (actionMode == null) {
      actionMode = activity.startSupportActionMode(this);
    }

    ConversationListAdapter adapter = getListAdapter();
    if (adapter == null) return;

    adapter.initializeBatchMode(true);
    adapter.toggleThreadInBatchSet(item.getThreadId());
    adapter.populateRecipients(item.getThreadId(), item.getRecipients());

    if (position >= 0) {
      adapter.notifyItemChanged(position);
    } else {
      int count = adapter.getItemCount();
      if (count > 0) adapter.notifyItemRangeChanged(0, count);
    }
  }

  @Override
  public void onSwitchToArchive() {
    if (!(getActivity() instanceof ConversationSelectedListener listener)) return;
    listener.onSwitchToArchive();
  }

  @Override
  public boolean onCreateActionMode(ActionMode mode, Menu menu) {
    mode.getMenuInflater().inflate(R.menu.conversation_list_cab, menu);

    MenuItem toggle = menu.findItem(R.id.menu_archive_selected);
    if (archive) {
      toggle.setIcon(R.drawable.ic_package_up);
      toggle.setTitle(R.string.conversation_list_batch_unarchive__menu_unarchive_selected);
    } else {
      toggle.setIcon(R.drawable.ic_package_down);
      toggle.setTitle(R.string.conversation_list_batch_archive__menu_archive_selected);
    }

    mode.setTitle(R.string.conversation_fragment_cab__batch_selection_mode);
    mode.setSubtitle(null);

    return true;
  }

//  private int statusBarColor;

  @Override
  public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
    return false;
  }

  @Override
  public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
    int id = item.getItemId();
    if (id == R.id.menu_select_all) {
      handleSelectAll();
      return true;
    } else if (id == R.id.menu_delete_selected) {
      handleDeleteSelected();
      return true;
    } else if (id == R.id.menu_archive_selected) {
      handleArchiveSelected();
      return true;
    } else if (id == R.id.menu_send_drafts) {
      handleSendDrafts();
      return true;
    }
    return false;
  }

  @Override
  public void onDestroyActionMode(ActionMode mode) {
    ConversationListAdapter adapter = getListAdapter();
    if (adapter != null) adapter.initializeBatchMode(false);

    actionMode = null;
  }

  private enum ReminderType {
    NONE,
    DEFAULT_SMS,
    SYSTEM_SMS_IMPORT,
    DELIVERY_REPORTS,
    STORE_RATING
  }

  public interface ConversationSelectedListener {
    void onCreateConversation(long threadId, Recipients recipients, int distributionType, long lastSeen);

    void onSwitchToArchive();
  }

  private final class ArchiveListenerCallback extends ItemTouchHelper.SimpleCallback {

    ArchiveListenerCallback() {
      // END means: LTR -> swipe right, RTL -> swipe left.
      super(0, ItemTouchHelper.END);
    }

    @Override
    public boolean onMove(@NonNull RecyclerView recyclerView,
                          @NonNull RecyclerView.ViewHolder viewHolder,
                          @NonNull RecyclerView.ViewHolder target) {
      return false;
    }

    @Override
    public int getSwipeDirs(@NonNull RecyclerView recyclerView,
                            @NonNull RecyclerView.ViewHolder viewHolder) {
      if (viewHolder.itemView instanceof ConversationListItemArchived) return 0;
      if (actionMode != null) return 0;
      return super.getSwipeDirs(recyclerView, viewHolder);
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
      if (getView() == null) return;

      final Context appContext = requireAppContext();

      final long threadId = ((ConversationListItem) viewHolder.itemView).getThreadId();
      final boolean read = ((ConversationListItem) viewHolder.itemView).getRead();
      final boolean archiveLocal = archive;

      final int textResId =
              archiveLocal
                      ? R.plurals.ConversationListFragment_moved_conversations_to_inbox
                      : R.plurals.ConversationListFragment_conversations_archived;

      final String title =
              getResources().getQuantityString(textResId, 1, 1);

      // Execute swipe action immediately.
      backgroundExecutor.execute(() -> {
        ThreadDatabase db = DatabaseFactory.getThreadDatabase(appContext);
        if (archiveLocal) {
          db.unarchiveConversation(threadId);
        } else {
          db.archiveConversation(threadId);
          if (!read) {
            db.setRead(threadId);
            if (masterSecret != null) MessageNotifier.updateNotification(appContext, masterSecret);
          }
        }
      });

      Snackbar.make(requireView(), title, Snackbar.LENGTH_LONG)
              .setAction(R.string.ConversationListFragment_undo, v -> backgroundExecutor.execute(() -> {
                ThreadDatabase db = DatabaseFactory.getThreadDatabase(appContext);
                if (archiveLocal) {
                  db.archiveConversation(threadId);
                } else {
                  db.unarchiveConversation(threadId);
                  if (!read) {
                    db.setUnread(threadId);
                    if (masterSecret != null)
                      MessageNotifier.updateNotification(appContext, masterSecret);
                  }
                }
              }))
              .show();
    }

    @Override
    public void onChildDraw(@NonNull Canvas c,
                            @NonNull RecyclerView recyclerView,
                            @NonNull RecyclerView.ViewHolder viewHolder,
                            float dX, float dY, int actionState,
                            boolean isCurrentlyActive) {

      if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
        final View itemView = viewHolder.itemView;

        // Do not fade the swiped item; otherwise the label behind it will "shine through".
        itemView.setAlpha(1f);
        itemView.setTranslationX(dX);

        if (dX == 0f) return;

        final float top = itemView.getTop();
        final float bottom = itemView.getBottom();
        final float left = itemView.getLeft();
        final float right = itemView.getRight();

        // dX > 0 -> revealed area is on the left; dX < 0 -> revealed area is on the right.
        final boolean revealOnLeft = dX > 0f;

        final float revealedLeft = revealOnLeft ? left : (right + dX);
        final float revealedRight = revealOnLeft ? (left + dX) : right;

        // Clip all drawing strictly to the revealed area.
        final int save = c.save();
        c.clipRect(revealedLeft, top, revealedRight, bottom);

        // Background.
        swipePaint.setColor(swipeBackgroundColor);
        c.drawRect(revealedLeft, top, revealedRight, bottom, swipePaint);

        // Icon (cached).
        Bitmap icon = archive ? swipeIconUnarchive : swipeIconArchive;

        // Lazy fallback if not yet prepared (should be rare).
        if (icon == null) {
          prepareSwipeIcons();
          icon = archive ? swipeIconUnarchive : swipeIconArchive;
        }

        if (icon != null) {
          final float padding = getResources().getDimension(R.dimen.conversation_list_fragment__swipe_icon_start_padding);

          // Place icon near the revealed edge.
          final float iconX = revealOnLeft
                  ? (left + padding)
                  : (right - padding - icon.getWidth());

          final float iconY = top + ((bottom - top - icon.getHeight()) / 2f);

          c.drawBitmap(icon, iconX, iconY, swipePaint);

          // Label: use existing menu titles for localization.
          final String text = archive
                  ? getString(R.string.conversation_list_item_view__unarchive)
                  : getString(R.string.conversation_list_item_view__archive);

          final float textWidth = swipeTextPaint.measureText(text);

          // If revealed on left, text is to the right of the icon.
          // If revealed on right, text is to the left of the icon.
          final float textX = revealOnLeft
                  ? (iconX + icon.getWidth() + swipeTextIconGapPx)
                  : (iconX - swipeTextIconGapPx - textWidth);

          // Vertically center the text.
          final Paint.FontMetrics fm = swipeTextPaint.getFontMetrics();
          final float textY = top + ((bottom - top) / 2f) - ((fm.ascent + fm.descent) / 2f);

          c.drawText(text, textX, textY, swipeTextPaint);
        }

        c.restoreToCount(save);
        return;
      }

      super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
    }
  }
}