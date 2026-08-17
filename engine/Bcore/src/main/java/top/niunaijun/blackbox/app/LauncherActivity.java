package top.niunaijun.blackbox.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import androidx.annotation.Nullable;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.R;
import top.niunaijun.blackbox.utils.Slog;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.animation.OvershootInterpolator;

/**
 * LauncherActivity - Handles the launch of virtual apps
 * This activity serves as a bridge between the host app and virtual apps
 */
public class LauncherActivity extends Activity {
    public static final String TAG = "SplashScreen";

    public static final String KEY_INTENT = "launch_intent";
    public static final String KEY_PKG = "launch_pkg";
    public static final String KEY_USER_ID = "launch_user_id";
    private boolean isRunning = false;

    public static void launch(Intent intent, int userId) {
        try {
            Intent splash = new Intent();
            splash.setClass(BlackBoxCore.getContext(), LauncherActivity.class);
            // Only use FLAG_ACTIVITY_NEW_TASK
            splash.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            splash.putExtra(LauncherActivity.KEY_INTENT, intent);
            splash.putExtra(LauncherActivity.KEY_PKG, intent.getPackage());
            splash.putExtra(LauncherActivity.KEY_USER_ID, userId);
            BlackBoxCore.getContext().startActivity(splash);
            Slog.d(TAG, "LauncherActivity.launch() called for package: " + intent.getPackage());
        } catch (Exception e) {
            Slog.e(TAG, "Error in LauncherActivity.launch()", e);
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
            
            Intent intent = getIntent();
            if (intent == null) {
                Slog.w(TAG, "Intent is null, finishing activity");
                finish();
                return;
            }
            
            Intent launchIntent = intent.getParcelableExtra(KEY_INTENT);
            String packageName = intent.getStringExtra(KEY_PKG);
            int userId = intent.getIntExtra(KEY_USER_ID, 0);

            if (launchIntent == null || packageName == null) {
                Slog.w(TAG, "Missing launch intent or package name, finishing activity");
                finish();
                return;
            }

            Slog.d(TAG, "LauncherActivity.onCreate() for package: " + packageName + ", userId: " + userId);

            // Get package info with enhanced error handling
            PackageInfo packageInfo = getPackageInfoWithFallback(packageName, userId);
            
            if (packageInfo == null) {
                Slog.w(TAG, "Package info not available for " + packageName + ", but proceeding with launch");
                // Don't fail immediately - try to proceed anyway
            } else {
                Slog.d(TAG, "Successfully retrieved package info for " + packageName);
            }
            
            // Properly load the app icon and app name
            Drawable drawable = null;
            String appName = packageName;
            try {
                if (packageInfo != null && packageInfo.applicationInfo != null) {
                    PackageManager pm = getPackageManager();
                    drawable = pm.getApplicationIcon(packageInfo.applicationInfo);
                    CharSequence label = pm.getApplicationLabel(packageInfo.applicationInfo);
                    if (label != null) appName = label.toString();
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to load app icon or name for " + packageName + ": " + e.getMessage());
            }
            setContentView(R.layout.activity_launcher);
            ImageView iconView = findViewById(R.id.iv_icon);
            android.view.View iconContainer = findViewById(R.id.card_icon_container);
            android.view.View torBadge = findViewById(R.id.layout_tor_splash_badge);
            TextView nameView = findViewById(R.id.tv_app_name);
            TextView statusView = findViewById(R.id.tv_loading_status);
            android.widget.ProgressBar progressBar = findViewById(R.id.pb_loading);

            boolean isTor = isTorEnabledForPackage(packageName);
            if (torBadge != null) {
                torBadge.setVisibility(isTor ? android.view.View.VISIBLE : android.view.View.GONE);
            }

            android.view.View torExitLayout = findViewById(R.id.layout_tor_exit_info);
            TextView torFlagView = findViewById(R.id.tv_tor_flag);
            TextView torExitDetails = findViewById(R.id.tv_tor_exit_details);

            if (isTor && torExitLayout != null) {
                torExitLayout.setVisibility(android.view.View.VISIBLE);
                torExitLayout.setAlpha(0f);
                torExitLayout.animate().alpha(1f).setDuration(400).setStartDelay(250).start();

                // Check if exit info is already cached
                updateTorExitUi(torFlagView, torExitDetails);

                // Fetch or refresh exit info asynchronously
                new Thread(() -> {
                    fetchTorExitInfoReflection();
                    runOnUiThread(() -> updateTorExitUi(torFlagView, torExitDetails));
                }, "TorExitInfoSplashThread").start();
            } else if (torExitLayout != null) {
                torExitLayout.setVisibility(android.view.View.GONE);
            }

            if (statusView != null) {
                statusView.setText(isTor ? "Iniciando con red segura Tor..." : "Iniciando aplicación...");
                statusView.setAlpha(0f);
                statusView.animate().alpha(1f).setDuration(400).setStartDelay(200).start();
            }

            if (progressBar != null) {
                progressBar.setAlpha(0f);
                progressBar.animate().alpha(1f).setDuration(400).setStartDelay(150).start();
            }

            if (nameView != null) {
                nameView.setText(appName);
                nameView.setAlpha(0f);
                nameView.animate()
                    .alpha(1f)
                    .setDuration(400)
                    .setStartDelay(100)
                    .start();
            }

            if (iconView != null && drawable != null) {
                iconView.setImageDrawable(drawable);
            }

            if (iconContainer != null) {
                iconContainer.setScaleX(0.75f);
                iconContainer.setScaleY(0.75f);
                iconContainer.setAlpha(0f);
                iconContainer.animate()
                    .scaleX(1.05f)
                    .scaleY(1.05f)
                    .alpha(1f)
                    .setDuration(350)
                    .setInterpolator(new OvershootInterpolator(1.2f))
                    .withEndAction(() -> {
                        if (iconContainer != null) {
                            iconContainer.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(180)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .start();
                        }
                    })
                    .start();
            }
            
            // Launch the app in a separate thread to avoid blocking the UI
            launchAppAsync(launchIntent, userId);
            
        } catch (Exception e) {
            Slog.e(TAG, "Critical error in LauncherActivity.onCreate()", e);
            finish();
        }
    }

    private void updateTorExitUi(TextView flagView, TextView detailsView) {
        if (flagView == null || detailsView == null) return;
        try {
            Class<?> torMgrClass = Class.forName("com.editech.services.tor.TorManager");
            String flag = (String) torMgrClass.getMethod("getTorExitFlag").invoke(null);
            String country = (String) torMgrClass.getMethod("getTorExitCountry").invoke(null);
            String ip = (String) torMgrClass.getMethod("getTorExitIp").invoke(null);

            if (ip != null && !ip.isEmpty()) {
                flagView.setText(flag != null && !flag.isEmpty() ? flag : "🧅");
                String countryStr = (country != null && !country.isEmpty()) ? country : "Tor Exit";
                detailsView.setText(countryStr + " · " + ip);
            } else {
                flagView.setText("🧅");
                detailsView.setText("Estableciendo circuito seguro...");
            }
        } catch (Throwable ignored) {
            flagView.setText("🧅");
            detailsView.setText("Tor Activo");
        }
    }

    private void fetchTorExitInfoReflection() {
        try {
            Class<?> torMgrClass = Class.forName("com.editech.services.tor.TorManager");
            torMgrClass.getMethod("fetchTorExitInfoSync").invoke(null);
        } catch (Throwable ignored) {}
    }

    /**
     * Checks if Tor routing is enabled for the target virtual app via reflection
     */
    private boolean isTorEnabledForPackage(String packageName) {
        if (packageName == null) return false;
        try {
            Class<?> torManagerClass = Class.forName("com.editech.services.tor.TorManager");
            java.lang.reflect.Method method = torManagerClass.getMethod("isTorEnabledForPackage", String.class);
            return (boolean) method.invoke(null, packageName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Get package info with enhanced error handling and fallback mechanisms
     */
    private PackageInfo getPackageInfoWithFallback(String packageName, int userId) {
        try {
            // First attempt: Try to get package info normally
            return BlackBoxCore.getBPackageManager().getPackageInfo(packageName, 0, userId);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get package info for " + packageName + " (attempt 1): " + e.getMessage());
            
            try {
                // Second attempt: Try with different flags
                return BlackBoxCore.getBPackageManager().getPackageInfo(packageName, 
                    android.content.pm.PackageManager.GET_META_DATA, userId);
            } catch (Exception e2) {
                Slog.w(TAG, "Failed to get package info for " + packageName + " (attempt 2): " + e2.getMessage());
                
                try {
                    // Third attempt: Try to get application info instead
                    android.content.pm.ApplicationInfo appInfo = BlackBoxCore.getBPackageManager()
                        .getApplicationInfo(packageName, 0, userId);
                    
                    if (appInfo != null) {
                        // Create a minimal PackageInfo from ApplicationInfo
                        PackageInfo fallbackInfo = new PackageInfo();
                        fallbackInfo.packageName = packageName;
                        fallbackInfo.applicationInfo = appInfo;
                        fallbackInfo.versionCode = 1;
                        fallbackInfo.versionName = "1.0";
                        fallbackInfo.firstInstallTime = System.currentTimeMillis();
                        fallbackInfo.lastUpdateTime = System.currentTimeMillis();
                        
                        Slog.d(TAG, "Created fallback PackageInfo for " + packageName);
                        return fallbackInfo;
                    }
                } catch (Exception e3) {
                    Slog.w(TAG, "Failed to get application info for " + packageName + ": " + e3.getMessage());
                }
            }
        }
        
        return null;
    }

    /**
     * Launch the app asynchronously to avoid blocking the UI thread
     */
    private void launchAppAsync(final Intent launchIntent, final int userId) {
        new Thread(() -> {
            try {
                Slog.d(TAG, "Starting app launch in background thread");
                
                // Add a small delay to ensure the launcher activity is properly displayed
                Thread.sleep(100);
                
                // Launch the app
                BlackBoxCore.getBActivityManager().startActivity(launchIntent, userId);
                
                Slog.d(TAG, "App launch initiated successfully");
            } catch (Exception e) {
                Slog.e(TAG, "Error launching app", e);
                
                // Try to show an error message to the user
                runOnUiThread(() -> {
                    try {
                        // You could show a toast or dialog here
                        Slog.e(TAG, "Failed to launch app: " + e.getMessage());
                    } catch (Exception uiException) {
                        Slog.e(TAG, "Error showing error message", uiException);
                    }
                });
            }
        }, "AppLaunchThread").start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isRunning = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isRunning) {
            finish();
        }
    }
}
