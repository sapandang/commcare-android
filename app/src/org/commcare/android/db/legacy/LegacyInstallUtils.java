/**
 * 
 */
package org.commcare.android.db.legacy;

import java.io.File;
import java.io.FileNotFoundException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.Hashtable;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

import net.sqlcipher.database.SQLiteDatabase;

import org.commcare.android.crypt.CipherPool;
import org.commcare.android.crypt.CryptUtil;
import org.commcare.android.database.DbHelper;
import org.commcare.android.database.EncryptedModel;
import org.commcare.android.database.SqlIndexedStorageUtility;
import org.commcare.android.database.app.models.UserKeyRecord;
import org.commcare.android.database.global.models.ApplicationRecord;
import org.commcare.android.database.user.CommCareUserOpenHelper;
import org.commcare.android.database.user.models.ACase;
import org.commcare.android.database.user.models.FormRecord;
import org.commcare.android.database.user.models.GeocodeCacheModel;
import org.commcare.android.database.user.models.SessionStateDescriptor;
import org.commcare.android.database.user.models.User;
import org.commcare.android.javarosa.AndroidLogEntry;
import org.commcare.android.javarosa.AndroidLogger;
import org.commcare.android.javarosa.DeviceReportRecord;
import org.commcare.android.logic.GlobalConstants;
import org.commcare.android.util.SessionUnavailableException;
import org.commcare.dalvik.application.CommCareApp;
import org.commcare.dalvik.application.CommCareApplication;
import org.commcare.dalvik.odk.provider.InstanceProviderAPI.InstanceColumns;
import org.commcare.dalvik.preferences.CommCarePreferences;
import org.commcare.dalvik.services.CommCareSessionService;
import org.commcare.resources.model.Resource;
import org.javarosa.core.model.instance.FormInstance;
import org.javarosa.core.services.Logger;
import org.javarosa.core.services.storage.Persistable;
import org.javarosa.core.services.storage.StorageFullException;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.preference.PreferenceManager;

/**
 * @author ctsims
 *
 */
public class LegacyInstallUtils {
	
	public static final String LEGACY_UPGRADE_PROGRESS = "legacy_upgrade_progress";
	public static final String UPGRADE_COMPLETE = "complete";

