package com.raygold.netflixdpad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom D-Pad Accessibility Helper for Mobile Netflix on Android 12 Projectors (RAY GOLD).
 *
 * Upgraded v2:
 * - Works across ALL windows and custom Netflix flavors (even if WINDOW_STATE_CHANGED is suppressed).
 * - Includes both Smart Card Focus Mode and Virtual D-Pad Cursor Mode (with auto-edge scrolling).
 * - Automatically falls back to Virtual D-Pad Cursor whenever Netflix hides its Accessibility tree.
 */
public class NetflixDpadService extends AccessibilityService {

    public static final String PREFS_NAME = "NetflixDpadPrefs";
    public static final String KEY_ALLOW_ALL_APPS = "allow_all_apps";
    /**
     * Netflix-only by default. Universal mode intercepts the remote in every app (launcher,
     * Settings, ...), which breaks apps that already handle D-Pad navigation natively.
     */
    public static final boolean DEFAULT_ALLOW_ALL_APPS = false;
    private static final String KEY_SERVICE_PAUSED = "service_paused";

    private WindowManager windowManager;
    private FrameLayout overlayRoot;
    private View focusHighlightBox;
    private TextView modeBadgeView;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Last app seen in a window-state event (transient system/IME windows excluded).
    private String lastForegroundPkg = null;
    private boolean isVirtualGridMode = false;

    private final Rect currentFocusRect = new Rect();
    private AccessibilityNodeInfo currentFocusedNode = null;

    private int screenWidth = 1920;
    private int screenHeight = 1080;

