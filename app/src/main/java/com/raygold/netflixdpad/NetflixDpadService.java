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
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Custom D-Pad Accessibility Helper for Mobile Netflix on Android 12 Projectors (RAY GOLD).
 *
 * How it works:
 * 1. Detects when Netflix (com.netflix.mediaclient) is in the foreground.
 * 2. Intercepts remote D-Pad keys (UP, DOWN, LEFT, RIGHT, DPAD_CENTER / ENTER).
 * 3. Scans the screen for clickable UI items (movie cards, tabs, Play/Pause, Skip Intro, profiles).
 * 4. Draws a high-visibility Netflix-Red focus border around the active item on the projector screen.
 * 5. Moves focus spatially in 2D when D-Pad arrows are pressed. When reaching the edge of the screen,
 *    automatically injects a smooth swipe gesture to scroll rows vertically or horizontally.
 * 6. Simulates a tap/click when the remote's OK/Center button is pressed.
 */
public class NetflixDpadService extends AccessibilityService {

    private static final String NETFLIX_PACKAGE = "com.netflix.mediaclient";
    private static final String PREFS_NAME = "NetflixDpadPrefs";

    private WindowManager windowManager;
    private FrameLayout overlayRoot;
    private View focusHighlightBox;
    private TextView modeBadgeView;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isNetflixForeground = false;
    private boolean isVirtualGridMode = false; // Toggleable fallback mode for custom surfaces/player

    private final Rect currentFocusRect = new Rect();
    private AccessibilityNodeInfo currentFocusedNode = null;

    private int screenWidth = 1920;
    private int screenHeight = 1080;

    // Virtual Grid cursor position (used when video player controls are hidden or in Grid Mode)
    private int gridX = 960;
    private int gridY = 540;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        updateScreenDimensions();
        createFocusOverlay();
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

