package org.odk.collect.android.activities.components;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.commcare.android.framework.CommCareActivity;
import org.commcare.dalvik.R;
import org.odk.collect.android.activities.FormEntryActivity;
import org.odk.collect.android.application.ODKStorage;
import org.odk.collect.android.preferences.FormEntryPreferences;

/**
 * @author ctsims
 */
public class FormLayoutHelpers {
    public static boolean determineNumberOfValidGroupLines(FormEntryActivity activity,
                                                           Rect newRootViewDimensions,
                                                           boolean groupNativeVisibility,
                                                           boolean groupForcedInvisible) {
        FrameLayout header = (FrameLayout)activity.findViewById(R.id.form_entry_header);
        TextView groupLabel = ((TextView)header.findViewById(R.id.form_entry_group_label));

        int contentSize = newRootViewDimensions.height();

        View navBar = activity.findViewById(R.id.nav_pane);
        int headerSize = navBar.getHeight();
        if (headerSize == 0) {
            headerSize = activity.getResources().getDimensionPixelSize(R.dimen.new_progressbar_minheight);
        }

        int availableWindow = contentSize - headerSize - getActionBarSize(activity);

        int questionFontSize = getFontSizeInPx(activity);

        //Request a consistent amount of the screen before groups can cut down

        int spaceRequested = questionFontSize * 6;

        int spaceAvailable = availableWindow - spaceRequested;

        int defaultHeaderSpace = activity.getResources().getDimensionPixelSize(R.dimen.content_min_margin) * 2;

        float textSize = groupLabel.getTextSize();

        int numberOfGroupLinesAllowed = (int)((spaceAvailable - defaultHeaderSpace) / textSize);

        if (numberOfGroupLinesAllowed < 0) {
            numberOfGroupLinesAllowed = 0;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            if (groupLabel.getMaxLines() == numberOfGroupLinesAllowed) {
                return groupForcedInvisible;
            }
        }

        if (numberOfGroupLinesAllowed == 0) {
            updateGroupViewVisibility(header, true, groupNativeVisibility);
            groupLabel.setMaxLines(0);
            return true;
        } else {
            updateGroupViewVisibility(header, false, groupNativeVisibility);
            groupLabel.setMaxLines(numberOfGroupLinesAllowed);
            return false;
        }
    }

    public static void updateGroupViewVisibility(FormEntryActivity activity,
                                                 boolean groupNativeVisibility,
                                                 boolean groupForcedInvisible) {
        FrameLayout header = (FrameLayout)activity.findViewById(R.id.form_entry_header);
        updateGroupViewVisibility(header, groupNativeVisibility, groupForcedInvisible);
    }

    private static void updateGroupViewVisibility(FrameLayout header,
                                                  boolean groupNativeVisibility,
                                                  boolean groupForcedInvisible) {
        TextView groupLabel = ((TextView)header.findViewById(R.id.form_entry_group_label));

        if (groupNativeVisibility && !groupForcedInvisible) {
            header.setVisibility(View.VISIBLE);
            groupLabel.setVisibility(View.VISIBLE);
        } else {
            header.setVisibility(View.GONE);
            groupLabel.setVisibility(View.GONE);

        }
    }

    private static int getFontSizeInPx(Activity activity) {
        SharedPreferences settings =
                PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext());
        String question_font =
                settings.getString(FormEntryPreferences.KEY_FONT_SIZE, ODKStorage.DEFAULT_FONTSIZE);

        int sizeInPx = Integer.valueOf(question_font);

        return (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, sizeInPx,
                activity.getResources().getDisplayMetrics());
    }

    private static int getActionBarSize(CommCareActivity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            int actionBarHeight = activity.getActionBar().getHeight();

            if (actionBarHeight != 0) {
                return actionBarHeight;
            }
            final TypedValue tv = new TypedValue();
            if (activity.getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
                actionBarHeight = TypedValue.complexToDimensionPixelSize(tv.data, activity.getResources().getDisplayMetrics());
            }
            return actionBarHeight;
        }
        return 0;
    }
}