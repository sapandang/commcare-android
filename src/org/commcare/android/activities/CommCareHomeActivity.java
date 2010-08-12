package org.commcare.android.activities;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Vector;

import javax.crypto.SecretKey;

import org.commcare.android.R;
import org.commcare.android.application.CommCareApplication;
import org.commcare.android.database.SqlIndexedStorageUtility;
import org.commcare.android.logic.GlobalConstants;
import org.commcare.android.models.FormRecord;
import org.commcare.android.preferences.ServerPreferences;
import org.commcare.android.providers.EncryptedFileInputStream;
import org.commcare.android.providers.EncryptedFileOutputStream;
import org.commcare.android.providers.PreloadContentProvider;
import org.commcare.android.tasks.ProcessAndSendListener;
import org.commcare.android.tasks.ProcessAndSendTask;
import org.commcare.android.util.AndroidCommCarePlatform;
import org.commcare.android.util.CommCarePlatformProvider;
import org.commcare.android.util.FileUtil;
import org.commcare.suite.model.Entry;
import org.javarosa.core.services.storage.StorageFullException;
import org.javarosa.core.util.StreamUtil;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.Toast;

public class CommCareHomeActivity extends Activity implements ProcessAndSendListener {
	public static final int LOGIN_USER = 0;
	public static final int GET_COMMAND = 1;
	public static final int GET_CASE = 2;
	public static final int MODEL_RESULT = 4;
	public static final int INIT_APP = 8;
	
	public static final int DIALOG_PROCESS = 0;
	public static final int USE_OLD_DIALOG = 1;
	
	View homeScreen;
	
	private AndroidCommCarePlatform platform;
	
	ProgressDialog mProgressDialog;
	AlertDialog mAskOldDialog;
	
	ProcessAndSendTask mProcess;
	
	Button startButton;
	Button logoutButton;
	
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
    	
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        platform = CommCarePlatformProvider.unpack(savedInstanceState, this);
        
        // enter data button. expects a result.
        startButton = (Button) findViewById(R.id.start);
        startButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(getApplicationContext(), MenuList.class);
                Bundle b = new Bundle();
                
