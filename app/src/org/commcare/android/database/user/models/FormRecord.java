package org.commcare.android.database.user.models;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.commcare.android.storage.framework.Persisted;
import org.commcare.models.framework.Persisting;
import org.commcare.modern.database.Table;
import org.commcare.modern.models.EncryptedModel;
import org.commcare.modern.models.MetaField;
import org.commcare.provider.InstanceProviderAPI.InstanceColumns;
import org.commcare.util.LogTypes;
import org.javarosa.core.services.Logger;

import java.io.FileNotFoundException;
import java.util.Date;

/**
 * @author ctsims
 */
@Table(FormRecord.STORAGE_KEY)
public class FormRecord extends Persisted implements EncryptedModel {

    public static final String STORAGE_KEY = "FORMRECORDS";

    public static final String META_INSTANCE_URI = "INSTANCE_URI";
    public static final String META_STATUS = "STATUS";
    public static final String META_UUID = "UUID";
    public static final String META_XMLNS = "XMLNS";
    public static final String META_LAST_MODIFIED = "DATE_MODIFIED";
    public static final String META_APP_ID = "APP_ID";
    public static final String META_SUBMISSION_ORDERING_NUMBER = "SUBMISSION_ORDERING_NUMBER";

    /**
     * This form record is a stub that hasn't actually had data saved for it yet
     */
    public static final String STATUS_UNSTARTED = "unstarted";

    /**
     * This form has been saved, but has not yet been marked as completed and ready for processing
     */
    public static final String STATUS_INCOMPLETE = "incomplete";

    /**
     * User entry on this form has finished, but the form has not been processed yet
     */
    public static final String STATUS_COMPLETE = "complete";

    /**
     * The form has been processed and is ready to be sent to the server *
     */
    public static final String STATUS_UNSENT = "unsent";

    /**
     * This form has been fully processed and is being retained for viewing in the future
     */
    public static final String STATUS_SAVED = "saved";

    /**
     * This form was complete, but something blocked it from processing and it's in a damaged
     * state (a.k.a. "quarantined")
     */
    public static final String STATUS_QUARANTINED = "limbo";

    /**
     * This form has been downloaded, but not processed for metadata
     */
    public static final String STATUS_UNINDEXED = "unindexed";

    /**
     * Represents a form record that was just deleted from the db, but which we still need an
     * object representation of to reference in the short-term
     */
    public static final String STATUS_JUST_DELETED = "just-deleted";

    public static final String QuarantineReason_LOCAL_PROCESSING_ERROR = "local-processing-error";
    public static final String QuarantineReason_SERVER_PROCESSING_ERROR = "server-processing-error";
    public static final String QuarantineReason_RECORD_ERROR = "record-error";
    public static final String QuarantineReason_MANUAL = "manual-quarantine";
    public static final String QuarantineReason_FILE_NOT_FOUND = "file-not-found";

    private static final String QUARANTINE_REASON_AND_DETAIL_SEPARATOR = "@@SEP@@";

    @Persisting(1)
    @MetaField(META_XMLNS)
    private String xmlns;

    @Persisting(2)
    @MetaField(META_INSTANCE_URI)
    private String instanceURI;

    @Persisting(3)
    @MetaField(META_STATUS)
    private String status;

    @Persisting(4)
    private byte[] aesKey;

    @Persisting(value = 5, nullable = true)
    @MetaField(META_UUID)
    private String uuid;

    @Persisting(6)
    @MetaField(META_LAST_MODIFIED)
    private Date lastModified;

    @Persisting(7)
    @MetaField(META_APP_ID)
    private String appId;

    @Persisting(value = 8, nullable = true)
    @MetaField(META_SUBMISSION_ORDERING_NUMBER)
    private String submissionOrderingNumber;

    @Persisting(value = 9, nullable = true)
    private String quarantineReason;

    public FormRecord() {
    }

    /**
     * Creates a record of a form entry with the provided data. Note that none
     * of the parameters can be null...
     */
    public FormRecord(String instanceURI, String status, String xmlns, byte[] aesKey, String uuid,
                      Date lastModified, String appId) {
        this.instanceURI = instanceURI;
        this.status = status;
        this.xmlns = xmlns;
        this.aesKey = aesKey;

        this.uuid = uuid;
        this.lastModified = lastModified;
        if (lastModified == null) {
            this.lastModified = new Date();
        }
        this.appId = appId;
    }

