package org.commcare.adapters;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import org.commcare.activities.AppManagerActivity;
import org.commcare.dalvik.R;
import org.commcare.android.database.global.models.ApplicationRecord;
import org.commcare.utils.MultipleAppsUtil;

/**
 * The ArrayAdapter used by AppManagerActivity to display all installed
 * CommCare apps on the manager screen.
 *
 * @author amstone326
 */
public class AppManagerAdapter extends ArrayAdapter<ApplicationRecord> {

    private final AppManagerActivity context;

    public AppManagerAdapter(Context context) {
        super(context, android.R.layout.simple_list_item_1, MultipleAppsUtil.appRecordArray());
        this.context = (AppManagerActivity)context;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = View.inflate(context, R.layout.app_title_view, null);
        }
        ApplicationRecord toDisplay = this.getItem(position);
        TextView appName = v.findViewById(R.id.app_name);
        appName.setText(toDisplay.getDisplayName());
        v.setContentDescription(toDisplay.getUniqueId());
        return v;
    }
}
