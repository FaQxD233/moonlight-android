package com.limelight.files;

import android.app.Activity;
import android.os.Build;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.view.DisplayCutout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.ui.AppDialog;
import com.limelight.utils.UiHelper;

/** What the file transfer screens share: the page with its header, and the card rows they list. */
final class FilePage {
    // Rows stop growing wider than this on tablets and in landscape
    private static final int MAX_CONTENT_WIDTH_DP = 640;

    private FilePage() {
    }

    /** Shows the page with this title and returns the column to add rows to. */
    static LinearLayout setUp(Activity activity, int titleRes) {
        activity.setContentView(R.layout.activity_file_page);
        ((TextView) activity.findViewById(R.id.page_title)).setText(titleRes);
        activity.findViewById(R.id.page_back).setOnClickListener(v -> activity.finish());
        applyInsets(activity.findViewById(R.id.page_root));

        LinearLayout content = activity.findViewById(R.id.page_content);
        int extra = UiHelper.dpToPx(activity,
                Math.max(0, (activity.getResources().getConfiguration().screenWidthDp - MAX_CONTENT_WIDTH_DP) / 2));
        content.setPaddingRelative(content.getPaddingStart() + extra, content.getPaddingTop(),
                content.getPaddingEnd() + extra, content.getPaddingBottom());
        return content;
    }

    /** Adds a round button to the header. */
    static ImageButton addAction(Activity activity, int iconRes, int descriptionRes, View.OnClickListener onClick) {
        LinearLayout actions = activity.findViewById(R.id.page_actions);
        ImageButton button = (ImageButton) LayoutInflater.from(activity).inflate(R.layout.view_page_action, actions, false);
        button.setImageResource(iconRes);
        button.setContentDescription(activity.getString(descriptionRes));
        button.setOnClickListener(onClick);
        actions.addView(button);
        return button;
    }

    // As on the home screen, the content stays clear of the system bars and, unlike with
    // fitsSystemWindows, of a camera cutout at the side in landscape
    private static void applyInsets(View root) {
        int left = root.getPaddingLeft();
        int top = root.getPaddingTop();
        int right = root.getPaddingRight();
        int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int insetLeft;
            int insetTop;
            int insetRight;
            int insetBottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets safe = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                insetLeft = safe.left;
                insetTop = safe.top;
                insetRight = safe.right;
                insetBottom = safe.bottom;
            }
            else {
                insetLeft = insets.getSystemWindowInsetLeft();
                insetTop = insets.getSystemWindowInsetTop();
                insetRight = insets.getSystemWindowInsetRight();
                insetBottom = insets.getSystemWindowInsetBottom();
                DisplayCutout cutout = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? insets.getDisplayCutout() : null;
                if (cutout != null) {
                    insetLeft = Math.max(insetLeft, cutout.getSafeInsetLeft());
                    insetTop = Math.max(insetTop, cutout.getSafeInsetTop());
                    insetRight = Math.max(insetRight, cutout.getSafeInsetRight());
                    insetBottom = Math.max(insetBottom, cutout.getSafeInsetBottom());
                }
            }
            view.setPadding(left + insetLeft, top + insetTop, right + insetRight, bottom + insetBottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    /** A section heading, with a status such as "Searching…" at its end when there is one. */
    static void section(LinearLayout parent, CharSequence title, CharSequence status) {
        View section = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file_section, parent, false);
        ((TextView) section.findViewById(R.id.section_title)).setText(title);
        if (!TextUtils.isEmpty(status)) {
            TextView statusView = section.findViewById(R.id.section_status);
            statusView.setText(status);
            statusView.setVisibility(View.VISIBLE);
        }
        parent.addView(section);
    }

    /** A line of explanation between rows. */
    static void note(LinearLayout parent, CharSequence text) {
        TextView note = new TextView(parent.getContext());
        note.setText(text);
        note.setTextColor(ContextCompat.getColor(parent.getContext(), R.color.home_secondary_text));
        note.setTextSize(12);
        int horizontal = UiHelper.dpToPx(parent.getContext(), 4);
        note.setPadding(horizontal, UiHelper.dpToPx(parent.getContext(), 8), horizontal, 0);
        parent.addView(note);
    }