	public static void checkForLegacyInstall(Context c, SqlIndexedStorageUtility<ApplicationRecord> currentAppStorage) throws StorageFullException {
		SharedPreferences globalPreferences = PreferenceManager.getDefaultSharedPreferences(c);
		if(globalPreferences.getString(LEGACY_UPGRADE_PROGRESS, "").equals(UPGRADE_COMPLETE)) { return; }
		//Check to see if the legacy database exists on this system
		if(!c.getDatabasePath(GlobalConstants.CC_DB_NAME).exists()) {
			globalPreferences.edit().putString(LEGACY_UPGRADE_PROGRESS, UPGRADE_COMPLETE).commit();
			Logger.log(AndroidLogger.TYPE_MAINTENANCE, "No legacy installs detected. Skipping transition");
			return;
		}
		
		//There is a legacy DB. First, check whether we've already moved things over (whether a new
		//app is already installed)
		int installedApps = currentAppStorage.getNumRecords();
		
		ApplicationRecord record = null;
		if(installedApps == 0 ) {
			//there are no installed apps
		} else if(installedApps == 1) {
			for(ApplicationRecord r : currentAppStorage ){
				int status = r.getStatus();
				if(status == ApplicationRecord.STATUS_SPECIAL_LEGACY) {
					//We've already started this process, but it didn't
					//finish
					record = r;
				}
			}
			
			//if the application present is either uninitialized or already
			//installed, we don't need to proceed.
			if(record == null) {
				globalPreferences.edit().putString(LEGACY_UPGRADE_PROGRESS, UPGRADE_COMPLETE).commit();
				Logger.log(AndroidLogger.TYPE_MAINTENANCE, "Legacy app was detected, but new install already covers it");
				return;
			}
			
		} else {
			//there's more than one app installed, which means we 
			//must have passed through here.
			globalPreferences.edit().putString(LEGACY_UPGRADE_PROGRESS, UPGRADE_COMPLETE).commit();
			Logger.log(AndroidLogger.TYPE_MAINTENANCE, "More than one app record installed, skipping legacy app detection");
			return;
		}
		
		//Ok, so now we have an old db, and either an incomplete app install, or
		//no app install.
		
		//Next step, determine if there was an old, completely installed app in the old db.
		//If this isn't true, we can just bail.
		
		
		//get the legacy storage
		final android.database.sqlite.SQLiteDatabase olddb = new LegacyCommCareOpenHelper(c).getReadableDatabase();
		LegacyDbHelper ldbh = new LegacyDbHelper(c) {
			@Override
			public android.database.sqlite.SQLiteDatabase getHandle() {
				return olddb;
			}
		};
		
		//Ok, so now we need to see whether there's app on the legacy db.
		LegacySqlIndexedStorageUtility<Resource> legacyResources = new LegacySqlIndexedStorageUtility<Resource>("GLOBAL_RESOURCE_TABLE", Resource.class, ldbh);
		
		//see if the resource table is installed
		boolean hasProfile = false;
		boolean allInstalled = true;
		int oldDbSize = 0;
		for(Resource r : legacyResources) {
			if(r.getStatus() != Resource.RESOURCE_STATUS_INSTALLED) {
				allInstalled = false;
			} 
			if(r.getResourceId().equals("commcare-application-profile")) {
				hasProfile = true;
			}
			oldDbSize++;
		}
		
		if(hasProfile && allInstalled) {
			//Legacy application is installed and ready to transition
		} else {
			globalPreferences.edit().putString(LEGACY_UPGRADE_PROGRESS, UPGRADE_COMPLETE).commit();
			Logger.log(AndroidLogger.TYPE_MAINTENANCE, "Legacy app detected, but it wasn't fully installed");
			return;
		}
		
		//So if we've gotten this far, we definitely have an app we need to copy over. See if we have an application record, and create
		//one if not.
		
		if(record == null) {
			record = new ApplicationRecord("legacy_application", ApplicationRecord.STATUS_SPECIAL_LEGACY);
		}
		//Commit? We can skip the searching for the old app if so, maybe?
		
		//Ok, so fire up a seat for the new Application
		CommCareApp app = new CommCareApp(record);
		app.setupSandbox();
		
		//Ok, so. We now have a valid application record and can start moving over records.
		//App data used to exist in three places, so we'll copy over all three
		//1) DB Records
		//2) File System Data
		//3) Update File system references
		//4) Application settings
		//5) Stubbed out user keys
		
		//1) DB Records
		//   The following models need to be moved: Resource Table entries and fixtures
		SqlIndexedStorageUtility<Resource> newInstallTable = app.getStorage("GLOBAL_RESOURCE_TABLE", Resource.class);
		SqlIndexedStorageUtility.cleanCopy(legacyResources, newInstallTable);
		
		//Fixtures
		LegacySqlIndexedStorageUtility<FormInstance> legacyFixtures = new LegacySqlIndexedStorageUtility<FormInstance>("fixture", FormInstance.class, ldbh);
		SqlIndexedStorageUtility<FormInstance> newFixtures = app.getStorage("fixture", FormInstance.class);
		SqlIndexedStorageUtility.cleanCopy(legacyFixtures, newFixtures);
		
		//TODO: Record Progress?
		
		
		//2) Ok, so now we want to copy over any old file system storage. Any jr:// prefixes should work fine after this move, 
		//but form records and such will still need to be updated. Unfortunately if people have placed their media in jr://file
		//instead of jr://file/commcare we're going to miss it here, which sucks, but otherwise we run the risk of breaking future
		//installs
		
		//Copy over the old file root
		File oldRoot = new File(getOldFileSystemRoot());
		oldRoot.renameTo(new File(app.fsPath("commcare/")));
		
		//3) Ok, so now we have app settings to copy over. Basically everything in the SharedPreferences should get put in the new app
		//preferences

		Map<String, ?> oldPrefs = globalPreferences.getAll();
		//well, this sucks more than anticipated.
		Editor e = app.getAppPreferences().edit();
		for(String k : oldPrefs.keySet()) {
			Object o = oldPrefs.get(k);
			if(o instanceof String) {
				e.putString(k, (String)o);
			} else if(o instanceof Integer) {
				e.putInt(k, (Integer)o);
			} else if(o instanceof Long) {
				e.putLong(k, (Long)o);
			} else if(o instanceof Boolean) {
				e.putBoolean(k, (Boolean)o);
			} else if(o instanceof Float) {
				e.putFloat(k, (Float)o);
			}
		}
		//4) Finally, we need to register a new UserKeyRecord which will prepare the user-facing records for transition
		//when the user logs in again
		
		
		SqlIndexedStorageUtility<UserKeyRecord> newUserKeyRecords = app.getStorage(UserKeyRecord.class);
		
		//Get legacy user storage
		LegacySqlIndexedStorageUtility<User> legacyUsers = new LegacySqlIndexedStorageUtility<User>("USER", User.class, ldbh);
		
		ArrayList<User> oldUsers = new ArrayList<User>();
		//So the old user storage wasn't encrypted since it had no actual prod data in it
		for(User u : legacyUsers) {
			oldUsers.add(u);
		}
		
		//we're done with the old storage now.
		olddb.close();
		
		User preferred = null;
		//go through all of the old users and generate key records for them
		for(User u : oldUsers) {
			//make sure we haven't already handled this user somehow
			if(newUserKeyRecords.getIDsForValue(UserKeyRecord.META_USERNAME, u.getUsername()).size() > 0 ) {
				continue;
			} 
			
			//See if we can find a user who was the last to log in
			if(preferred == null || u.getUsername().toLowerCase().equals(globalPreferences.getString(CommCarePreferences.LAST_LOGGED_IN_USER, "").toLowerCase())) {
				preferred = u;
			}
			
			//There's not specific reason to thing this might happen, but might be valuable to double check
			if(newUserKeyRecords.getIDsForValue(UserKeyRecord.META_USERNAME, u.getUsername()).size() == 0) {
				UserKeyRecord ukr = new UserKeyRecord(u.getUsername(), u.getPassword(), u.getWrappedKey(), new Date(0), new Date(Long.MAX_VALUE), u.getUniqueId(), UserKeyRecord.TYPE_LEGACY_TRANSITION);
				newUserKeyRecords.write(ukr);
			}
		}
		
		//First off: All of the app resources are now transitioned. We can continue to handle data transitions at login if the following fails
		app.writeInstalled();
		globalPreferences.edit().putString(LEGACY_UPGRADE_PROGRESS, UPGRADE_COMPLETE).commit();
		
		//Now, we should try to transition over legacy user storage if any of the previous users are on test data
		
		//First, see if the preferred user (Only one if available, or the most recent login)
		if(preferred != null) {
			//There should only be one user key record for that user which is legacy
			for(UserKeyRecord ukr : newUserKeyRecords.getRecordsForValues(new String[]{UserKeyRecord.META_USERNAME}, new String[]{preferred.getUsername()})) {
				if(ukr.getType() == UserKeyRecord.TYPE_LEGACY_TRANSITION) {
					try {
						transitionLegacyUserStorage(c, app, generateOldTestKey().getEncoded(), ukr );
					} catch(RuntimeException re){
						//expected if they used a real key
						re.printStackTrace();
					}
					break;
				}
			}
		}
		String toSkip = preferred == null ? null : preferred.getUsername();
		for(UserKeyRecord ukr : newUserKeyRecords) {
			if(ukr.getUsername().equals(toSkip)) {continue;}
			if(ukr.getType() == UserKeyRecord.TYPE_LEGACY_TRANSITION) {
				try {
					transitionLegacyUserStorage(c, app, generateOldTestKey().getEncoded(), ukr );
				} catch(RuntimeException re){
					//expected if they used a real key
					re.printStackTrace();
				}
				break;
			}
		}
		
		//Okay! Whew. We're now all set. Anything else that needs to happen should happen when the user logs in and we
		//can unwrap their keys
		app.teardownSandbox();
	}
	
