package org.commcare.android.database;

import android.content.ContentValues;
import android.database.Cursor;
import android.util.Pair;

import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SQLiteQueryBuilder;

import org.commcare.android.crypt.EncryptionIO;
import org.commcare.android.logic.GlobalConstants;
import org.commcare.android.util.SessionUnavailableException;
import org.commcare.dalvik.application.CommCareApplication;
import org.commcare.modern.database.DatabaseHelper;
import org.javarosa.core.io.StreamsUtil;
import org.javarosa.core.services.storage.EntityFilter;
import org.javarosa.core.services.storage.IStorageIterator;
import org.javarosa.core.services.storage.Persistable;
import org.javarosa.core.util.InvalidIndexException;
import org.javarosa.core.util.externalizable.Externalizable;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Vector;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sql logic for storing persistable objects. Uses the filesystem to store
 * persitables; useful when objects are larger than the 1mb sql row limit.
 *
 * @author Phillip Mates (pmates@dimagi.com).
 */
public class SqlFileBackedStorage<T extends Persistable> extends SqlStorage<T> {
    private final File dbDir;
    private final boolean areFilesEncrypted;

    public SqlFileBackedStorage(String table, Class<? extends T> ctype,
                                AndroidDbHelper helper, String baseDir, boolean areFilesEncrypted) {
        super(table, ctype, helper);

        this.areFilesEncrypted = areFilesEncrypted;
        dbDir = new File(baseDir + GlobalConstants.FILE_CC_DB + table);
        setupDir();
    }

    private void setupDir() {
        if (!dbDir.exists()) {
            if (!dbDir.mkdirs()) {
                throw new RuntimeException("unable to create db storage directory: " + dbDir);
            }
        }
    }

