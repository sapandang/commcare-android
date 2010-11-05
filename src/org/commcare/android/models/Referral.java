/*
 * Copyright (C) 2009 JavaRosa
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.commcare.android.models;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.Hashtable;

import org.commcare.android.database.EncryptedModel;
import org.javarosa.core.model.instance.FormInstance;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.model.util.restorable.Restorable;
import org.javarosa.core.model.util.restorable.RestoreUtils;
import org.javarosa.core.model.utils.DateUtils;
import org.javarosa.core.services.storage.IMetaData;
import org.javarosa.core.services.storage.Persistable;
import org.javarosa.core.util.externalizable.DeserializationException;
import org.javarosa.core.util.externalizable.ExtUtil;
import org.javarosa.core.util.externalizable.PrototypeFactory;

/**
 * A patient referral is a persistent record associated with a patient.
 * A referral represents that a patient was referred somewhere, and will
 * be followed up with to resolve the issue that the referral was issued
 * for. 
 * 
 * @author Clayton Sims
 * @date Jan 23, 2009 
 *
 */
public class Referral implements Persistable, IMetaData, EncryptedModel {
	public static final String STORAGE_KEY = "PATREFERRAL";
	public static final String REFERRAL_ID = "referralid";
	public static final String REFERRAL_TYPE = "referraltype";
	
	private String type;
	private String referralId;
	private String linkedId; //ID of patient or case this referral is linked to
	private Date createdOn;
	private Date dueOn;
	
	private int recordId;
	
	private boolean pending = true;
	
	/**
	 * NOTE: Should only be used for deserialization.
	 */
	public Referral() {
		
	}
	
	public Referral(String type, Date createdOn, String referralId, String linkedId) {
		this(type, createdOn, referralId, linkedId, null);
	}
	
	public Referral(String type, String referralId, String linkedId, int dueIn) {
		this(type, DateUtils.today(), referralId, linkedId, null);
		setDueInNDays(dueIn);
	}
		
	public Referral(String type, Date createdOn, String referralId, String linkedId, Date dueOn) {
		setID(-1);
		this.type = type;
		this.createdOn = createdOn;
		this.dueOn = dueOn;
		this.referralId = referralId;
		this.linkedId = linkedId;
	}

	/**
	 * @return the internal record ID of the patient that this referral is associated with
	 */
	public String getLinkedId() {
		return linkedId;
	}

	/**
	 * @return The Type of referral issued to the patient.
	 */
	public String getType() {
		return type;
	}

	/**
	 * @return the dateReferred
	 */
	public Date getDateCreated() {
		return createdOn;
	}

	public Date getDateDue () {
		return dueOn;
	}
	
	public void setDateDue (Date dueOn) {
		this.dueOn = dueOn;
	}
	
	public void setDueInNDays (int n) {
		this.dueOn = DateUtils.dateAdd(DateUtils.today(), n);
	}
	
	/**
	 * @return the referralId
	 */
	public String getReferralId() {
		return referralId;
	}
	
	public boolean isPending() {
		return this.pending;
	}
	
	public void close() {
		this.pending = false;
	}

	public void readExternal(DataInputStream in, PrototypeFactory pf)
			throws IOException, DeserializationException {
		this.type  = ExtUtil.readString(in);
		this.createdOn = ExtUtil.readDate(in);
		this.dueOn = ExtUtil.readDate(in);
		this.referralId = ExtUtil.readString(in);
		this.linkedId = ExtUtil.readString(in);
		this.recordId = ExtUtil.readInt(in);
		this.pending = ExtUtil.readBool(in);
	}

	public void writeExternal(DataOutputStream out) throws IOException {
		ExtUtil.writeString(out,type);
		ExtUtil.writeDate(out, createdOn);
		ExtUtil.writeDate(out, dueOn);
		ExtUtil.writeString(out, referralId);
		ExtUtil.writeString(out, linkedId);
		ExtUtil.writeNumeric(out, recordId);
		ExtUtil.writeBool(out, pending);
	}

	public void setID(int recordId) {
		this.recordId = recordId;
	}
	
	public int getID() {
		return recordId;
	}

	public Hashtable getMetaData() {
		Hashtable meta = new Hashtable();
		for(String key : getMetaDataFields()) {
			meta.put(key, getMetaData(key));
		}
		return meta;
	}

	public Object getMetaData(String fieldName) {
		if(REFERRAL_ID.equals(fieldName)) {
			return referralId;
		} else if(REFERRAL_TYPE.equals(fieldName)) {
			return type;
		} else {
			throw new IllegalArgumentException("No metadata field " + fieldName  + " in the case storage system");
		}
	}

	public String[] getMetaDataFields() {
		return new String[] {REFERRAL_ID, REFERRAL_TYPE};
	}

	public boolean isBlobEncrypted() {
		return true;
	}

	public boolean isEncrypted(String data) {
		//ref ID isn't encrypted here.
		return false;
	}

}