    /**
     * Create a copy of the current form record, with an updated instance uri
     * and status.
     */
    public FormRecord updateInstanceAndStatus(String instanceURI, String newStatus) {
        FormRecord fr = new FormRecord(instanceURI, newStatus, xmlns, aesKey, uuid,
                lastModified, appId);
        fr.recordId = this.recordId;
        fr.submissionOrderingNumber = this.submissionOrderingNumber;
        return fr;
    }

    public static FormRecord StandInForDeletedRecord() {
        FormRecord r = new FormRecord();
        r.status = STATUS_JUST_DELETED;
        return r;
    }

    public Uri getInstanceURI() {
        if ("".equals(instanceURI)) {
            return null;
        }
        return Uri.parse(instanceURI);
    }

    public byte[] getAesKey() {
        return aesKey;
    }

    public String getStatus() {
        return status;
    }

    public Date lastModified() {
        return lastModified;
    }

    public String getFormNamespace() {
        return xmlns;
    }

    @Override
    public boolean isEncrypted(String data) {
        return false;
    }

    @Override
    public boolean isBlobEncrypted() {
        return true;
    }

    public String getAppId() {
        return this.appId;
    }

    public String getInstanceID() {
        return uuid;
    }

    public int getSubmissionOrderingNumber() {
        if (submissionOrderingNumber == null) {
            return -1;
        }
        return Integer.parseInt(submissionOrderingNumber);
    }

    public void setQuarantineReason(String reasonType, String reasonDetail) {
        this.quarantineReason = reasonType;
        if (reasonDetail != null) {
            this.quarantineReason += (QUARANTINE_REASON_AND_DETAIL_SEPARATOR + reasonDetail);
        }
    }

    public String getQuarantineReasonType() {
        return (quarantineReason == null) ?
                null :
                quarantineReason.split(QUARANTINE_REASON_AND_DETAIL_SEPARATOR)[0];
    }

    public String getQuarantineReasonDetail() {
        if (quarantineReason == null) {
            return null;
        }
        String[] typeAndDetail = this.quarantineReason.split(QUARANTINE_REASON_AND_DETAIL_SEPARATOR);
        if (typeAndDetail.length == 2) {
            return typeAndDetail[1];
        } else {
            return null;
        }
    }

    /**
     * Get the file system path to the encrypted XML submission file.
     *
     * @param context Android context
     * @return A string containing the location of the encrypted XML instance for this form
     * @throws FileNotFoundException If there isn't a record available defining a path for this form
     */
    public String getPath(Context context) throws FileNotFoundException {
        Uri uri = getInstanceURI();
        if (uri == null) {
            throw new FileNotFoundException("No form instance URI exists for formrecord " + recordId);
        }

        Cursor c = null;
        try {
            c = context.getContentResolver().query(uri, new String[]{InstanceColumns.INSTANCE_FILE_PATH}, null, null, null);
            if (c == null || !c.moveToFirst()) {
                throw new FileNotFoundException("No Instances were found at for formrecord " + recordId + " at isntance URI " + uri.toString());
            }

            return c.getString(c.getColumnIndex(InstanceColumns.INSTANCE_FILE_PATH));
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    @Override
    public String toString() {
        return String.format("Form Record[%s][Status: %s]\n[Form: %s]\n[Last Modified: %s]", this.recordId, this.status, this.xmlns, this.lastModified.toString());
    }

    public void setFormNumberForSubmissionOrdering(int num) {
        this.submissionOrderingNumber = ""+num;
    }

    public void logPendingDeletion(String classTag, String reason) {
        String logMessage = String.format(
                "Wiping form record with id %s and submission ordering number %s " +
                        "in class %s because %s",
                getInstanceID(),
                getSubmissionOrderingNumber(),
                classTag, reason);
        Logger.log(LogTypes.TYPE_FORM_DELETION, logMessage);
    }

    public void logManualQuarantine() {
        String logMessage = String.format(
                "User manually quarantined form record with id %s and submission ordering number %s ",
                getInstanceID(),
                getSubmissionOrderingNumber());
        Logger.log(LogTypes.TYPE_FORM_DELETION, logMessage);
    }

}
