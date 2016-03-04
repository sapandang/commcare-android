package org.commcare.tasks;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import org.commcare.CommCareApplication;
import org.commcare.activities.CommCareWiFiDirectActivity;
import org.commcare.dalvik.R;
import org.commcare.logging.AndroidLogger;
import org.commcare.models.database.SqlStorage;
import org.commcare.models.database.user.models.FormRecord;
import org.commcare.preferences.CommCarePreferences;
import org.commcare.tasks.templates.CommCareTask;
import org.commcare.utils.FileUtil;
import org.commcare.utils.FormUploadUtil;
import org.commcare.utils.SessionUnavailableException;
import org.commcare.utils.StorageUtils;
import org.commcare.views.notifications.NotificationMessageFactory;
import org.commcare.views.notifications.ProcessIssues;
import org.javarosa.core.services.Logger;
import org.javarosa.core.services.locale.Localization;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Properties;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * @author wspride
 */
public abstract class ZipTask extends CommCareTask<String, String, FormRecord[], CommCareWiFiDirectActivity> {
    private static final String TAG = ZipTask.class.getSimpleName();

    private Context c;
    private Long[] results;
    private File baseDirectory = new File(CommCareWiFiDirectActivity.baseDirectory);
    // this is where the forms that have been pulled from FormRecord storage to the file system live
    private File storedFormDirectory = new File(CommCareWiFiDirectActivity.sourceDirectory);

    public final static String FORM_PROPERTIES_FILE = "form.properties";
    public final static String FORM_PROPERTY_POST_URL = "PostURL";

    public static final int ZIP_TASK_ID = 72135;

    public ZipTask(Context c) {
        this.c = c;
        taskId = ZIP_TASK_ID;
    }

    @Override
    protected void onProgressUpdate(String... values) {
        super.onProgressUpdate(values);
    }

    @Override
    protected void onPostExecute(FormRecord[] result) {
        super.onPostExecute(result);
        //These will never get Zero'd otherwise
        c = null;
        results = null;
    }

    private static final String[] SUPPORTED_FILE_EXTS = {".xml", ".jpg", ".3gpp", ".3gp"};

    /**
     * Turn a FormRecord folder from storage into a standard file representation in our file system.n
     */
    private long getFileInstanceFromStorage(File formRecordFolder, SecretKeySpec decryptionKey)
            throws FileNotFoundException, SessionUnavailableException {
        File[] files = formRecordFolder.listFiles();
        Logger.log(TAG, "Trying to get instance with: " + files.length + " files.");

        File myDir = new File(storedFormDirectory, formRecordFolder.getName());
        myDir.mkdirs();

        if (files == null) {
            //make sure external storage is available to begin with.
            String state = Environment.getExternalStorageState();
            if (!Environment.MEDIA_MOUNTED.equals(state)) {
                //If so, just bail as if the user had logged out.
                throw new SessionUnavailableException("External Storage Removed");
            } else {
                throw new FileNotFoundException("No directory found at: " + formRecordFolder.getAbsoluteFile());
            }
        }

        //If we're listening, figure out how much (roughly) we have to send
        long bytes = 0;
        for (File file : files) {
            //Make sure we'll be sending it
            boolean supported = false;
            for (String ext : SUPPORTED_FILE_EXTS) {
                if (file.getName().endsWith(ext)) {
                    supported = true;
                    break;
                }
            }
            if (!supported) {
                continue;
            }
            bytes += file.length();
        }

        Log.d(TAG, "Storing " + bytes + " form bytes");

        final Cipher decryptCipher = FormUploadUtil.getDecryptCipher(decryptionKey);

        for (File file : files) {
            // This is not the ideal long term solution for determining whether we need decryption, but works
            if (file.getName().endsWith(".xml")) {
                try {
                    FileUtil.copyFile(file, new File(myDir, file.getName()), decryptCipher, null);
                } catch (IOException ie) {
                    Log.d(TAG, "Copying file: " + file.getName() + " failed with: " + ie.getMessage());
                    publishProgress(("File writing failed: " + ie.getMessage()));
                    return FormUploadUtil.FAILURE;
                }
            } else {
                try {
                    FileUtil.copyFile(file, new File(myDir, file.getName()));
                } catch (IOException ie) {
                    Log.d(TAG, "Copying file: " + file.getName() + " failed with: " + ie.getMessage());
                    publishProgress(("File writing failed: " + ie.getMessage()));
                    return FormUploadUtil.FAILURE;
                }
            }
        }
        // write any form.properties we want
        writeProperties(myDir);
        return FormUploadUtil.FULL_SUCCESS;
    }

