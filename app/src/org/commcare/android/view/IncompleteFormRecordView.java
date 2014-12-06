/**
 * 
 */
package org.commcare.android.view;

import java.text.DateFormat;
import java.util.Date;
import java.util.Hashtable;

import org.commcare.android.database.user.models.FormRecord;
import org.commcare.android.util.MarkupUtil;
import org.commcare.android.util.MediaUtil;
import org.commcare.android.util.SessionUnavailableException;
import org.commcare.dalvik.R;
import org.commcare.suite.model.Text;
import org.javarosa.core.services.locale.Localization;
import org.javarosa.core.services.locale.Localizer;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * @author ctsims
 *
 */
public class IncompleteFormRecordView extends LinearLayout {
    
    public TextView mPrimaryTextView;
    public TextView mLowerTextView;
    public TextView mRightTextView;
    public TextView mUpperRight;
    
    Hashtable<String,Text> names;
    Date start;
    
    Drawable rightHandSync;
    
    boolean formExists = true;

    public IncompleteFormRecordView(Context context, Hashtable<String, Text> names) {
        super(context);
        
        ViewGroup vg = (ViewGroup)View.inflate(context, R.layout.formrecordview, null);
        this.names = names;
        
        mPrimaryTextView = (TextView)vg.findViewById(R.id.formrecord_txt_main);
        mLowerTextView = (TextView)vg.findViewById(R.id.formrecord_txt_btm);
        mRightTextView = (TextView)vg.findViewById(R.id.formrecord_txt_right);
        mUpperRight = (TextView)vg.findViewById(R.id.formrecord_txt_upp_right);
        
        mPrimaryTextView.setTextAppearance(context, android.R.style.TextAppearance_Large);
        mUpperRight.setTextAppearance(context, android.R.style.TextAppearance_Large);
        
        
        LayoutParams l =new LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT);
        addView(vg, l);

        start = new Date();
        
        rightHandSync = context.getResources().getDrawable(android.R.drawable.stat_notify_sync_noanim);
    }

    public void setParams(FormRecord record, String dataTitle, Long timestamp) throws SessionUnavailableException{
        if(names.containsKey(record.getFormNamespace())) {
            Text name = names.get(record.getFormNamespace());
            mPrimaryTextView.setText(MarkupUtil.localizeStyleSpannable(name.evaluate()));
        } else {
            formExists = false;
            mPrimaryTextView.setText(MarkupUtil.localizeStyleSpannable("form.record.gone"));
        }
        
        if(dataTitle != null) {
            mLowerTextView.setText(dataTitle); 
        }
                
        //be careful here...
        if(timestamp != 0) {
            mRightTextView.setText(DateUtils.formatSameDayTime(timestamp, start.getTime(), DateFormat.DEFAULT, DateFormat.DEFAULT));
        } else {
            mRightTextView.setText("Never");
        }
        if(record.getStatus() == FormRecord.STATUS_UNSENT) {
            mUpperRight.setText(MarkupUtil.localizeStyleSpannable("form.record.unsent"));
            mUpperRight.setTextAppearance(getContext(), R.style.WarningTextStyle);
            mUpperRight.setCompoundDrawablesWithIntrinsicBounds(null, null, rightHandSync, null);
        } else {
            mUpperRight.setText("");
            mUpperRight.setCompoundDrawables(null, null, null, null);
        }
    }
}