        // Red glowing focus border around the currently selected Netflix item
        focusHighlightBox = new View(this);
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.argb(35, 229, 9, 20)); // Subtle translucent red fill
        border.setStroke(6, Color.rgb(229, 9, 20));  // Bright Netflix Red border (#E50914)
        border.setCornerRadius(14f);
        focusHighlightBox.setBackground(border);
        focusHighlightBox.setVisibility(View.GONE);

        FrameLayout.LayoutParams boxParams = new FrameLayout.LayoutParams(0, 0);
        overlayRoot.addView(focusHighlightBox, boxParams);

        // Subtle status pill in top-right corner when switching modes
        modeBadgeView = new TextView(this);
        modeBadgeView.setTextColor(Color.WHITE);
        modeBadgeView.setTextSize(12f);
        modeBadgeView.setPadding(20, 8, 20, 8);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.argb(200, 20, 20, 20));
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

        String pkg = event.getPackageName().toString();
        int eventType = event.getEventType();

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Ignore our own overlay window events
            if (pkg.equals(getPackageName())) return;

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            boolean allowAllApps = prefs.getBoolean("allow_all_apps", false);

            boolean nowNetflix = pkg.contains("netflix") || allowAllApps;
            if (nowNetflix != isNetflixForeground) {
                isNetflixForeground = nowNetflix;
                if (!isNetflixForeground) {
                    hideFocusOverlay();
                } else {
                    updateScreenDimensions();
                    showBadgeTemporarily("D-Pad Helper Active (Smart Focus)");
                    mainHandler.postDelayed(this::ensureValidFocus, 350);
                }
            }
        } else if (isNetflixForeground &&
                (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                        || eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            // Refresh highlight coordinates if the focused node moved
            if (!isVirtualGridMode && currentFocusedNode != null) {
                currentFocusedNode.refresh();
                Rect updated = new Rect();
                currentFocusedNode.getBoundsInScreen(updated);
                if (isValidScreenRect(updated)) {
                    currentFocusRect.set(updated);
                    drawFocusRect(currentFocusRect);
                }
            }
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!isNetflixForeground) {
            return super.onKeyEvent(event);
        }

        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // Allow long-press MENU or Channel/Page keys to toggle between Smart Focus and Virtual Grid Pointer
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_PROG_RED) {
            if (action == KeyEvent.ACTION_DOWN) {
                isVirtualGridMode = !isVirtualGridMode;
                if (isVirtualGridMode) {
                    showBadgeTemporarily("Mode: Virtual D-Pad Pointer (Video / Free Tap)");
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
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;

        if (!isDpadKey) {
            return super.onKeyEvent(event);
        }

        // Consume both ACTION_DOWN and ACTION_UP so Netflix doesn't get confused by raw key events
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
                performSelectAction();
                return true;
        }

        return super.onKeyEvent(event);
    }

    /**
     * Handles directional navigation in Smart UI Node Mode.
     * Finds the nearest clickable item in the requested direction; if at the edge of the screen,
     * scrolls Netflix via a touch swipe gesture and then re-scans.
     */
    private void navigateDirection(int keyCode) {
        List<AccessibilityNodeInfo> clickables = collectClickableNodes();

        // If no clickable nodes exist (e.g. fullscreen video playing with hidden HUD),
        // wake up the HUD or perform direct video actions:
        if (clickables.isEmpty()) {
            handleVideoPlayerFallback(keyCode);
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
            // No clickable card further in that direction -> auto-scroll the screen!
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
        if (currentFocusedNode != null) {
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
            // Wake video controls by tapping center of screen
            dispatchTapGesture(screenWidth / 2, screenHeight / 2);
            mainHandler.postDelayed(this::ensureValidFocus, 400);
        }
    }

    /**
     * When watching a movie full-screen and OSD controls are hidden:
     * - Left / Right double-taps left or right side to skip -10s / +10s (standard mobile Netflix gesture)
     * - Up / Down / OK taps the screen center to reveal the OSD controls and focuses Play/Pause
     */
    private void handleVideoPlayerFallback(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            showBadgeTemporarily("Rewind 10s");
            int leftX = (int) (screenWidth * 0.22f);
            int midY = screenHeight / 2;
            dispatchDoubleTapGesture(leftX, midY);
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            showBadgeTemporarily("Forward 10s");
            int rightX = (int) (screenWidth * 0.78f);
            int midY = screenHeight / 2;
            dispatchDoubleTapGesture(rightX, midY);
        } else {
            // Tap center to bring up Netflix player overlay controls
            dispatchTapGesture(screenWidth / 2, screenHeight / 2);
            mainHandler.postDelayed(this::ensureValidFocus, 400);
        }
    }

    private void handleVirtualGridKey(int keyCode) {
        int stepX = Math.max(80, screenWidth / 14);
        int stepY = Math.max(70, screenHeight / 10);

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                gridX = Math.max(40, gridX - stepX);
                updateGridHighlight();
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                gridX = Math.min(screenWidth - 40, gridX + stepX);
                updateGridHighlight();
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (gridY <= stepY + 40) {
                    // At top edge -> scroll up
                    scrollScreenForDirection(KeyEvent.KEYCODE_DPAD_UP, null);
                } else {
                    gridY -= stepY;
                    updateGridHighlight();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (gridY >= screenHeight - stepY - 40) {
                    // At bottom edge -> scroll down
                    scrollScreenForDirection(KeyEvent.KEYCODE_DPAD_DOWN, null);
                } else {
                    gridY += stepY;
                    updateGridHighlight();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                flashFocusBox();
                dispatchTapGesture(gridX, gridY);
                break;
        }
    }

    private void updateGridHighlight() {
        int size = 56;
        currentFocusRect.set(gridX - size / 2, gridY - size / 2, gridX + size / 2, gridY + size / 2);
        drawFocusRect(currentFocusRect);
    }

    private void ensureValidFocus() {
        if (!isNetflixForeground || isVirtualGridMode) return;
        List<AccessibilityNodeInfo> clickables = collectClickableNodes();
        if (!clickables.isEmpty()) {
            selectInitialNode(clickables);
        }
    }

    private void selectInitialNode(List<AccessibilityNodeInfo> clickables) {
        // Sort top-to-bottom, left-to-right and pick the first prominent card/button
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
            double dx = r.centerX() - targetX;
            double dy = r.centerY() - targetY;
            double dist = Math.hypot(dx, dy);
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

            // Skip the currently focused rect itself
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

            // Penalize large cross-axis jumps so Left/Right stays in the same horizontal movie row
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
        if (root == null) return result;
        traverseNodeTree(root, result);
        return result;
    }

    private void traverseNodeTree(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> outList) {
        if (node == null || !node.isVisibleToUser()) return;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        if (isValidScreenRect(bounds)) {
            boolean actionable = node.isClickable() || node.isFocusable();
            if (actionable && bounds.width() >= 36 && bounds.height() >= 36
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
            focusHighlightBox.setAlpha(0.3f);
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
                new GestureDescription.StrokeDescription(tapPath, 0, 60);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);
        dispatchGesture(builder.build(), null, null);
    }

    private void dispatchDoubleTapGesture(int x, int y) {
        dispatchTapGesture(x, y);
        mainHandler.postDelayed(() -> dispatchTapGesture(x, y), 140);
    }

    private void scrollScreenForDirection(int keyCode, Runnable onComplete) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        int startX = screenWidth / 2;
        int startY = screenHeight / 2;
        int endX = startX;
        int endY = startY;

        // If we have a focused card inside a horizontal row, start the horizontal swipe at that row's Y
        if (!currentFocusRect.isEmpty()) {
            startY = Math.max(150, Math.min(screenHeight - 150, currentFocusRect.centerY()));
            endY = startY;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // Swipe up to scroll content down
                startY = (int) (screenHeight * 0.72f);
                endY = (int) (screenHeight * 0.28f);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                // Swipe down to scroll content up
                startY = (int) (screenHeight * 0.28f);
                endY = (int) (screenHeight * 0.72f);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Swipe left on current row to reveal items to the right
                startX = (int) (screenWidth * 0.78f);
                endX = (int) (screenWidth * 0.22f);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // Swipe right on current row to reveal items to the left
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
