package com.omnia.optimizer;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * Resolves adaptive Material 3 / One UI Material You theme colors and tactile feedback.
 */
public final class OmniaTheme {

    private OmniaTheme() {
    }

    public static int color(Context context, int resId) {
        return context.getResources().getColor(resId, context.getTheme());
    }

    public static int withAlpha(int color, int alpha0To255) {
        return (color & 0x00FFFFFF) | ((alpha0To255 & 0xFF) << 24);
    }

    public static boolean isDarkMode(Context context) {
        int mask = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    public static void hapticTap(View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    public static void hapticConfirm(View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }
}