    @Override
    public Vector<T> getRecordsForValues(String[] fieldNames, Object[] values) {
        Pair<String, String[]> whereClause = helper.createWhereAndroid(fieldNames, values, em, null);

        Cursor c;
        try {
            c = helper.getHandle().query(table, new String[]{DatabaseHelper.FILE_COL, DatabaseHelper.AES_COL}, whereClause.first, whereClause.second, null, null, null);
        } catch (SessionUnavailableException e) {
            throw new UserStorageClosedException(e.getMessage());
        }
        try {
            if (c.getCount() == 0) {
                return new Vector<>();
            } else {
                c.moveToFirst();
                Vector<T> recordObjects = new Vector<>();
                int fileColIndex = c.getColumnIndexOrThrow(DatabaseHelper.FILE_COL);
                int aesColIndex = c.getColumnIndexOrThrow(DatabaseHelper.AES_COL);
                while (!c.isAfterLast()) {
                    String filename = c.getString(fileColIndex);
                    byte[] aesKeyBlob = c.getBlob(aesColIndex);
                    InputStream inputStream = getFileInputStream(filename, aesKeyBlob);
                    recordObjects.add(newObject(inputStream));
                    c.moveToNext();
                }
                return recordObjects;
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    @Override
    public T getRecordForValues(String[] rawFieldNames, Object[] values) throws NoSuchElementException, InvalidIndexException {
        SQLiteDatabase appDb;
        try {
            appDb = helper.getHandle();
        } catch (SessionUnavailableException e) {
            throw new UserStorageClosedException(e.getMessage());
        }

        Cursor c;
        Pair<String, String[]> whereClause = helper.createWhereAndroid(rawFieldNames, values, em, null);
        c = appDb.query(table, new String[]{DatabaseHelper.ID_COL, DatabaseHelper.FILE_COL, DatabaseHelper.AES_COL}, whereClause.first, whereClause.second, null, null, null);
        try {
            int queryCount = c.getCount();
            if (queryCount == 0) {
                throw new NoSuchElementException("No element in table " + table + " with names " + Arrays.toString(rawFieldNames) + " and values " + Arrays.toString(values));
            } else if (queryCount > 1) {
                throw new InvalidIndexException("Invalid unique column set" + Arrays.toString(rawFieldNames) + ". Multiple records found with value " + Arrays.toString(values), Arrays.toString(rawFieldNames));
            }
            c.moveToFirst();
            String filename = c.getString(c.getColumnIndexOrThrow(DatabaseHelper.FILE_COL));
            byte[] aesKeyBlob = c.getBlob(c.getColumnIndexOrThrow(DatabaseHelper.AES_COL));
            return newObject(getFileInputStream(filename, aesKeyBlob));
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    @Override
    public T getRecordForValue(String rawFieldName, Object value) throws NoSuchElementException, InvalidIndexException {
        SQLiteDatabase db;
        try {
            db = helper.getHandle();
        } catch (SessionUnavailableException e) {
            throw new UserStorageClosedException(e.getMessage());
        }

        Pair<String, String[]> whereClause = helper.createWhereAndroid(new String[]{rawFieldName}, new Object[]{value}, em, null);

        if (STORAGE_OUTPUT_DEBUG) {
            String sql = SQLiteQueryBuilder.buildQueryString(false, table, new String[]{DatabaseHelper.ID_COL}, whereClause.first, null, null, null, null);
            DbUtil.explainSql(db, sql, whereClause.second);
        }

        String scrubbedName = AndroidTableBuilder.scrubName(rawFieldName);
        Cursor c = db.query(table, new String[]{DatabaseHelper.FILE_COL, DatabaseHelper.AES_COL}, whereClause.first, whereClause.second, null, null, null);
        try {
            int queryCount = c.getCount();
            if (queryCount == 0) {
                throw new NoSuchElementException("No element in table " + table + " with name " + scrubbedName + " and value " + value.toString());
            } else if (queryCount > 1) {
                throw new InvalidIndexException("Invalid unique column " + scrubbedName + ". Multiple records found with value " + value.toString(), scrubbedName);
            }
            c.moveToFirst();
            String filename = c.getString(c.getColumnIndexOrThrow(DatabaseHelper.FILE_COL));
            byte[] aesKeyBlob = c.getBlob(c.getColumnIndexOrThrow(DatabaseHelper.AES_COL));
            return newObject(getFileInputStream(filename, aesKeyBlob));
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    @Override
    public int add(Externalizable externalizable) {
        SQLiteDatabase db;
        try {
            db = helper.getHandle();
        } catch (SessionUnavailableException sue) {
            throw new UserStorageClosedException(sue.getMessage());
        }
        int i = -1;
        try {
            db.beginTransaction();

            File dataFile = newFileForEntry();
            ContentValues contentValues = helper.getNonDataContentValues(externalizable);
            contentValues.put(DatabaseHelper.FILE_COL, dataFile.getAbsolutePath());
            long ret = db.insertOrThrow(table, DatabaseHelper.FILE_COL, contentValues);

            if (ret > Integer.MAX_VALUE) {
                throw new RuntimeException("Waaaaaaaaaay too many values");
            }

            i = (int)ret;

            db.setTransactionSuccessful();
        } catch (IOException e) {
            // failed to create new file
            // TODO PLM: what is the correct way to fail here?
            e.printStackTrace();
        } finally {
            db.endTransaction();
        }

        return i;
    }

    @Override
    public SqlStorageIterator<T> iterate(boolean includeData) {
        SQLiteDatabase db;
        try {
            db = helper.getHandle();
        } catch (SessionUnavailableException e) {
            throw new UserStorageClosedException(e.getMessage());
        }

        SqlStorageIterator<T> spanningIterator = getIndexSpanningIteratorOrNull(db, includeData);
        if (spanningIterator != null) {
            return spanningIterator;
        } else {
            return new SqlFileBackedStorageIterator<>(getIterateCursor(db, includeData), this);
        }
    }

    @Override
    protected Cursor getIterateCursor(SQLiteDatabase db, boolean includeData) {
        String[] projection = includeData ? new String[]{DatabaseHelper.ID_COL, DatabaseHelper.FILE_COL} : new String[]{DatabaseHelper.ID_COL};
        return db.query(table, projection, null, null, null, null, null);
    }

    @Override
    public SqlStorageIterator<T> iterate(boolean includeData, String primaryId) {
        throw new UnsupportedOperationException("custom iterate method is unsupported by SqlFileBackedStorage objects");
    }

    @Override
    public byte[] readBytes(int id) {
        Pair<String, byte[]> filenameAndKeyBytes = getEntryFilenameAndKey(id);
        String filename = filenameAndKeyBytes.first;
        byte[] aesKeyBlob = filenameAndKeyBytes.second;

        InputStream is = getFileInputStream(filename, aesKeyBlob);
        if (is == null) {
            throw new RuntimeException("Unable to open and decrypt file: " + filename);
        }

        return StreamsUtil.getStreamAsBytes(is);
    }

    private InputStream getFileInputStream(String filename, byte[] aesKeyBytes) {
        InputStream is;
        if (areFilesEncrypted) {
            SecretKeySpec aesKey = new SecretKeySpec(aesKeyBytes, "AES");
            is = EncryptionIO.getFileInputStream(filename, aesKey);
        } else {
            try {
                is = new FileInputStream(filename);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e.getMessage());
            }
        }
        return is;
    }

    @Override
    public void remove(int id) {
        SQLiteDatabase db;
        try {
            db = helper.getHandle();
        } catch (SessionUnavailableException e) {
            throw new UserStorageClosedException(e.getMessage());
        }
        db.beginTransaction();
        try {
            String filename = getEntryFilename(id);
            File dataFile = new File(filename);
            dataFile.delete();

            db.delete(table, DatabaseHelper.ID_COL + "=?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void remove(List<Integer> ids) {
        if (ids.size() == 0) {
            return;
        }
        SQLiteDatabase db;
        try {
            db = helper.getHandle();
        } catch (SessionUnavailableException e) {
            throw new UserStorageClosedException(e.getMessage());
        }
        db.beginTransaction();
        try {
            List<Pair<String, String[]>> whereParamList = AndroidTableBuilder.sqlList(ids);
            for (Pair<String, String[]> whereParams : whereParamList) {
                int rowsRemoved = db.delete(table, DatabaseHelper.ID_COL + " IN " + whereParams.first, whereParams.second);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void removeAll() {
        SQLiteDatabase db;
        try {
            db = helper.getHandle();
        } catch (SessionUnavailableException e) {
            throw new UserStorageClosedException(e.getMessage());
        }
        db.beginTransaction();
        try {
            db.delete(table, null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public Vector<Integer> removeAll(EntityFilter ef) {
        Vector<Integer> removed = new Vector<>();
        for (IStorageIterator iterator = this.iterate(); iterator.hasMore(); ) {
            int id = iterator.nextID();
            switch (ef.preFilter(id, null)) {
                case EntityFilter.PREFILTER_INCLUDE:
                    removed.add(id);
                    continue;
                case EntityFilter.PREFILTER_EXCLUDE:
                    continue;
                case EntityFilter.PREFILTER_FILTER:
                    if (ef.matches(read(id))) {
                        removed.add(id);
                    }
            }
        }

        if (removed.size() == 0) {
            return removed;
        }

        List<Pair<String, String[]>> whereParamList = AndroidTableBuilder.sqlList(removed);

        SQLiteDatabase db;
        try {
            db = helper.getHandle();
        } catch (SessionUnavailableException e) {
            throw new UserStorageClosedException(e.getMessage());
        }
        db.beginTransaction();
        try {
            for (Pair<String, String[]> whereParams : whereParamList) {
                db.delete(table, DatabaseHelper.ID_COL + " IN " + whereParams.first, whereParams.second);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        return removed;
    }

    @Override
    public void update(int id, Externalizable extObj) {
        SQLiteDatabase db;
        try {
            db = helper.getHandle();
        } catch (SessionUnavailableException sue) {
            throw new UserStorageClosedException(sue.getMessage());
        }
        db.beginTransaction();
        try {
            db.update(table, helper.getNonDataContentValues(extObj), DatabaseHelper.ID_COL + "=?", new String[]{String.valueOf(id)});

            String filename = getEntryFilename(id);
            if (areFilesEncrypted) {
                writePersitableToEncryptedFile(extObj, filename, getEntryAESKey(id));
            } else {
                writePersitableToFile(extObj, filename);
            }
            db.setTransactionSuccessful();
        } catch (IOException e) {
            // failed to update file
            // TODO PLM: what is the correct way to fail here?
            e.printStackTrace();
        } finally {
            db.endTransaction();
        }
    }

    private byte[] getEntryAESKey(int id) {
        Cursor c;
        try {
            c = helper.getHandle().query(table, new String[]{DatabaseHelper.ID_COL, DatabaseHelper.AES_COL}, DatabaseHelper.ID_COL + "=?", new String[]{String.valueOf(id)}, null, null, null);
        } catch (SessionUnavailableException e) {
            throw new UserStorageClosedException(e.getMessage());
        }

        try {
            c.moveToFirst();
            return c.getBlob(c.getColumnIndexOrThrow(DatabaseHelper.AES_COL));
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private Pair<String, byte[]> getEntryFilenameAndKey(int id) {
        Cursor c;
        try {
            c = helper.getHandle().query(table, new String[]{DatabaseHelper.ID_COL, DatabaseHelper.FILE_COL, DatabaseHelper.AES_COL}, DatabaseHelper.ID_COL + "=?", new String[]{String.valueOf(id)}, null, null, null);
        } catch (SessionUnavailableException e) {
            throw new UserStorageClosedException(e.getMessage());
        }

        try {
            c.moveToFirst();
            return new Pair<>(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.FILE_COL)),
                    c.getBlob(c.getColumnIndexOrThrow(DatabaseHelper.AES_COL)));
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private String getEntryFilename(int id) {
        Cursor c;
        try {
            c = helper.getHandle().query(table, new String[]{DatabaseHelper.ID_COL, DatabaseHelper.FILE_COL}, DatabaseHelper.ID_COL + "=?", new String[]{String.valueOf(id)}, null, null, null);
        } catch (SessionUnavailableException e) {
            throw new UserStorageClosedException(e.getMessage());
        }

        try {
            c.moveToFirst();
            return c.getString(c.getColumnIndexOrThrow(DatabaseHelper.FILE_COL));
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    @Override
    public void write(Persistable p) {
        if (p.getID() != -1) {
            update(p.getID(), p);
            return;
        }
        SQLiteDatabase db;
        try {
            db = helper.getHandle();
        } catch (SessionUnavailableException e) {
            throw new UserStorageClosedException(e.getMessage());
        }
        try {
            db.beginTransaction();
            File dataFile = newFileForEntry();
            ContentValues contentValues = helper.getNonDataContentValues(p);
            contentValues.put(DatabaseHelper.FILE_COL, dataFile.getAbsolutePath());
            SecretKey key = null;
            if (areFilesEncrypted) {
                try {
                    key = CommCareApplication._().createNewSymetricKey();
                } catch (SessionUnavailableException e) {
                    throw new RuntimeException("Session unavailable: Unable to generate encryption key.");
                }
                contentValues.put(DatabaseHelper.AES_COL, key.getEncoded());
            }
            long ret = db.insertOrThrow(table, DatabaseHelper.FILE_COL, contentValues);

            if (ret > Integer.MAX_VALUE) {
                throw new RuntimeException("Waaaaaaaaaay too many values");
            }

            p.setID((int)ret);

            if (areFilesEncrypted) {
                writePersitableToEncryptedFile(p, dataFile.getAbsolutePath(), key.getEncoded());
            } else {
                writePersitableToFile(p, dataFile.getAbsolutePath());
            }

            db.setTransactionSuccessful();
        } catch (IOException e) {
            // failed to create new file
            // TODO PLM: what is the correct way to fail here?
            e.printStackTrace();
        } finally {
            db.endTransaction();
        }
    }

    private void writePersitableToEncryptedFile(Externalizable externalizable,
                                                String filename,
                                                byte[] aesKeyBytes) throws IOException {
        DataOutputStream objectOutStream = null;
        SecretKeySpec aesKey = new SecretKeySpec(aesKeyBytes, "AES");
        try {
            objectOutStream = new DataOutputStream(EncryptionIO.createFileOutputStream(filename, aesKey));
            externalizable.writeExternal(objectOutStream);
        } finally {
            if (objectOutStream != null) {
                objectOutStream.close();
            }
        }
    }

    private void writePersitableToFile(Externalizable externalizable,
                                       String filename) throws IOException {
        DataOutputStream objectOutStream = null;
        try {
            objectOutStream =
                    new DataOutputStream(new FileOutputStream(filename));
            externalizable.writeExternal(objectOutStream);
        } finally {
            if (objectOutStream != null) {
                objectOutStream.close();
            }
        }
    }

    private File newFileForEntry() throws IOException {
        File newFile = getUniqueFilename();

        if (!newFile.createNewFile()) {
            throw new RuntimeException("Trying to create a new file using existing filename; " +
                    "Shouldn't be possible since we already checked for uniqueness");
        }

        return newFile;
    }

    private File getUniqueFilename() {
        String filename = DateTime.now().toString(DateTimeFormat.forPattern("MM_dd_yyyy_HH_mm_ss")) + "_" + (Math.random() * 5);
        File newFile = new File(dbDir, filename);
        while (newFile.exists()) {
            newFile = getUniqueFilename();
        }
        return newFile;
    }
}