	private static String getOldFileSystemRoot() {
		String filesystemHome = CommCareApplication._().getAndroidFsRoot();
		return filesystemHome + "commcare/";
	}

	public static void transitionLegacyUserStorage(final Context c, CommCareApp app, final byte[] oldKey, UserKeyRecord ukr) throws StorageFullException{
		try {
			final CipherPool pool = new CipherPool() {
				Object lock = new Object();
				byte[] key = oldKey;
	
				@Override
				public Cipher generateNewCipher() {
					synchronized(lock) {
						try {
							synchronized(key) {
								SecretKeySpec spec = new SecretKeySpec(key, "AES");
								Cipher decrypter = Cipher.getInstance("AES");
								decrypter.init(Cipher.DECRYPT_MODE, spec);
								
								return decrypter;
							}
						} catch (InvalidKeyException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (NoSuchAlgorithmException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (NoSuchPaddingException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
					return null;
				}
	        	
	        };
	        
	        
			//get the legacy storage
			final android.database.sqlite.SQLiteDatabase olddb = new LegacyCommCareOpenHelper(c, new LegacyCommCareDBCursorFactory(getLegacyEncryptedModels()) {
				protected CipherPool getCipherPool() throws SessionUnavailableException {
					return pool;
				}
			}).getReadableDatabase();
	        
			LegacyDbHelper ldbh = new LegacyDbHelper(c) {
				@Override
				public android.database.sqlite.SQLiteDatabase getHandle() {
					return olddb;
				}
			};
			
			final String newFileSystemRoot = app.storageRoot();
			final String oldRoot = getOldFileSystemRoot();
			
			LegacySqlIndexedStorageUtility<User> legacyUserStorage = new LegacySqlIndexedStorageUtility<User>("User", User.class, ldbh);
			try {
				//Test to see if the old db worked
				for(User u : legacyUserStorage) {
					
				}
			} catch(RuntimeException e) {
				//This almost certainly means that we don't have the right key;
				return;
			}
			
			//If we were able to iterate over the users, the key was fine, so let's use it to open our db
			final SQLiteDatabase currentUserDatabase = new CommCareUserOpenHelper(CommCareApplication._(), ukr.getUuid()).getWritableDatabase(CommCareSessionService.getKeyVal(oldKey));
			DbHelper newDbHelper = new DbHelper(c) {
				@Override
				public SQLiteDatabase getHandle() {
					return currentUserDatabase;
				}
			};
			
			try {
				
			//So we need to copy over a bunch of storage and also make some incidental changes along the way.
			
			SqlIndexedStorageUtility.cleanCopy(new LegacySqlIndexedStorageUtility<ACase>(ACase.STORAGE_KEY, ACase.class, ldbh),
					   new SqlIndexedStorageUtility<ACase>(ACase.STORAGE_KEY, ACase.class, newDbHelper));
			
			SqlIndexedStorageUtility.cleanCopy(legacyUserStorage,
					new SqlIndexedStorageUtility<User>("USER", User.class, newDbHelper));
			
			final Map<Integer, Integer> formRecordMapping = SqlIndexedStorageUtility.cleanCopy(new LegacySqlIndexedStorageUtility<FormRecord>("FORMRECORDS", FormRecord.class, ldbh),
					new SqlIndexedStorageUtility<FormRecord>("FORMRECORDS", FormRecord.class, newDbHelper), new CopyMapper<FormRecord>() {
	
						@Override
						public FormRecord transform(FormRecord t) {
							String formRecordPath;
							try {
								formRecordPath = t.getPath(c);
								String newPath = replaceOldRoot(formRecordPath, oldRoot, newFileSystemRoot);
								if(newPath != formRecordPath) {
									ContentValues cv = new ContentValues();
									cv.put(InstanceColumns.INSTANCE_FILE_PATH, newPath);
									c.getContentResolver().update(t.getInstanceURI(), cv, null, null);
								}
								return t;
							} catch(FileNotFoundException e) {
								//This means the form record doesn't
								//actually have a URI at all, so we 
								//can skip this.
								return t;
							}
						}
				
			});
			
			SqlIndexedStorageUtility.cleanCopy(new LegacySqlIndexedStorageUtility<SessionStateDescriptor>("android_cc_session", SessionStateDescriptor.class, ldbh),
					new SqlIndexedStorageUtility<SessionStateDescriptor>("android_cc_session", SessionStateDescriptor.class, newDbHelper), new CopyMapper<SessionStateDescriptor>() {
	
						@Override
						public SessionStateDescriptor transform(SessionStateDescriptor t) {
							return t.reMapFormRecordId(formRecordMapping.get(t.getFormRecordId()));
						}
				
			});
			
			SqlIndexedStorageUtility.cleanCopy(new LegacySqlIndexedStorageUtility<GeocodeCacheModel>(GeocodeCacheModel.STORAGE_KEY, GeocodeCacheModel.class, ldbh),
					new SqlIndexedStorageUtility<GeocodeCacheModel>(GeocodeCacheModel.STORAGE_KEY, GeocodeCacheModel.class, newDbHelper));
			
			SqlIndexedStorageUtility.cleanCopy(new LegacySqlIndexedStorageUtility<AndroidLogEntry>(AndroidLogEntry.STORAGE_KEY, AndroidLogEntry.class, ldbh),
					new SqlIndexedStorageUtility<AndroidLogEntry>(AndroidLogEntry.STORAGE_KEY, AndroidLogEntry.class, newDbHelper));
			
			
			SqlIndexedStorageUtility.cleanCopy(new LegacySqlIndexedStorageUtility<DeviceReportRecord>("log_records", DeviceReportRecord.class, ldbh),
						new SqlIndexedStorageUtility<DeviceReportRecord>("log_records", DeviceReportRecord.class, newDbHelper), new CopyMapper<DeviceReportRecord>() {
						@Override
						public DeviceReportRecord transform(DeviceReportRecord t) {
							return new DeviceReportRecord(replaceOldRoot(t.getFilePath(), oldRoot, newFileSystemRoot), t.getKey());
						}
				
			});
			
			SqlIndexedStorageUtility.cleanCopy(new LegacySqlIndexedStorageUtility<FormInstance>("fixture", FormInstance.class, ldbh),
					new SqlIndexedStorageUtility<FormInstance>("fixture", FormInstance.class, newDbHelper));
			
			} catch(StorageFullException sfe) {
				throw new RuntimeException(sfe);
			}
			
			//Now we can update this key record to confirm that it is fully installed
			ukr.setType(UserKeyRecord.TYPE_NORMAL);
			app.getStorage(UserKeyRecord.class).write(ukr);
			
			//Now, if we've copied everything over to this user with no problems, we want to actually go back and wipe out all of the
			//data that is linked to specific files, since individual users might delete them out of their sandboxes.
			new LegacySqlIndexedStorageUtility<DeviceReportRecord>("log_records", DeviceReportRecord.class, ldbh).removeAll();
			new LegacySqlIndexedStorageUtility<FormRecord>("FORMRECORDS", FormRecord.class, ldbh).removeAll();
			new LegacySqlIndexedStorageUtility<SessionStateDescriptor>("android_cc_session", SessionStateDescriptor.class, ldbh).removeAll();
			
			olddb.close();
			currentUserDatabase.close();
		} finally {
			
		}
	}
	
	protected static String replaceOldRoot(String filePath, String oldRoot, String newFileSystemRoot) {
		if(filePath.contains(oldRoot)) {
			return filePath.replace(oldRoot, newFileSystemRoot);
		}
		return filePath;
	}

	private static Hashtable<String, EncryptedModel> getLegacyEncryptedModels() {
		Hashtable<String, EncryptedModel> models = new Hashtable<String, EncryptedModel>();
		models.put(ACase.STORAGE_KEY, new ACase());
		models.put("FORMRECORDS", new FormRecord());
		models.put(GeocodeCacheModel.STORAGE_KEY, new GeocodeCacheModel());
		models.put("log_records", new DeviceReportRecord());
		return models;
	}
	
	public interface CopyMapper<T extends Persistable> {
		public T transform(T t);
	}

	private static SecretKeySpec generateOldTestKey() {
		KeyGenerator generator;
		try {
			generator = KeyGenerator.getInstance("AES");
			generator.init(256, new SecureRandom(CommCareApplication._().getPhoneId().getBytes()));
			return new SecretKeySpec(generator.generateKey().getEncoded(), "AES");
		} catch (NoSuchAlgorithmException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}
	
}
