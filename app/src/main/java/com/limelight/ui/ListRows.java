package com.limelight.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.utils.UiHelper;

/** The two-line card rows of the stream log list. */
public final class ListRows {
    private ListRows() {
    }

    public static View create(Context context, CharSequence title, CharSequence detail, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiHelper.dpToPx(context, 16), UiHelper.dpToPx(context, 13),
                UiHelper.dpToPx(context, 16), UiHelper.dpToPx(context, 13));
        row.setBackgroundResource(R.drawable.bg_update_dialog_card_selector);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int margin = UiHelper.dpToPx(context, 6);
        params.setMargins(margin, margin, margin, margin);
        row.setLayoutParams(params);
        // A row without an action shouldn't look pressable or stop the controller's focus
        row.setFocusable(onClick != null);
        row.setClickable(onClick != null);
        row.setOnClickListener(onClick);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(14);
        titleView.setSingleLine(true);
        // Unlike the XML attribute, setSingleLine() doesn't add an ellipsis by itself
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(titleView);

        TextView detailView = new TextView(context);
        detailView.setText(detail);
        detailView.setTextColor(0xB3FFFFFF);
        detailView.setTextSize(11);
        detailView.setPadding(0, UiHelper.dpToPx(context, 5), 0, 0);
        row.addView(detailView);
        return row;
    }
}
