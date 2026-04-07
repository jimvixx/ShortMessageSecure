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

package org.jimvixx.smsecure.permissions;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.util.LRUCache;
import org.jimvixx.smsecure.util.ServiceUtil;

import java.lang.ref.WeakReference;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Permission helper.
 * NOTE:
 * - Builder API below uses legacy requestPermissions() and requestCode routing via OUTSTANDING.
 * - For modern Activity Result API, use dispatchActivityResult(Fragment, Map, PermissionsRequest).
 */
public class Permissions {

  private static final Map<Integer, PermissionsRequest> OUTSTANDING = new LRUCache<>(2);

  public static PermissionsBuilder with(@NonNull Activity activity) {
    return new PermissionsBuilder(new ActivityPermissionObject(activity));
  }

  public static PermissionsBuilder with(@NonNull Fragment fragment) {
    return new PermissionsBuilder(new FragmentPermissionObject(fragment));
  }

  /**
   * Modern Activity Result API entry point (RequestMultiplePermissions).
   * This does NOT depend on OUTSTANDING/requestCode. Use it from fragments that
   * registered ActivityResultLauncher and want to reuse PermissionsRequest callback logic.
   */
  static void dispatchActivityResult(@NonNull Fragment fragment,
                                     @NonNull Map<String, Boolean> grantResults,
                                     @NonNull PermissionsRequest request) {
    FragmentPermissionObject permissionObject = new FragmentPermissionObject(fragment);

    String[] permissions = grantResults.keySet().toArray(new String[0]);
    int[] results = new int[permissions.length];
    boolean[] shouldShowRationale = new boolean[permissions.length];

    for (int i = 0; i < permissions.length; i++) {
      String permission = permissions[i];

      results[i] = Boolean.TRUE.equals(grantResults.get(permission))
              ? PackageManager.PERMISSION_GRANTED
              : PackageManager.PERMISSION_DENIED;

      if (results[i] != PackageManager.PERMISSION_GRANTED) {
        shouldShowRationale[i] = permissionObject.shouldShouldPermissionRationale(permission);
      }
    }

    request.onResult(permissions, results, shouldShowRationale);
  }

  /**
   * Legacy routing for requestPermissions()/requestCode.
   */
  public static void onRequestPermissionsResult(@NonNull Fragment fragment,
                                                int requestCode,
                                                @NonNull String[] permissions,
                                                @NonNull int[] grantResults) {
    onRequestPermissionsResult(new FragmentPermissionObject(fragment), requestCode, permissions, grantResults);
  }

  /**
   * Legacy routing for requestPermissions()/requestCode.
   */
  public static void onRequestPermissionsResult(@NonNull Activity activity,
                                                int requestCode,
                                                @NonNull String[] permissions,
                                                @NonNull int[] grantResults) {
    onRequestPermissionsResult(new ActivityPermissionObject(activity), requestCode, permissions, grantResults);
  }

  private static void onRequestPermissionsResult(@NonNull PermissionObject context,
                                                 int requestCode,
                                                 @NonNull String[] permissions,
                                                 @NonNull int[] grantResults) {
    PermissionsRequest resultListener;

    synchronized (OUTSTANDING) {
      resultListener = OUTSTANDING.remove(requestCode);
    }

    if (resultListener == null) return;

    boolean[] shouldShowRationaleDialog = new boolean[permissions.length];

    for (int i = 0; i < permissions.length; i++) {
      if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
        shouldShowRationaleDialog[i] = context.shouldShouldPermissionRationale(permissions[i]);
      }
    }

