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
import java.util.Collections;
import java.util.Comparator;
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

    private static final String PREFS_NAME = "NetflixDpadPrefs";

    private WindowManager windowManager;
    private FrameLayout overlayRoot;
    private View focusHighlightBox;
    private TextView modeBadgeView;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isHelperActive = true;
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

        String pkg = event.getPackageName().toString().toLowerCase();
        int eventType = event.getEventType();

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg.equals(getPackageName().toLowerCase())) return;

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            // Default to true so it works even if the projector uses a modified Netflix package name
            boolean allowAllApps = prefs.getBoolean("allow_all_apps", true);

            boolean shouldActivate = pkg.contains("netflix") || pkg.contains("mediaclient") || allowAllApps;
            isHelperActive = shouldActivate;
            if (!isHelperActive) {
                hideFocusOverlay();
            } else if (pkg.contains("netflix") || pkg.contains("mediaclient")) {
                updateScreenDimensions();
                showBadgeTemporarily("Netflix D-Pad Active");
                mainHandler.postDelayed(this::ensureValidFocus, 300);
            }
        }
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean servicePaused = prefs.getBoolean("service_paused", false);
        if (servicePaused || !isHelperActive) {
            return super.onKeyEvent(event);
        }

        // Check active window package; don't intercept keys when inside our own setup screen
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null) {
            String activePkg = root.getPackageName().toString();
            if (activePkg.equals(getPackageName())) {
                return super.onKeyEvent(event);
            }
            boolean allowAll = prefs.getBoolean("allow_all_apps", true);
            if (!allowAll && !activePkg.toLowerCase().contains("netflix")) {
                hideFocusOverlay();
                return super.onKeyEvent(event);
            }
        }

        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // Press MENU key to switch between Smart Card Focus and Virtual D-Pad Pointer
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_PROG_RED) {
            if (action == KeyEvent.ACTION_DOWN) {
                isVirtualGridMode = !isVirtualGridMode;
                if (isVirtualGridMode) {
                    showBadgeTemporarily("Mode: D-Pad Step Pointer + Auto-Scroll");
                    updateGridHighlight();
                } else {
                    showBadgeTemporarily("Mode: Smart Card Focus");
                    ensureValidFocus();
                }
            }
            return true;
        }

        boolean isDpadKey = keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A;

        if (!isDpadKey) {
            return super.onKeyEvent(event);
        }

        if (action != KeyEvent.ACTION_DOWN) {
            return true;
        }

        if (isVirtualGridMode) {
            handleVirtualGridKey(keyCode);
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                navigateDirection(keyCode);
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                performSelectAction();
                return true;
        }

        return super.onKeyEvent(event);
    }

    private void navigateDirection(int keyCode) {
        List<AccessibilityNodeInfo> clickables = collectClickableNodes();

        // If Netflix's current screen doesn't expose clickable nodes, automatically use the Virtual Step Pointer!
        if (clickables.isEmpty()) {
            handleVirtualGridKey(keyCode);
            return;
        }

        if (currentFocusRect.isEmpty() || !isValidScreenRect(currentFocusRect)) {
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
        Collections.sort(clickables, new Comparator<AccessibilityNodeInfo>() {
            @Override
            public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                Rect ra = new Rect();
                Rect rb = new Rect();
                a.getBoundsInScreen(ra);
                b.getBoundsInScreen(rb);
                if (Math.abs(ra.top - rb.top) > 40) {
                    return Integer.compare(ra.top, rb.top);
                }
                return Integer.compare(ra.left, rb.left);
            }
        });
        setFocusedNode(clickables.get(0));
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

        if (isValidScreenRect(bounds)) {
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

    private boolean isValidScreenRect(Rect r) {
        if (r == null || r.isEmpty()) return false;
        return r.left >= 0 && r.top >= 0
                && r.right <= screenWidth + 20
                && r.bottom <= screenHeight + 20
                && r.width() > 20 && r.height() > 20;
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
