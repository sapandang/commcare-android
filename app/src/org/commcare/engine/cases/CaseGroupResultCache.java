package org.commcare.engine.cases;

import android.util.SparseArray;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.IntSet;

import org.commcare.cases.model.Case;
import org.commcare.cases.query.QueryCache;
import org.commcare.cases.query.QueryCacheEntry;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Vector;

/**
 * Created by ctsims on 1/25/2017.
 */

public class CaseGroupResultCache implements QueryCacheEntry {

    HashMap<String,IntSet> bulkFetchBodies = new HashMap<>();

    IntObjectMap<Case> cachedCases = new IntObjectHashMap<>();


    public void reportBulkCaseBody(String key, IntSet ids) {
        if(bulkFetchBodies.containsKey(key)) {
            return;
        }
        bulkFetchBodies.put(key, ids);
    }

    public boolean hasMatchingCaseSet(int recordId) {
        if(isLoaded(recordId)) {
            return true;
        }
        if(getTranche(recordId) != null) {
            return true;
        }
        return false;
    }

    public IntSet getTranche(int recordId) {
        for(IntSet tranche: bulkFetchBodies.values()) {
            if(tranche.contains(recordId)){
                return tranche;
            }
        }
        return null;
    }

    public boolean isLoaded(int recordId) {
        return cachedCases.containsKey(recordId);
    }

    public IntObjectMap<Case> getLoadedCaseMap() {
        return cachedCases;
    }

    public Case getLoadedCase(int recordId) {
        return cachedCases.get(recordId);
    }
}