    /**
     * Writes any properties of this form/user the receiving tablet might want to form.properties
     * Current properties:
     *  PostURL:    The receiver will attempt to submit to this URL instead of its default URL.
     *              We do this because HQ uses the receiver URL to help display forms prettily.
     * @param formInstanceFolder: the form instance folder to write in
     */
    private void writeProperties(File formInstanceFolder) {
        FileOutputStream outputStream = null;
        try {
            File formProperties = new File(formInstanceFolder, "form.properties");
            outputStream = new FileOutputStream(formProperties);
            Properties properties = new Properties();
            SharedPreferences settings = CommCareApplication._().getCurrentApp().getAppPreferences();
            // HQ likes us to submit forms to the "correct" app and user specific URL
            String postUrl = settings.getString(CommCarePreferences.PREFS_SUBMISSION_URL_KEY,
                    c.getString(R.string.PostURL));
            properties.setProperty("PostURL", postUrl);
            properties.store(outputStream, null);
        } catch(IOException e){
            // we'll just ignore this, not the end of the world
            e.printStackTrace();
        } finally{
            if(outputStream != null){
                try {
                    outputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private boolean zipParentFolder(File toBeZippedDirectory, String zipFilePath) throws IOException {

        Log.d(TAG, "Zipping directory" + toBeZippedDirectory.toString() + " to path " + zipFilePath);

        ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFilePath)));

        try {
            if (!toBeZippedDirectory.isDirectory()) {
                throw new RuntimeException("toBeZippedDirecory was not a directory. Bad.");
            }
            // the to be zipped directory should contain a bunch of sub directories
            File[] formInstanceFolders = toBeZippedDirectory.listFiles();

            for (File formInstanceFolder : formInstanceFolders) {
                File[] subFileArray = formInstanceFolder.listFiles();
                zipInstanceFolder(subFileArray, zipFilePath, out);
            }

        } finally {
            out.close();
        }
        return false;
    }

    private void zipInstanceFolder(File[] toBeZippedFiles, String zipFilePath, ZipOutputStream zos)
            throws IOException {
        Log.d(TAG, "Zipping instance folder with files: " + Arrays.toString(toBeZippedFiles)
                + ", zipFilePath: " + zipFilePath);

        int BUFFER_SIZE = 1024;
        BufferedInputStream origin;

        byte data[] = new byte[BUFFER_SIZE];

        for (File file : toBeZippedFiles) {
            FileInputStream fi = new FileInputStream(file);
            origin = new BufferedInputStream(fi, BUFFER_SIZE);
            try {

                String tempPath = file.getPath();

                Log.d(TAG, "Zipping instance folder with path: " + tempPath);

                String[] pathParts = tempPath.split("/");

                int pathPartsLength = pathParts.length;

                String fileName = pathParts[pathPartsLength - 1];
                String fileFolder = pathParts[pathPartsLength - 2];

                Log.d(TAG, "Zipping instance folder with path: " + fileFolder + "/" + fileName);

                ZipEntry entry = new ZipEntry(fileFolder + "/" + fileName);
                zos.putNextEntry(entry);
                int count;
                while ((count = origin.read(data, 0, BUFFER_SIZE)) != -1) {
                    zos.write(data, 0, count);
                }
            } finally {
                origin.close();
            }
        }
    }

    @Override
    protected FormRecord[] doTaskBackground(String... params) {
        Log.d(TAG, "Doing zip task in background with params: " + params);

        if (baseDirectory.exists() && baseDirectory.isDirectory()) {
            baseDirectory.delete();
        }

        baseDirectory.mkdirs();
        storedFormDirectory.mkdirs();

        SqlStorage<FormRecord> storage = CommCareApplication._().getUserStorage(FormRecord.class);
        Vector<Integer> ids = StorageUtils.getUnsentOrUnprocessedFormsForCurrentApp(storage);

        if (ids.size() > 0) {
            FormRecord[] records = new FormRecord[ids.size()];
            for (int i = 0; i < ids.size(); ++i) {
                records[i] = storage.read(ids.elementAt(i).intValue());
            }

            results = new Long[records.length];
            for (int i = 0; i < records.length; ++i) {
                //Assume failure
                results[i] = FormUploadUtil.FAILURE;
            }

            publishProgress(Localization.get("bulk.form.start"));

            for (int i = 0; i < records.length; ++i) {
                FormRecord record = records[i];
                try {
                    //If it's unsent, go ahead and send it
                    if (FormRecord.STATUS_UNSENT.equals(record.getStatus())) {
                        File folder;
                        try {
                            folder = new File(record.getPath(c)).getCanonicalFile().getParentFile();
                        } catch (IOException e) {
                            Logger.log(AndroidLogger.TYPE_ERROR_WORKFLOW, "Bizarre. Exception just getting the file reference. Not removing." + getExceptionText(e));
                            continue;
                        }

                        //Good!
                        //Time to transfer forms to storage!
                        try {
                            results[i] = getFileInstanceFromStorage(folder, new SecretKeySpec(record.getAesKey(), "AES"));
                        } catch (FileNotFoundException e) {
                            if (CommCareApplication._().isStorageAvailable()) {
                                //If storage is available generally, this is a bug in the app design
                                Logger.log(AndroidLogger.TYPE_ERROR_DESIGN, "Removing form record because file was missing|" + getExceptionText(e));
                            } else {
                                //Otherwise, the SD card just got removed, and we need to bail anyway.
                                CommCareApplication._().reportNotificationMessage(NotificationMessageFactory.message(ProcessIssues.StorageRemoved), true);
                                break;
                            }
                            continue;
                        }

                        //Check for success
                        if (results[i].intValue() == FormUploadUtil.FULL_SUCCESS) {
                            //FormRecordCleanupTask.wipeRecord(c, platform, record);
                            //publishProgress(Localization.get("bulk.form.dialog.progress",new String[]{""+i, ""+results[i].intValue()}));
                        }

                        if (results[i].intValue() == FormUploadUtil.FAILURE) {
                            publishProgress("Failure during zipping process");
                            return null;
                        }
                    }
                } catch (SessionUnavailableException sue) {
                    this.cancel(false);
                    return null;
                } catch (Exception e) {
                    //Just try to skip for now. Hopefully this doesn't wreck the model :/
                    Logger.log(AndroidLogger.TYPE_ERROR_DESIGN, "Totally Unexpected Error during form submission" + getExceptionText(e));
                }
            }

            long result = 0;
            for (int i = 0; i < records.length; ++i) {
                if (results[i] > result) {
                    result = results[i];
                }
            }

            if (result == 0) {
                try {
                    Log.d(TAG, "827 trying zip");

                    String zipPath = CommCareWiFiDirectActivity.sourceDirectory;

                    File nf = new File(zipPath);
                    if (nf.exists()) {
                        nf.delete();
                    }

                    zipParentFolder(nf, CommCareWiFiDirectActivity.sourceZipDirectory);
                    storedFormDirectory.delete();
                } catch (IOException ioe) {
                    Log.d(TAG, "827 IOException: " + ioe.getMessage());
                }
            }
            return records;
        } else {
            publishProgress("No forms to send.");
            return null;
        }
    }

    private String getExceptionText(Exception e) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            e.printStackTrace(new PrintStream(bos));
            return new String(bos.toByteArray());
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    protected void onCancelled() {
        super.onCancelled();

        CommCareApplication._().reportNotificationMessage(NotificationMessageFactory.message(ProcessIssues.LoggedOut));
    }

}
