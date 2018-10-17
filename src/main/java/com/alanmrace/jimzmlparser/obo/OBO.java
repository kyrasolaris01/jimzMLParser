package com.alanmrace.jimzmlparser.obo;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class to store an ontology database (including dependents) loaded from OBO format.
 * 
 * @author Alan Race
 */
public class OBO implements Serializable {

    /** Class logger. */
    private static final Logger logger = Logger.getLogger(OBO.class.getName());

    /** Serial version ID. */
    private static final long serialVersionUID = 1L;

    /** Location of the PSI MS ontology. */
    public static final String MS_OBO_URI = "https://raw.githubusercontent.com/HUPO-PSI/psi-ms-CV/master/psi-ms.obo";
    public static final String MS_OBO_FULLNAME = "Proteomics Standards Initiative Mass Spectrometry Ontology";
    
    /** Location of the Units ontology. */
    public static final String UO_OBO_URI = "http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/phenotype/unit.obo";
    public static final String UO_OBO_FULLNAME = "Units of Measurement Ontology";
    
    public static final String PATO_OBO_FULLNAME = "Phenotype And Trait Ontology";
    public static final String PATO_OBO_URI = "https://raw.githubusercontent.com/pato-ontology/pato/master/pato.obo";
    
    /** Location of the MSI ontology. */
    public static final String IMS_OBO_URI = "https://raw.githubusercontent.com/imzML/imzML/development/imagingMS.obo";
    /** Full name of the MSI ontology. */
    public static final String IMS_OBO_FULLNAME = "Mass Spectrometry Imaging Ontology";
    /** Shorthand identifier for the MSI ontology. */
    public static final String IMS_OBO_ID = "IMS";
    /** Version number for the MSI ontology. */
    public static final String IMS_OBO_VERSION = "???";
    
    /** Path to the OBO file. */
    private String path;

    /** List of imported ontologies. */
    private List<OBO> imports;
    
    private String defaultNamespace;
    
    private String ontologyIdentifier;
    
    private String dataVersion;

    /** Dictionary of ontology terms, using their ID as the key. */
    private Map<String, OBOTerm> terms;
    
    /**
     * Singleton OBO instance.
     */
    protected static OBO ONTOLOGY;