    // Virtual D-Pad Pointer coordinates
    private int gridX = 960;
    private int gridY = 540;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        updateScreenDimensions();
        createFocusOverlay();
        showBadgeTemporarily("Netflix D-Pad Helper Ready");
    }

    private void updateScreenDimensions() {
        if (windowManager != null) {
            DisplayMetrics dm = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(dm);
            screenWidth = dm.widthPixels > 0 ? dm.widthPixels : 1920;
            screenHeight = dm.heightPixels > 0 ? dm.heightPixels : 1080;
            gridX = screenWidth / 2;
            gridY = screenHeight / 2;
        }
    }

    private void createFocusOverlay() {
        overlayRoot = new FrameLayout(this);

        focusHighlightBox = new View(this);
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.argb(45, 229, 9, 20));
        border.setStroke(6, Color.rgb(229, 9, 20));
        border.setCornerRadius(14f);
        focusHighlightBox.setBackground(border);
        focusHighlightBox.setVisibility(View.GONE);

        FrameLayout.LayoutParams boxParams = new FrameLayout.LayoutParams(0, 0);
        overlayRoot.addView(focusHighlightBox, boxParams);

        modeBadgeView = new TextView(this);
        modeBadgeView.setTextColor(Color.WHITE);
        modeBadgeView.setTextSize(13f);
        modeBadgeView.setPadding(24, 10, 24, 10);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.argb(210, 20, 20, 20));
        badgeBg.setStroke(2, Color.rgb(229, 9, 20));
        badgeBg.setCornerRadius(20f);
        modeBadgeView.setBackground(badgeBg);
        modeBadgeView.setVisibility(View.GONE);

        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END
        );
        badgeParams.setMargins(24, 24, 24, 24);
        overlayRoot.addView(modeBadgeView, badgeParams);

        WindowManager.LayoutParams wmParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        wmParams.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager.addView(overlayRoot, wmParams);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        String pkg = event.getPackageName().toString().toLowerCase();
        // Our own screens and transient system/IME windows (volume panel, keyboard, notification
        // shade) say nothing about which app is in front. Treating them as a foreground change used
        // to switch the helper off until the next Netflix window event.
        if (pkg.equals(getPackageName().toLowerCase()) || isTransientSystemPackage(pkg)) return;

        lastForegroundPkg = pkg;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean allowAllApps = prefs.getBoolean(KEY_ALLOW_ALL_APPS, DEFAULT_ALLOW_ALL_APPS);
        if (!isTargetPackage(pkg, allowAllApps)) {
            hideFocusOverlay();
        } else if (isNetflixPackage(pkg)) {
            updateScreenDimensions();
            showBadgeTemporarily("Netflix D-Pad Active");
            mainHandler.postDelayed(this::ensureValidFocus, 300);
        }
    }

    private static boolean isNetflixPackage(String lowerPkg) {
        return lowerPkg.contains("netflix") || lowerPkg.contains("mediaclient");
    }

    private static boolean isTransientSystemPackage(String lowerPkg) {
        return lowerPkg.startsWith("com.android.systemui") || lowerPkg.contains("inputmethod");
    }

    private static boolean isTargetPackage(String lowerPkg, boolean allowAllApps) {
        return allowAllApps || isNetflixPackage(lowerPkg);
    }

    /** True when the app currently in front is one the helper should drive. */
    private boolean isTargetAppInForeground(SharedPreferences prefs) {
        boolean allowAllApps = prefs.getBoolean(KEY_ALLOW_ALL_APPS, DEFAULT_ALLOW_ALL_APPS);

        String pkg = null;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null) {
            pkg = root.getPackageName().toString().toLowerCase();
        }
        // Netflix sometimes hides its accessibility tree; fall back to the last window event.
        if (pkg == null || isTransientSystemPackage(pkg)) {
            pkg = lastForegroundPkg;
        }
        if (pkg == null || pkg.equals(getPackageName().toLowerCase())) return false;
        return isTargetPackage(pkg, allowAllApps);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        final int keyCode = event.getKeyCode();

        // MENU / red button switches between Smart Card Focus and the Virtual D-Pad Pointer.
        final boolean isModeKey = keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_PROG_RED;
        boolean isDpadKey = keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A;

        // Leave every other key (volume, back, home, ...) alone, with no accessibility IPC at all.
        if (!isModeKey && !isDpadKey) {
            return super.onKeyEvent(event);
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_SERVICE_PAUSED, false) || !isTargetAppInForeground(prefs)) {
            return super.onKeyEvent(event);
        }

        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }

        // Walking Netflix's node tree can be slow on a projector SoC, and the system only waits a
        // short while for onKeyEvent to answer before delivering the key to the app anyway. Claim
        // the key right away and do the work on the next loop iteration.
        mainHandler.post(() -> {
            if (isModeKey) {
                toggleMode();
            } else {
                handleDpadKey(keyCode);
            }
        });
        return true;
    }

    private void toggleMode() {
        isVirtualGridMode = !isVirtualGridMode;
        if (isVirtualGridMode) {
            showBadgeTemporarily("Mode: D-Pad Step Pointer + Auto-Scroll");
            updateGridHighlight();
        } else {
            showBadgeTemporarily("Mode: Smart Card Focus");
            ensureValidFocus();
        }
    }

    private void handleDpadKey(int keyCode) {
        if (isVirtualGridMode) {
            handleVirtualGridKey(keyCode);
            return;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                navigateDirection(keyCode);
                break;
            default:
                // DPAD_CENTER / ENTER / NUMPAD_ENTER / BUTTON_A
                performSelectAction();
                break;
        }
    }

    private void navigateDirection(int keyCode) {
        List<AccessibilityNodeInfo> clickables = collectClickableNodes();

        // If Netflix's current screen doesn't expose clickable nodes, automatically use the Virtual Step Pointer!
        if (clickables.isEmpty()) {
            handleVirtualGridKey(keyCode);
            return;
        }

        refreshCurrentFocus();

        // Only an empty rect means "no focus yet". A card that is partly scrolled off-screen is
        // still a valid starting point (it used to reset focus to the top-left item).
        if (currentFocusRect.isEmpty()) {
            selectInitialNode(clickables);
            return;
        }

        AccessibilityNodeInfo bestCandidate = findBestCandidateInDirection(clickables, currentFocusRect, keyCode);
        if (bestCandidate != null) {
            setFocusedNode(bestCandidate);
        } else {
            scrollScreenForDirection(keyCode, () -> {
                List<AccessibilityNodeInfo> updatedNodes = collectClickableNodes();
                if (!updatedNodes.isEmpty()) {
                    AccessibilityNodeInfo afterScroll = findBestCandidateInDirection(
                            updatedNodes, currentFocusRect, keyCode);
                    if (afterScroll != null) {
                        setFocusedNode(afterScroll);
                    } else {
                        selectNearestNodeToCenter(updatedNodes, currentFocusRect.centerX(), currentFocusRect.centerY());
                    }
                }
            });
        }
    }

    /**
     * Netflix scrolls and recycles views on its own, so the remembered node may have moved or be
     * gone. Re-read its bounds; if it is gone, keep the last known rect as the starting point.
     */
    private void refreshCurrentFocus() {
        if (currentFocusedNode == null) return;
        if (currentFocusedNode.refresh()) {
            currentFocusedNode.getBoundsInScreen(currentFocusRect);
            drawFocusRect(currentFocusRect);
        } else {
            currentFocusedNode = null;
        }
    }

    private void performSelectAction() {
        if (!isVirtualGridMode && currentFocusedNode != null) {
            currentFocusedNode.refresh();
            boolean clicked = currentFocusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (clicked) {
                flashFocusBox();
                return;
            }
        }

        if (!currentFocusRect.isEmpty()) {
            flashFocusBox();
            dispatchTapGesture(currentFocusRect.centerX(), currentFocusRect.centerY());
        } else {
            flashFocusBox();
            dispatchTapGesture(gridX, gridY);
        }
    }

    private void handleVirtualGridKey(int keyCode) {
        int stepX = Math.max(90, screenWidth / 12);
        int stepY = Math.max(80, screenHeight / 9);

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (gridX <= stepX + 40) {
                    scrollScreenForDirection(KeyEvent.KEYCODE_DPAD_LEFT, null);
                } else {
                    gridX = Math.max(40, gridX - stepX);
                    updateGridHighlight();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (gridX >= screenWidth - stepX - 40) {
                    scrollScreenForDirection(KeyEvent.KEYCODE_DPAD_RIGHT, null);
                } else {
                    gridX = Math.min(screenWidth - 40, gridX + stepX);
                    updateGridHighlight();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (gridY <= stepY + 40) {
                    scrollScreenForDirection(KeyEvent.KEYCODE_DPAD_UP, null);
                } else {
                    gridY -= stepY;
                    updateGridHighlight();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (gridY >= screenHeight - stepY - 40) {
                    scrollScreenForDirection(KeyEvent.KEYCODE_DPAD_DOWN, null);
                } else {
                    gridY += stepY;
                    updateGridHighlight();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                flashFocusBox();
                dispatchTapGesture(gridX, gridY);
                break;
        }
    }

    private void updateGridHighlight() {
        int size = 64;
        currentFocusRect.set(gridX - size / 2, gridY - size / 2, gridX + size / 2, gridY + size / 2);
        drawFocusRect(currentFocusRect);
    }

    private void ensureValidFocus() {
        if (isVirtualGridMode) {
            updateGridHighlight();
            return;
        }
        List<AccessibilityNodeInfo> clickables = collectClickableNodes();
        if (!clickables.isEmpty()) {
            selectInitialNode(clickables);
        } else {
            updateGridHighlight();
        }
    }

    private void selectInitialNode(List<AccessibilityNodeInfo> clickables) {
        // Top-most row first, then the left-most item in that row. A linear scan replaces the old
        // tolerance-based comparator: it was not transitive, so Collections.sort could throw
        // "Comparison method violates its general contract!" on large lists and kill the service.
        Rect r = new Rect();
        int minTop = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo node : clickables) {
            node.getBoundsInScreen(r);
            minTop = Math.min(minTop, r.top);
        }
        AccessibilityNodeInfo first = null;
        int minLeft = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo node : clickables) {
            node.getBoundsInScreen(r);
            if (r.top - minTop <= 40 && r.left < minLeft) {
                minLeft = r.left;
                first = node;
            }
        }
        if (first != null) {
            setFocusedNode(first);
        }
    }

    private void selectNearestNodeToCenter(List<AccessibilityNodeInfo> nodes, int targetX, int targetY) {
        AccessibilityNodeInfo best = null;
        double bestDist = Double.MAX_VALUE;
        for (AccessibilityNodeInfo node : nodes) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            double dist = Math.hypot(r.centerX() - targetX, r.centerY() - targetY);
            if (dist < bestDist) {
                bestDist = dist;
                best = node;
            }
        }
        if (best != null) {
            setFocusedNode(best);
        }
    }

    private AccessibilityNodeInfo findBestCandidateInDirection(
            List<AccessibilityNodeInfo> candidates, Rect fromRect, int keyCode) {

        int fromX = fromRect.centerX();
        int fromY = fromRect.centerY();

        AccessibilityNodeInfo bestNode = null;
        double bestScore = Double.MAX_VALUE;

        for (AccessibilityNodeInfo node : candidates) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);

            if (Math.abs(r.centerX() - fromX) < 12 && Math.abs(r.centerY() - fromY) < 12) {
                continue;
            }

            int dx = r.centerX() - fromX;
            int dy = r.centerY() - fromY;

            boolean isInDirection = false;
            double primaryDist = 0;
            double orthogonalDist = 0;

            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    isInDirection = dx > 25;
                    primaryDist = dx;
                    orthogonalDist = Math.abs(dy);
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    isInDirection = dx < -25;
                    primaryDist = -dx;
                    orthogonalDist = Math.abs(dy);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    isInDirection = dy > 25;
                    primaryDist = dy;
                    orthogonalDist = Math.abs(dx);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    isInDirection = dy < -25;
                    primaryDist = -dy;
                    orthogonalDist = Math.abs(dx);
                    break;
            }

            if (!isInDirection) continue;

            double orthogonalWeight = (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    ? 3.2 : 1.8;
            double score = primaryDist + (orthogonalDist * orthogonalWeight);

            if (score < bestScore) {
                bestScore = score;
                bestNode = node;
            }
        }

        return bestNode;
    }

    private List<AccessibilityNodeInfo> collectClickableNodes() {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            traverseNodeTree(root, result);
        }
        if (result.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo wRoot = window.getRoot();
                    if (wRoot != null) {
                        traverseNodeTree(wRoot, result);
                    }
                }
            }
        }
        return result;
    }

    private void traverseNodeTree(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> outList) {
        if (node == null || !node.isVisibleToUser()) return;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        if (isMostlyOnScreen(bounds)) {
            boolean actionable = node.isClickable() || node.isFocusable();
            if (actionable && bounds.width() >= 32 && bounds.height() >= 32
                    && bounds.width() < screenWidth * 0.95f) {
                outList.add(node);
            }
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                traverseNodeTree(child, outList);
            }
        }
    }

    /**
     * Netflix carousels keep cards that are partly off the edge of the screen. Accept any node
     * with a reasonable visible area instead of requiring it to sit fully inside the screen,
     * otherwise those cards can never be reached with the remote.
     */
    private boolean isMostlyOnScreen(Rect r) {
        if (r == null || r.isEmpty()) return false;
        int visibleWidth = Math.min(r.right, screenWidth) - Math.max(r.left, 0);
        int visibleHeight = Math.min(r.bottom, screenHeight) - Math.max(r.top, 0);
        return visibleWidth >= 32 && visibleHeight >= 32;
    }

    private void setFocusedNode(AccessibilityNodeInfo node) {
        currentFocusedNode = node;
        node.getBoundsInScreen(currentFocusRect);
        gridX = currentFocusRect.centerX();
        gridY = currentFocusRect.centerY();
        drawFocusRect(currentFocusRect);
    }

    private void drawFocusRect(Rect rect) {
        mainHandler.post(() -> {
            if (focusHighlightBox == null) return;
            int pad = 6;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) focusHighlightBox.getLayoutParams();
            lp.width = rect.width() + pad * 2;
            lp.height = rect.height() + pad * 2;
            lp.leftMargin = Math.max(0, rect.left - pad);
            lp.topMargin = Math.max(0, rect.top - pad);
            focusHighlightBox.setLayoutParams(lp);
            focusHighlightBox.setVisibility(View.VISIBLE);
        });
    }

    private void flashFocusBox() {
        mainHandler.post(() -> {
            if (focusHighlightBox == null) return;
            focusHighlightBox.setAlpha(0.25f);
            focusHighlightBox.animate().alpha(1.0f).setDuration(180).start();
        });
    }

    private void hideFocusOverlay() {
        mainHandler.post(() -> {
            if (focusHighlightBox != null) {
                focusHighlightBox.setVisibility(View.GONE);
            }
            if (modeBadgeView != null) {
                modeBadgeView.setVisibility(View.GONE);
            }
        });
    }

    private void showBadgeTemporarily(String text) {
        mainHandler.post(() -> {
            if (modeBadgeView == null) return;
            modeBadgeView.setText(text);
            modeBadgeView.setVisibility(View.VISIBLE);
            mainHandler.removeCallbacks(hideBadgeRunnable);
            mainHandler.postDelayed(hideBadgeRunnable, 2200);
        });
    }

    private final Runnable hideBadgeRunnable = new Runnable() {
        @Override
        public void run() {
            if (modeBadgeView != null) {
                modeBadgeView.setVisibility(View.GONE);
            }
        }
    };

    private void dispatchTapGesture(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        int clampedX = Math.max(10, Math.min(screenWidth - 10, x));
        int clampedY = Math.max(10, Math.min(screenHeight - 10, y));

        Path tapPath = new Path();
        tapPath.moveTo(clampedX, clampedY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(tapPath, 0, 65);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);
        dispatchGesture(builder.build(), null, null);
    }

    private void scrollScreenForDirection(int keyCode, Runnable onComplete) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        int startX = screenWidth / 2;
        int startY = screenHeight / 2;
        int endX = startX;
        int endY = startY;

        if (!currentFocusRect.isEmpty()) {
            startY = Math.max(150, Math.min(screenHeight - 150, currentFocusRect.centerY()));
            endY = startY;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                startY = (int) (screenHeight * 0.72f);
                endY = (int) (screenHeight * 0.28f);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                startY = (int) (screenHeight * 0.28f);
                endY = (int) (screenHeight * 0.72f);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                startX = (int) (screenWidth * 0.78f);
                endX = (int) (screenWidth * 0.22f);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                startX = (int) (screenWidth * 0.22f);
                endX = (int) (screenWidth * 0.78f);
                break;
        }

        Path swipePath = new Path();
        swipePath.moveTo(startX, startY);
        swipePath.lineTo(endX, endY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(swipePath, 0, 260);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                if (onComplete != null) {
                    mainHandler.postDelayed(onComplete, 220);
                }
            }
        }, null);
    }

    @Override
    public void onInterrupt() {
        hideFocusOverlay();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (windowManager != null && overlayRoot != null) {
            try {
                windowManager.removeView(overlayRoot);
            } catch (Exception ignored) {
            }
        }
    }
}