                CommCarePlatformProvider.pack(b, platform);
                i.putExtra(GlobalConstants.COMMCARE_PLATFORM, b);
                startActivityForResult(i, GET_COMMAND);

            }
        });
        
        logoutButton = (Button) findViewById(R.id.logout);
        logoutButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(getApplicationContext(), LoginActivity.class);
                
                CommCareHomeActivity.this.platform.clearState();
                CommCareHomeActivity.this.platform.logout();
                
                startActivityForResult(i, LOGIN_USER);
            }
        });
    }
    
    /*
     * (non-Javadoc)
     * 
     * @see android.app.Activity#onSaveInstanceState(android.os.Bundle)
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        platform.pack(outState);
    }
    
    /*
     * (non-Javadoc)
     * 
     * @see android.app.Activity#onSaveInstanceState(android.os.Bundle)
     */
    @Override
    protected void onRestoreInstanceState(Bundle inState) {
        super.onRestoreInstanceState(inState);
        platform.unpack(inState);
    }
   
    /*
     * (non-Javadoc)
     * @see android.app.Activity#onActivityResult(int, int, android.content.Intent)
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
    	switch(requestCode) {
    	case INIT_APP:
    		if(resultCode == RESULT_CANCELED) {
    			//quit somehow.
    			this.finish();
    			return;
    		} else if(resultCode == RESULT_OK) {
    			CommCareApplication._().initializeGlobalResources();
    			return;
    		}
    		break;
    	case LOGIN_USER:
    		if(resultCode == RESULT_CANCELED) {
    			//quit somehow.
    			this.finish();
    			return;
    		} else if(resultCode == RESULT_OK) {
    			platform.logInUser(intent.getStringExtra(GlobalConstants.STATE_USER_KEY));
    			refreshView();
    			return;
    		}
    		break;
    		
    	case GET_COMMAND:
    		if(resultCode == RESULT_CANCELED) {
    			//We were already deep into getting other state
    			if(platform.getCommand() != null) {
    				//In order of depth: Ref, Case.
    				if(platform.getCaseId() != null) {
    					platform.setCaseId(null);
    					break;
    				} else {
    	        		platform.setCommand(null);
    	        		break;
    				}
    			} else {
    				//We've got nothing useful, come home bill bailey.
        			refreshView();
        			return;
    			}
    		} else if(resultCode == RESULT_OK) {
    			//Get our command, set it, and continue forward
    			String command = intent.getStringExtra(GlobalConstants.STATE_COMMAND_ID);
    			platform.setCommand(command);
    			break;
    		}
        case GET_CASE:
        	if(resultCode == RESULT_CANCELED) {
        		platform.setCaseId(null);
        		platform.setCommand(null);
        		break;
    		} else if(resultCode == RESULT_OK) {
    			platform.setCaseId(intent.getStringExtra(GlobalConstants.STATE_CASE_ID));
    			if(intent.hasExtra(CallOutActivity.CALL_DURATION)) {
    				platform.setCallDuration(intent.getLongExtra(CallOutActivity.CALL_DURATION, 0));
    			}
    			break;
    		}
        case MODEL_RESULT:
        	if(resultCode == RESULT_OK) {
        		String instance = intent.getStringExtra("instancepath");
        		boolean completed = intent.getBooleanExtra("instancecomplete", true);
        		//intent.get
        		SqlIndexedStorageUtility<FormRecord> storage =  CommCareApplication._().getStorage(FormRecord.STORAGE_KEY, FormRecord.class);
        		
        		//First, check to see if there's already data we're updating
        		Vector<Integer> records = storage.getIDsForValue(FormRecord.META_PATH, instance);
        		FormRecord current;
    			String caseID = platform.getCaseId();
    			if(caseID == null) {
    				caseID = AndroidCommCarePlatform.ENTITY_NONE; 
    			}
        		if(records.size() > 0) {
        			current = storage.read(records.elementAt(0).intValue());
        		} else {
        			//Otherwise, we need to get the current record from the unstarted stub
        			Vector<Integer> unstarteds = storage.getIDsForValues(new String[] {FormRecord.META_XMLNS, FormRecord.META_ENTITY_ID, FormRecord.META_STATUS}, new Object[] {platform.getForm(), caseID, FormRecord.STATUS_UNSTARTED});
        			if(unstarteds.size() != 1) {
        				throw new RuntimeException("Invalid DB state upon returning from form entry"); 
        			}
        			current = storage.read(unstarteds.elementAt(0).intValue());
        		}
        		
        		if(completed) {
    				FormRecord r = new FormRecord(platform.getForm(), instance, caseID, FormRecord.STATUS_COMPLETE, current.getAesKey());
    				r.setID(current.getID());
    				try {
						storage.write(r);
					} catch (StorageFullException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
	        		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
	        		mProcess = new ProcessAndSendTask(this, settings.getString(ServerPreferences.KEY_SUBMIT, this.getString(R.string.default_submit_server)));
	        		mProcess.setListener(this);
	        		showDialog(DIALOG_PROCESS);
	        		mProcess.execute(r);
	        		refreshView();
        		} else {
        			
        			FormRecord r = new FormRecord(platform.getForm(), instance, caseID, FormRecord.STATUS_INCOMPLETE, current.getAesKey());
        			r.setID(current.getID());
        			
        			try {
						storage.write(r);
					} catch (StorageFullException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					
        			refreshView();
        		}
        		platform.clearState();
        		return;
    		} else {
    			platform.clearState();
    			refreshView();
        		break;
    		}
    	} 
    		
    	
    	String needed = platform.getNeededData();
    	if(needed == null) {
    		startFormEntry();
    	}
    	if(needed == GlobalConstants.STATE_CASE_ID) {
            Intent i = new Intent(getApplicationContext(), EntitySelectActivity.class);
            Bundle b = new Bundle();
            
            CommCarePlatformProvider.pack(b, platform);
            i.putExtra(GlobalConstants.COMMCARE_PLATFORM, b);
            startActivityForResult(i, GET_CASE);
    	} else if(needed == GlobalConstants.STATE_COMMAND_ID) {
            Intent i = new Intent(getApplicationContext(), MenuList.class);
            Bundle b = new Bundle();
            
            CommCarePlatformProvider.pack(b, platform);
            i.putExtra(GlobalConstants.COMMCARE_PLATFORM, b);
            i.putExtra(GlobalConstants.STATE_COMMAND_ID, platform.getCommand());
            startActivityForResult(i, GET_COMMAND);
    	}
    	
    	super.onActivityResult(requestCode, resultCode, intent);
    }
    
    private void startFormEntry() {
    	String command = platform.getCommand();
		
		Entry e = platform.getMenuMap().get(command);
		String xmlns = e.getXFormNamespace();
		String path = platform.getFormPath(xmlns);
		
		SqlIndexedStorageUtility<FormRecord> storage =  CommCareApplication._().getStorage(FormRecord.STORAGE_KEY, FormRecord.class);
		String caseID = platform.getCaseId();
		if(caseID == null) {
			caseID = AndroidCommCarePlatform.ENTITY_NONE; 
		}
		Vector<Integer> records = storage.getIDsForValues(new String[] {FormRecord.META_XMLNS, FormRecord.META_ENTITY_ID, FormRecord.META_STATUS}, new Object[] {xmlns, caseID, FormRecord.STATUS_INCOMPLETE});
		if(records.size() > 0 ) {
			FormRecord r = storage.read(records.elementAt(0));
			createAskUseOldDialog(path,r);
		} else {
			formEntry(path, null);
		}
		
	
    }
    
    private void formEntry(String formpath, FormRecord r) {
		PreloadContentProvider.initializeSession(platform, this);
		Intent i = new Intent("org.odk.collect.android.action.FormEntry");
		i.putExtra("formpath", formpath);
		i.putExtra("instancedestination", GlobalConstants.FILE_CC_SAVED);
		
		if(r == null) {
			
			String caseID = platform.getCaseId();
			if(caseID == null) {
				caseID = AndroidCommCarePlatform.ENTITY_NONE; 
			}
			
			SecretKey key = CommCareApplication._().createNewSymetricKey();
			r = new FormRecord(platform.getForm(), "",caseID, FormRecord.STATUS_UNSTARTED, key.getEncoded());
			SqlIndexedStorageUtility<FormRecord> storage =  CommCareApplication._().getStorage(FormRecord.STORAGE_KEY, FormRecord.class);
			
			//Make sure that there are no other unstarted definitions for this form/case, otherwise we won't be able to tell them apart unpon completion
			Vector<Integer> ids = storage.getIDsForValues(new String[] {FormRecord.META_XMLNS, FormRecord.META_ENTITY_ID, FormRecord.META_STATUS}, new Object[] {platform.getForm(),caseID, FormRecord.STATUS_UNSTARTED} );
			for(Integer recordId : ids) {
				storage.remove(recordId.intValue());
			}
			
			try {
				storage.write(r);
			} catch (StorageFullException e) {
				throw new RuntimeException(e);
			}
		}
		
		if(r.getPath() != "") {
			i.putExtra("instancepath", r.getPath());
		}
		i.putExtra("encryptionkey", r.getAesKey());
		i.putExtra("encryptionkeyalgo", "AES");
		
		String[] preloaders = new String[] {"case", PreloadContentProvider.CONTENT_URI_CASE + "/" + r.getEntityId() + "/", "meta", PreloadContentProvider.CONTENT_URI_META + "/"};
		i.putExtra("preloadproviders",preloaders);

		
		startActivityForResult(i, MODEL_RESULT);
    }
    
    /*
     * (non-Javadoc)
     * 
     * @see android.app.Activity#onResume()
     */
    @Override
    protected void onResume() {
        super.onResume();
        if(CommCareApplication._().getAppResourceState() != CommCareApplication.STATE_READY &&
                CommCareApplication._().getDatabaseState() != CommCareApplication.STATE_READY) {
     	        Intent i = new Intent(getApplicationContext(), CommCareStartupActivity.class);
     	        i.putExtra(CommCareStartupActivity.DATABASE_STATE, CommCareApplication._().getDatabaseState());
     	        i.putExtra(CommCareStartupActivity.RESOURCE_STATE, CommCareApplication._().getAppResourceState());
     	        
     	        
     	        this.startActivityForResult(i, INIT_APP);
             }
        else if(platform.getLoggedInUser() == null) {
        	Intent i = new Intent(getApplicationContext(), LoginActivity.class);
        	startActivityForResult(i,LOGIN_USER);
        } else {
        	refreshView();
        }
    }
    
    /*
     * (non-Javadoc)
     * 
     * @see android.app.Activity#onCreateDialog(int)
     */
    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
        case DIALOG_PROCESS:
                mProgressDialog = new ProgressDialog(this);
                mProgressDialog.setTitle("Processing Form");
                mProgressDialog.setMessage("Processing your Form");
                mProgressDialog.setIndeterminate(true);
                mProgressDialog.setCancelable(false);
                return mProgressDialog;
        }
        return null;
    }
    
    private void createAskUseOldDialog(final String formpath, final FormRecord r) {
        mAskOldDialog = new AlertDialog.Builder(this).create();
        mAskOldDialog.setTitle("Continue Form");
        mAskOldDialog.setMessage("You've got a saved copy of an incomplete form for this client. Do you want to continue filling out that form?");
        DialogInterface.OnClickListener useOldListener = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int i) {
                switch (i) {
                    case DialogInterface.BUTTON1: // yes, use old
                    	formEntry(formpath, r);
                        break;
                    case DialogInterface.BUTTON3: // no, create new
                    	formEntry(formpath, null);
                        break;
                    case DialogInterface.BUTTON2: // no, and delete the old one
                    	SqlIndexedStorageUtility<FormRecord> storage =  CommCareApplication._().getStorage(FormRecord.STORAGE_KEY, FormRecord.class);
                		storage.remove(r);
                		//How do we delete the saved record here?
                		//Find the parent folder and delete it, I guess?
                		try {
                			File f = new File(r.getPath());
                			if(!f.isDirectory() && f.getParentFile() != null) {
                				f = f.getParentFile();
                			}
                			FileUtil.deleteFile(f);
                		} catch(Exception e) {
                			e.printStackTrace();
                		}
                    	
                    	formEntry(formpath, null);
                        break;
                }
            }
        };
        mAskOldDialog.setCancelable(false);
        mAskOldDialog.setButton("Yes", useOldListener);
        mAskOldDialog.setButton2("Delete it", useOldListener);
        mAskOldDialog.setButton3("No", useOldListener);
        
        mAskOldDialog.show();
    }
    
    private void refreshView() {
    }

	public void processAndSendFinished(int result) {
		mProgressDialog.dismiss();
		if(result == ProcessAndSendTask.FULL_SUCCESS) {
			Toast.makeText(this, "Form Sent to Server!", Toast.LENGTH_LONG).show();
		} else {
			Toast.makeText(this, "Error in Sending! Will try again later.", Toast.LENGTH_LONG).show();
		}
		refreshView();
	}
}