    /**
     * Generate ontology database from the specified .obo file. 
     * If the obo file specifies imports, then load those imports from resources
     * associated with the project. The path of the import is ignored, with only 
     * the final name considered as the location of the resource.
     * 
     * @param location Location of the .obo file to load the ontology from.
     * @param loader Means of loading the OBO
     */
    private OBO(String location, OBOLoader loader) throws IOException {
        imports = new ArrayList<OBO>();
        terms = new HashMap<String, OBOTerm>();

        InputStreamReader isr = new InputStreamReader(loader.getInputStream(location));
        BufferedReader in = new BufferedReader(isr);

        String curLine;
        OBOTerm curTerm = null;
        boolean processingTerms = false;

        try {
            while ((curLine = in.readLine()) != null) {
                // Skip empty lines
                if (curLine.trim().isEmpty()) {
                    continue;
                }

                if (curLine.trim().equals("[Term]")) {
                    // Process a term
                    processingTerms = true;

                    // Get the ID
                    curLine = in.readLine();
                    // TODO: Requires that the first tag in the term is the ID tag 
                    
                    if(curLine != null) {
	                    int indexOfColon = curLine.indexOf(':');
	                    String id = curLine.substring(indexOfColon + 1).trim();
	
	                    curTerm = new OBOTerm(this, id);
	
	                    terms.put(id, curTerm);
                    }
                } else if (curLine.trim().equals("[Typedef]")) {
                    processingTerms = false;
                } else if (curTerm != null && processingTerms) {
                    curTerm.parse(curLine);
                } else {
                    // TODO: Add in header information
                    int locationOfColon = curLine.indexOf(':');
                    String tag = curLine.substring(0, locationOfColon).trim();
                    String value = curLine.substring(locationOfColon + 1).trim().toLowerCase();

                    if("import".equals(tag)) {
                        imports.add(new OBO(value, loader));
                    } else if("default-namespace".equals(tag)) {
                        defaultNamespace = value;
                    } else if("ontology".equals(tag)) {
                        ontologyIdentifier = value;
                    } else if("data-version".equals(tag)) {
                        dataVersion = value;
                    }
                }
            }
        } catch (IOException ex) {
            Logger.getLogger(OBO.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        // Process relationships
        for (OBOTerm term : terms.values()) {
            Collection<String> is_a = term.getIsA();

            if(is_a != null) {
                for (String id : is_a) {
                    OBOTerm parentTerm = getTerm(id);

                    if (parentTerm == null) {
                        System.err.println("Haven't found " + id);
                    } else {
                        parentTerm.addChild(term);
                        term.addParent(parentTerm);
                    }
                }
                
                term.clearIsA();
            }

            // Units
            if(term.unitList != null) {
                for(String unitName : term.unitList) {
                    term.addUnit(getTerm(unitName));
                }
                
                term.unitList = null;
            }
        }
    }

    /**
     * Static function to load in the imagingMS.obo file stored as a project resource.
     * 
     * @return Loaded ontology
     */
    public static OBO getOBO() {
        if(ONTOLOGY == null)
            try {
                logger.log(Level.FINER, "Trying to load OBO from files");
                ONTOLOGY = OBO.loadOntologyFromFile(IMS_OBO_URI);
            } catch (IOException ex) {
                try {
                    logger.log(Level.FINER, "Trying to load OBO from URL");
                    ONTOLOGY = OBO.loadOntologyFromURL(IMS_OBO_URI);
                } catch (IOException ex1) {
                    logger.log(Level.FINER, "Trying to load OBO from resource");

                    try {
                        ONTOLOGY = OBO.loadOntologyFromResource(IMS_OBO_URI);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

        return ONTOLOGY;
    }

    public static void downloadOBO(String oboLocation) throws IOException {
        InputStream in = new URL(oboLocation).openStream();

        String filename = oboLocation.substring(oboLocation.lastIndexOf('/') + 1);

        installOBO(in, filename);

        in.close();
    }

    public static String ONTOLOGIES_FOLDER = "Ontologies";

    public static void installOBO(InputStream in, String filename) throws IOException {
        File ontologiesFolder = new File(ONTOLOGIES_FOLDER);

        if(!ontologiesFolder.exists())
            ontologiesFolder.mkdir();

        File file = new File(ontologiesFolder, filename);

        FileOutputStream out = new FileOutputStream(file);
        byte[] buffer = new byte[1024];
        int len;

        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }

        out.close();
    }

    public static void setOBO(OBO obo) {
        ONTOLOGY = obo;
    }

    public static OBO loadOntologyFromURL(String url) throws IOException {
        return new OBO(url, new HTTPOBOLoader());
    }

    public static OBO loadOntologyFromResource(String resource) throws IOException {
        return new OBO(resource, new ResourceOBOLoader());
    }

    public static OBO loadOntologyFromFile(String file) throws IOException {
        return new OBO(file, new FileOBOLoader());
    }
    
    /**
     * Return all imported ontologys.
     * 
     * @return List of imported ontologys
     */
    public List<OBO> getImports() {
        return this.imports;
    }
    
    public List<OBO> getFullImportHeirarchy() {
        List<OBO> fullList = new ArrayList<OBO>();
        
        for(OBO importedOBO : this.imports) {
            fullList.addAll(importedOBO.getFullImportHeirarchy());
        }
        
        fullList.add(this);
        
        return fullList;
    }
    
    /**
     * Return a complete list of ontology terms present within this ontolgoy.
     * 
     * @return All ontology terms
     */
    public Collection<OBOTerm> getTerms() {
        return this.terms.values();
    }

    /**
     * Get the term from the ontology with the ID id.
     * If no term is found with exactly the id specified, then return null.
     * 
     * @param id ID of the ontology term
     * @return Ontology term if found, null otherwise
     */
    public final OBOTerm getTerm(String id) {
        if (id == null) {
            return null;
        }

        OBOTerm term = terms.get(id);

        if (term == null) {
            for (OBO parent : imports) {
                term = parent.getTerm(id);

                if (term != null) {
                    break;
                }
            }
        }

        return term;
    }

    public String getPath() {
        return path;
    }
    
    public String getDefaultNamespace() {
        return defaultNamespace;
    }
    
    public String getOntology() {
        return ontologyIdentifier;
    }
    
    public String getDataVersion() {
        return dataVersion;
    }
    
    public OBO getOBOWithID(String id) {
        if(this.ontologyIdentifier.equalsIgnoreCase(id))
            return this;
        
        OBO foundOBO = null;
        
        for(OBO importedOBO : imports) {            
            foundOBO = importedOBO.getOBOWithID(id);
            
            if(foundOBO != null)
                break;
        }
        
        return foundOBO;
    }
    
    @Override
    public String toString() {
        return path;
    }
    
    public static String getNameFromID(String id) {
        if("IMS".equals(id)) {
            return OBO.IMS_OBO_FULLNAME;
        } else if("MS".equals(id)) {
            return OBO.MS_OBO_FULLNAME;
        } else if("UO".equals(id)) {
            return OBO.UO_OBO_FULLNAME;
        } else if("PATO".equals(id)) {
            return OBO.PATO_OBO_FULLNAME;
        }
        
        return id;
    }
}
