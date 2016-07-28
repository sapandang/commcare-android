package org.commcare.provider;

import android.content.Intent;
import android.support.annotation.NonNull;
import android.util.Base64;
import android.util.Log;

import com.simprints.libsimprints.Constants;
import com.simprints.libsimprints.FingerIdentifier;
import com.simprints.libsimprints.Identification;
import com.simprints.libsimprints.Registration;
import com.simprints.libsimprints.Tier;

import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.instance.AbstractTreeElement;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.services.locale.Localization;
import org.javarosa.core.util.OrderedHashtable;
import org.commcare.android.javarosa.IntentCallout;

import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Vector;

/**
 * Process simprints fingerprint registration and identification callout results.
 *
 * @author Phillip Mates (pmates@dimagi.com).
 */
public class SimprintsCalloutProcessing {
    private static final String TAG = SimprintsCalloutProcessing.class.getSimpleName();
    private static final String RIGHT_INDEX_XPATH_KEY = "rightIndex";
    private static final String RIGHT_THUMB_XPATH_KEY = "rightThumb";
    private static final String LEFT_INDEX_XPATH_KEY = "leftIndex";
    private static final String LEFT_THUMB_XPATH_KEY = "leftThumb";

    /**
     * Fingerprint lookup response from Simprints scanner app.
     * Idenficiation responses contain a list of top matching Case IDs with an
     * associated confidence score.
     */
    public static boolean isIdentificationResponse(Intent intent) {
        return intent.hasExtra(Constants.SIMPRINTS_IDENTIFICATIONS);
    }

    /**
     * Fingerprint registration response from Simprints scanner app.
     * Registration responses contain fingerprint templates for the scanned fingerprints
     */
    public static boolean isRegistrationResponse(Intent intent) {
        return intent.hasExtra(Constants.SIMPRINTS_REGISTRATION);
    }

    public static OrderedHashtable<String, String> getConfidenceMatchesFromCalloutResponse(Intent intent) {
        List<Identification> idReadings = (List)intent.getParcelableArrayListExtra(Constants.SIMPRINTS_IDENTIFICATIONS);

        Collections.sort(idReadings);

        OrderedHashtable<String, String> guidToConfidenceMap = new OrderedHashtable<>();
        for (Identification id : idReadings) {
            guidToConfidenceMap.put(id.getGuid(), getTierText(id.getTier()));
        }

        return guidToConfidenceMap;
    }

    private static String getTierText(@NonNull Tier tier) {
        switch (tier) {
            case TIER_1:
                return Localization.get("fingerprint.match.100");
            case TIER_2:
                return Localization.get("fingerprint.match.75");
            case TIER_3:
                return Localization.get("fingerprint.match.50");
            case TIER_4:
                return Localization.get("fingerprint.match.25");
            case TIER_5:
                return Localization.get("fingerprint.match.0");
            default:
                return Localization.get("fingerprint.match.unknown");
        }
    }

    private static Registration getRegistrationData(Intent intent) {
        return intent.getParcelableExtra(Constants.SIMPRINTS_REGISTRATION);
    }

    public static boolean processRegistrationResponse(FormDef formDef, Intent intent, TreeReference intentQuestionRef,
                                                      Hashtable<String, Vector<TreeReference>> responseToRefMap) {
        Registration registration = getRegistrationData(intent);

        Vector<TreeReference> rightIndexRefs = responseToRefMap.get(RIGHT_INDEX_XPATH_KEY);
        Vector<TreeReference> rightThumbRefs = responseToRefMap.get(RIGHT_THUMB_XPATH_KEY);
        Vector<TreeReference> leftIndexRefs = responseToRefMap.get(LEFT_INDEX_XPATH_KEY);
        Vector<TreeReference> leftThumbRefs = responseToRefMap.get(LEFT_THUMB_XPATH_KEY);
        int numOfFingersScanned = getFingerprintScanCount(registration);

        String resultMessage =
                Localization.get("fingerprints.scanned", new String[]{"" + numOfFingersScanned});
        IntentCallout.setNodeValue(formDef, intentQuestionRef, resultMessage);

        if (rightIndexRefs != null && !rightIndexRefs.isEmpty() &&
                rightThumbRefs != null && !rightThumbRefs.isEmpty() &&
                leftIndexRefs != null && !leftIndexRefs.isEmpty() &&
                leftThumbRefs != null && !leftThumbRefs.isEmpty()) {
            storeFingerprintTemplate(formDef, rightIndexRefs, intentQuestionRef, registration.getTemplate(FingerIdentifier.RIGHT_INDEX_FINGER));
            storeFingerprintTemplate(formDef, rightThumbRefs, intentQuestionRef, registration.getTemplate(FingerIdentifier.RIGHT_THUMB));
            storeFingerprintTemplate(formDef, leftIndexRefs, intentQuestionRef, registration.getTemplate(FingerIdentifier.LEFT_INDEX_FINGER));
            storeFingerprintTemplate(formDef, leftThumbRefs, intentQuestionRef, registration.getTemplate(FingerIdentifier.LEFT_THUMB));
            return true;
        } else {
            return false;
        }
    }

    private static int getFingerprintScanCount(Registration registration) {
        return countTemplateScanned(registration.getTemplate(FingerIdentifier.LEFT_INDEX_FINGER))
                + countTemplateScanned(registration.getTemplate(FingerIdentifier.RIGHT_INDEX_FINGER))
                + countTemplateScanned(registration.getTemplate(FingerIdentifier.LEFT_THUMB))
                + countTemplateScanned(registration.getTemplate(FingerIdentifier.RIGHT_THUMB));
    }

    private static int countTemplateScanned(byte[] template) {
        if (template == null || template.length == 0) {
            return 0;
        } else {
            return 1;
        }
    }

    private static void storeFingerprintTemplate(FormDef formDef, Vector<TreeReference> treeRefs,
                                                 TreeReference contextRef, byte[] digitTemplate) {
        for (TreeReference ref : treeRefs) {
            storeFingerprintTemplateAtReference(formDef, ref, contextRef, digitTemplate);
        }
    }

    private static void storeFingerprintTemplateAtReference(FormDef formDef, TreeReference ref,
                                                            TreeReference contextRef,
                                                            byte[] digitTemplate) {
        EvaluationContext context = new EvaluationContext(formDef.getEvaluationContext(), contextRef);
        TreeReference fullRef = ref.contextualize(contextRef);
        AbstractTreeElement node = context.resolveReference(fullRef);

        if (node == null) {
            Log.e(TAG, "Unable to resolve ref " + ref);
            return;
        }
        int dataType = node.getDataType();

        IntentCallout.setValueInFormDef(formDef, fullRef, Base64.encodeToString(digitTemplate, Base64.DEFAULT), dataType);
    }
}