    resultListener.onResult(permissions, grantResults, shouldShowRationaleDialog);
  }

  private static void requestPermissions(@NonNull Activity activity,
                                         int requestCode,
                                         @NonNull String... permissions) {
    ActivityCompat.requestPermissions(activity, filterNotGranted(activity, permissions), requestCode);
  }

  @SuppressWarnings("deprecation")
  private static void requestPermissions(@NonNull Fragment fragment,
                                         int requestCode,
                                         @NonNull String... permissions) {
    fragment.requestPermissions(filterNotGranted(fragment.requireContext(), permissions), requestCode);
  }

  private static @NonNull String[] filterNotGranted(@NonNull Context context,
                                                    @NonNull String... permissions) {
    List<String> result = new ArrayList<>(permissions.length);

    for (String permission : permissions) {
      if (ContextCompat.checkSelfPermission(context, permission)
              != PackageManager.PERMISSION_GRANTED) {
        result.add(permission);
      }
    }

    return result.toArray(new String[0]);
  }

  public static boolean hasAny(@NonNull Context context, @NonNull String... permissions) {
    for (String permission : permissions) {
      if (ContextCompat.checkSelfPermission(context, permission)
              == PackageManager.PERMISSION_GRANTED) {
        return true;
      }
    }

    return false;
  }

  public static boolean hasAll(@NonNull Context context, @NonNull String... permissions) {
    for (String permission : permissions) {
      if (ContextCompat.checkSelfPermission(context, permission)
              != PackageManager.PERMISSION_GRANTED) {
        return false;
      }
    }

    return true;
  }

  private static Intent getApplicationSettingsIntent(@NonNull Context context) {
    Intent intent = new Intent();
    intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
    Uri uri = Uri.fromParts("package", context.getPackageName(), null);
    intent.setData(uri);
    return intent;
  }

  public static class PermissionsBuilder {

    private final PermissionObject permissionObject;

    private String[] requestedPermissions;

    private Runnable allGrantedListener;
    private Runnable anyDeniedListener;
    private Runnable anyPermanentlyDeniedListener;

    private @Nullable String rationaleDialogMessage;

    private boolean ifNecessary;

    PermissionsBuilder(@NonNull PermissionObject permissionObject) {
      this.permissionObject = permissionObject;
    }

    public PermissionsBuilder request(@NonNull String... requestedPermissions) {
      this.requestedPermissions = requestedPermissions;
      return this;
    }

    public PermissionsBuilder ifNecessary() {
      this.ifNecessary = true;
      return this;
    }

    public PermissionsBuilder withRationaleDialog(@NonNull String message) {
      this.rationaleDialogMessage = message;
      return this;
    }

    public PermissionsBuilder withPermanentDenialDialog(@NonNull String message) {
      return onAnyPermanentlyDenied(new SettingsDialogListener(permissionObject.getContext(), message));
    }

    public PermissionsBuilder onAllGranted(@Nullable Runnable allGrantedListener) {
      this.allGrantedListener = allGrantedListener;
      return this;
    }

    public PermissionsBuilder onAnyDenied(@Nullable Runnable anyDeniedListener) {
      this.anyDeniedListener = anyDeniedListener;
      return this;
    }

    public PermissionsBuilder onAnyPermanentlyDenied(@Nullable Runnable anyPermanentlyDeniedListener) {
      this.anyPermanentlyDeniedListener = anyPermanentlyDeniedListener;
      return this;
    }

    public void execute() {
      PermissionsRequest request = new PermissionsRequest(
              allGrantedListener,
              anyDeniedListener,
              anyPermanentlyDeniedListener,
              null,
              null,
              null,
              null
      );

      if (requestedPermissions == null || requestedPermissions.length == 0) {
        executeNoPermissionsRequest(request);
        return;
      }

      if (ifNecessary && permissionObject.hasAll(requestedPermissions)) {
        executePreGrantedPermissionsRequest(request);
      } else if (rationaleDialogMessage != null) {
        executePermissionsRequestWithRationale(request);
      } else {
        executePermissionsRequest(request);
      }
    }

    private void executePreGrantedPermissionsRequest(@NonNull PermissionsRequest request) {
      int[] grantResults = new int[requestedPermissions.length];
      Arrays.fill(grantResults, PackageManager.PERMISSION_GRANTED);
      request.onResult(requestedPermissions, grantResults, new boolean[requestedPermissions.length]);
    }

    private void executePermissionsRequestWithRationale(@NonNull PermissionsRequest request) {
      View view = View.inflate(permissionObject.getContext(), R.layout.permissions_rationale_dialog, null);
      TextView messageView = view.findViewById(R.id.message);
      Button positiveButton = view.findViewById(R.id.permissions_rationale_positive);
      Button negativeButton = view.findViewById(R.id.permissions_rationale_negative);

      messageView.setText(rationaleDialogMessage);

      AlertDialog dialog = new AlertDialog.Builder(permissionObject.getContext())
              .setView(view)
              .create();

      dialog.show();

      if (dialog.getWindow() != null) {
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout((int) (permissionObject.getWindowWidth() * 0.75f),
                ViewGroup.LayoutParams.WRAP_CONTENT);
      }

      positiveButton.setOnClickListener(v -> {
        dialog.dismiss();
        executePermissionsRequest(request);
      });

      negativeButton.setOnClickListener(v -> {
        dialog.dismiss();
        executeNoPermissionsRequest(request);
      });
    }

    private void executePermissionsRequest(@NonNull PermissionsRequest request) {
      int requestCode = new SecureRandom().nextInt(65434) + 100;

      synchronized (OUTSTANDING) {
        OUTSTANDING.put(requestCode, request);
      }

      for (String permission : requestedPermissions) {
        request.addMapping(permission, permissionObject.shouldShouldPermissionRationale(permission));
      }

      permissionObject.requestPermissions(requestCode, requestedPermissions);
    }

    private void executeNoPermissionsRequest(@NonNull PermissionsRequest request) {
      String[] permissions = requestedPermissions != null
              ? filterNotGranted(permissionObject.getContext(), requestedPermissions)
              : new String[0];

      for (String permission : permissions) {
        request.addMapping(permission, true);
      }

      int[] grantResults = new int[permissions.length];
      boolean[] showDialog = new boolean[permissions.length];

      Arrays.fill(grantResults, PackageManager.PERMISSION_DENIED);
      Arrays.fill(showDialog, true);

      request.onResult(permissions, grantResults, showDialog);
    }
  }

  private abstract static class PermissionObject {
    abstract @NonNull Context getContext();

    abstract boolean shouldShouldPermissionRationale(@NonNull String permission);

    abstract boolean hasAll(@NonNull String... permissions);

    abstract void requestPermissions(int requestCode, @NonNull String... permissions);

    int getWindowWidth() {
      WindowManager windowManager = ServiceUtil.getWindowManager(getContext());
      Display display = windowManager.getDefaultDisplay();
      DisplayMetrics metrics = new DisplayMetrics();
      display.getMetrics(metrics);
      return metrics.widthPixels;
    }
  }

  private static class ActivityPermissionObject extends PermissionObject {

    private final Activity activity;

    ActivityPermissionObject(@NonNull Activity activity) {
      this.activity = activity;
    }

    @Override
    public @NonNull Context getContext() {
      return activity;
    }

    @Override
    public boolean shouldShouldPermissionRationale(@NonNull String permission) {
      return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission);
    }

    @Override
    public boolean hasAll(@NonNull String... permissions) {
      return Permissions.hasAll(activity, permissions);
    }

    @Override
    public void requestPermissions(int requestCode, @NonNull String... permissions) {
      Permissions.requestPermissions(activity, requestCode, permissions);
    }
  }

  private static class FragmentPermissionObject extends PermissionObject {

    private final Fragment fragment;

    FragmentPermissionObject(@NonNull Fragment fragment) {
      this.fragment = fragment;
    }

    @Override
    public @NonNull Context getContext() {
      return fragment.requireContext();
    }

    @Override
    public boolean shouldShouldPermissionRationale(@NonNull String permission) {
      return fragment.shouldShowRequestPermissionRationale(permission);
    }

    @Override
    public boolean hasAll(@NonNull String... permissions) {
      return Permissions.hasAll(fragment.requireContext(), permissions);
    }

    @Override
    public void requestPermissions(int requestCode, @NonNull String... permissions) {
      Permissions.requestPermissions(fragment, requestCode, permissions);
    }
  }

  private static class SettingsDialogListener implements Runnable {

    private final WeakReference<Context> context;
    private final String message;

    SettingsDialogListener(@NonNull Context context, @NonNull String message) {
      this.message = message;
      this.context = new WeakReference<>(context);
    }

    @Override
    public void run() {
      Context context = this.context.get();

      if (context != null) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.Permissions_permission_required)
                .setMessage(message)
                .setPositiveButton(R.string.Continue,
                        (dialog, which) -> context.startActivity(getApplicationSettingsIntent(context)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
      }
    }
  }
}