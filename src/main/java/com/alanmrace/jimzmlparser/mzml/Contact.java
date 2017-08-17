package com.alanmrace.jimzmlparser.mzml;

import java.io.Serializable;
import java.util.ArrayList;

/**
 * Class describing a person of contact responsible for the mzML file.
 * 
 * @author Alan Race
 */
public class Contact extends MzMLContentWithParams implements Serializable {

    /**
     * Serialisation version ID.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Accession: Contact organisation (MS:1000590) [Required].
     */
    public static String contactOrganisationID = "MS:1000590"; // Required (1)

    /**
     * Accession: Contact name (MS:1000586) [Required].
     */
    public static String contactNameID = "MS:1000586"; // Required (1)

    /**
     * Accession: Contact person attribute (MS:1000585) [Optional].
     */
    public static String contactPersonAttributeID = "MS:1000585"; // Optional child (1+)

    /**
     * Default constructor.
     */
    public Contact() {
        super();
    }

    /**
     * Copy constructor.
     * 
     * @param contact Contact to copy
     * @param rpgList New ReferenceableParamGroupList to match references to
     */
    public Contact(Contact contact, ReferenceableParamGroupList rpgList) {
        super(contact, rpgList);
    }

//    @Override
//    public ArrayList<OBOTermInclusion> getListOfRequiredCVParams() {
//        ArrayList<OBOTermInclusion> required = new ArrayList<OBOTermInclusion>();
//        required.add(new OBOTermInclusion(contactNameID, true, false, true));
//        required.add(new OBOTermInclusion(contactOrganisationID, true, false, true));
//
//        return required;
//    }
//
//    @Override
//    public ArrayList<OBOTermInclusion> getListOfOptionalCVParams() {
//        ArrayList<OBOTermInclusion> optional = new ArrayList<OBOTermInclusion>();
//        optional.add(new OBOTermInclusion(contactPersonAttributeID, false, true, false));
//
//        return optional;
//    }

    @Override
    public String toString() {
        String name = getCVParam(contactNameID).getValueAsString();
        String organisation = getCVParam(contactOrganisationID).getValueAsString();

        return "contact: " + name + " (" + organisation + ")";
    }

    @Override
    public String getTagName() {
        return "contact";
    }
}