    /** What an empty list shows instead, with a button when action isn't null. */
    static void empty(LinearLayout parent, int iconRes, CharSequence text, CharSequence actionText,
                      View.OnClickListener action) {
        View empty = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file_empty, parent, false);
        ((ImageView) empty.findViewById(R.id.empty_icon)).setImageResource(iconRes);
        ((TextView) empty.findViewById(R.id.empty_text)).setText(text);
        if (action != null) {
            TextView actionView = empty.findViewById(R.id.empty_action);
            actionView.setText(actionText);
            actionView.setVisibility(View.VISIBLE);
            actionView.setOnClickListener(action);
        }
        parent.addView(empty);
    }

    /** A line for two big buttons side by side; see tile(). */
    static LinearLayout tiles(LinearLayout parent) {
        LinearLayout line = new LinearLayout(parent.getContext());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setBaselineAligned(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = UiHelper.dpToPx(parent.getContext(), 8);
        parent.addView(line, params);
        return line;
    }

    /** A big button in a line from tiles(); the two in a line are equally tall. */
    static void tile(LinearLayout line, int iconRes, CharSequence title, CharSequence detail, String key,
                     View.OnClickListener onClick) {
        View tile = LayoutInflater.from(line.getContext()).inflate(R.layout.item_file_tile, line, false);
        ((ImageView) tile.findViewById(R.id.tile_icon)).setImageResource(iconRes);
        ((TextView) tile.findViewById(R.id.tile_title)).setText(title);
        ((TextView) tile.findViewById(R.id.tile_detail)).setText(detail);
        if (line.getChildCount() > 0) {
            ((LinearLayout.LayoutParams) tile.getLayoutParams()).setMarginStart(UiHelper.dpToPx(line.getContext(), 8));
        }
        tile.setTag(key);
        tile.setOnClickListener(onClick);
        line.addView(tile);
    }

    static Row row(LinearLayout parent) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file_row, parent, false);
        parent.addView(view);
        return new Row(view);
    }

    /**
     * The key of the row or tile holding the focus, for restoreFocus() once the column is rebuilt:
     * rebuilding would otherwise drop a controller's focus.
     */
    static Object focusedKey(ViewGroup content) {
        View view = content.findFocus();
        while (view != null && view != content) {
            if (view.getTag() != null) {
                return view.getTag();
            }
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    static void restoreFocus(ViewGroup content, Object key) {
        View view = key != null ? content.findViewWithTag(key) : null;
        if (view != null) {
            view.requestFocus();
        }
    }

    static void showHelp(Activity activity) {
        AppDialog.showMessage(activity, activity.getString(R.string.file_help_title),
                activity.getString(R.string.file_help_text), null, null);
    }

    /** A card row: an icon, a title and a detail line, then a badge, a switch, an arrow or a menu button. */
    static final class Row {
        final View view;
        private final View iconTile;
        private final ImageView icon;
        private final TextView title;
        private final TextView detail;
        private final TextView badge;

        private Row(View view) {
            this.view = view;
            iconTile = view.findViewById(R.id.row_icon_tile);
            icon = view.findViewById(R.id.row_icon);
            title = view.findViewById(R.id.row_title);
            detail = view.findViewById(R.id.row_detail);
            badge = view.findViewById(R.id.row_badge);
        }

        Row icon(int iconRes) {
            icon.setImageResource(iconRes);
            return this;
        }

        /** Fills the icon tile with a picture, such as a thumbnail, instead of an icon. */
        ImageView picture() {
            icon.setPadding(0, 0, 0, 0);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iconTile.setClipToOutline(true);
            return icon;
        }

        Row title(CharSequence text) {
            title.setText(text);
            return this;
        }

        /** Long names keep their start and their extension. */
        Row middleEllipsis() {
            title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            return this;
        }

        Row detail(CharSequence text) {
            detail.setText(text);
            detail.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
            return this;
        }

        Row badge(CharSequence text, int colorRes) {
            badge.setText(text);
            badge.setTextColor(ContextCompat.getColor(view.getContext(), colorRes));
            badge.setVisibility(View.VISIBLE);
            return this;
        }

        Row toggle(boolean checked) {
            Switch toggle = view.findViewById(R.id.row_switch);
            toggle.setChecked(checked);
            toggle.setVisibility(View.VISIBLE);
            return this;
        }

        Row chevron() {
            view.findViewById(R.id.row_chevron).setVisibility(View.VISIBLE);
            return this;
        }

        Row onClick(View.OnClickListener listener) {
            view.setOnClickListener(listener);
            view.setFocusable(true);
            return this;
        }

        /** A menu button at the end of the row; a long press does the same. */
        Row more(CharSequence description, View.OnClickListener listener) {
            ImageButton more = view.findViewById(R.id.row_more);
            more.setContentDescription(description);
            more.setOnClickListener(listener);
            more.setVisibility(View.VISIBLE);
            view.setOnLongClickListener(v -> {
                listener.onClick(v);
                return true;
            });
            return this;
        }

        /** Names the row so the focus can come back to it after the column is rebuilt. */
        Row key(Object key) {
            view.setTag(key);
            return this;
        }

        Row dimmed() {
            view.setAlpha(0.55f);
            return this;
        }
    }
}
