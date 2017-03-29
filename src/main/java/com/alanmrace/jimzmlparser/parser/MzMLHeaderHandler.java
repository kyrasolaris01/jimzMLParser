package com.alanmrace.jimzmlparser.parser;

import com.alanmrace.jimzmlparser.exceptions.CVParamAccessionNotFoundException;
import com.alanmrace.jimzmlparser.exceptions.InvalidFormatIssue;
import com.alanmrace.jimzmlparser.exceptions.MissingReferenceIssue;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import com.alanmrace.jimzmlparser.mzML.*;
import com.alanmrace.jimzmlparser.obo.OBO;
import com.alanmrace.jimzmlparser.obo.OBOTerm;
import com.alanmrace.jimzmlparser.exceptions.InvalidMzML;
import com.alanmrace.jimzmlparser.exceptions.Issue;
import com.alanmrace.jimzmlparser.exceptions.MzMLParseException;
import com.alanmrace.jimzmlparser.exceptions.NonFatalParseException;
import com.alanmrace.jimzmlparser.exceptions.ObsoleteTermUsed;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public class MzMLHeaderHandler extends DefaultHandler {

    private static final Logger logger = Logger.getLogger(MzMLHeaderHandler.class.getName());

    protected Locator locator;

    protected OBO obo;
    protected MzML mzML;

    protected CVList cvList;
    protected FileDescription fileDescription;
    protected SourceFileList sourceFileList;
    protected ReferenceableParamGroupList referenceableParamGroupList;
    protected SampleList sampleList;
    protected SoftwareList softwareList;
    protected ScanSettingsList scanSettingsList;
    protected ScanSettings currentScanSettings;
    protected SourceFileRefList currentSourceFileRefList;
    protected TargetList currentTargetList;
    protected InstrumentConfigurationList instrumentConfigurationList;
    protected InstrumentConfiguration currentInstrumentConfiguration;
    protected ComponentList currentComponentList;
    protected DataProcessingList dataProcessingList;
    protected DataProcessing currentDataProcessing;
    protected Run run;
    protected SpectrumList spectrumList;
    protected Spectrum currentSpectrum;
    protected ScanList currentScanList;
    protected Scan currentScan;
    protected ScanWindowList currentScanWindowList;
    protected PrecursorList currentPrecursorList;
    protected Precursor currentPrecursor;
    protected SelectedIonList currentSelectedIonList;
    protected ProductList currentProductList;
    protected Product currentProduct;
    protected BinaryDataArrayList currentBinaryDataArrayList;
    protected BinaryDataArray currentBinaryDataArray;
    protected ChromatogramList chromatogramList;
    protected Chromatogram currentChromatogram;

    protected MzMLContent currentContent;

    // Flags for tags that share the same sub-tags
    protected boolean processingSpectrum;
    protected boolean processingChromatogram;

    protected boolean processingPrecursor;
    protected boolean processingProduct;

    protected boolean processingOffset;
    protected StringBuffer offsetData;
    protected String previousOffsetIDRef;
    protected String currentOffsetIDRef;
    protected long previousOffset = -1;

    protected DataStorage dataStorage;
    protected boolean openDataStorage = true;

    protected int numberOfSpectra = 0;

    protected List<ParserListener> listeners;

    protected MzMLHeaderHandler(OBO obo) {
        this.obo = obo;

        processingSpectrum = false;
        processingChromatogram = false;
        processingPrecursor = false;
        processingProduct = false;

        // Create a string buffer for storing the character offsets stored in indexed mzML
        offsetData = new StringBuffer();

        listeners = new LinkedList<ParserListener>();
    }

    @Override
    public void setDocumentLocator(Locator locator) {
        this.locator = locator;
    }

    public void setOpenDataStorage(boolean openDataStorage) {
        this.openDataStorage = openDataStorage;
    }

    public MzMLHeaderHandler(OBO obo, File mzMLFile) throws FileNotFoundException {
        this(obo, mzMLFile, true);
    }

    public MzMLHeaderHandler(OBO obo, File mzMLFile, boolean openDataFile) throws FileNotFoundException {
        this(obo);

        if (openDataFile) {
            this.dataStorage = new MzMLSpectrumDataStorage(mzMLFile);
        }
    }

    public void registerParserListener(ParserListener listener) {
        this.listeners.add(listener);
    }

    protected void notifyParserListeners(Issue issue) {
        for (ParserListener listener : listeners) {
            listener.issueFound(issue);
        }
    }

    public static MzML parsemzMLHeader(String filename, boolean openDataFile) throws MzMLParseException {
        try {
            OBO obo = new OBO("imagingMS.obo");

            // Parse mzML
            MzMLHeaderHandler handler = new MzMLHeaderHandler(obo, new File(filename), openDataFile);
            handler.setOpenDataStorage(openDataFile);

            SAXParserFactory spf = SAXParserFactory.newInstance();

            //get a new instance of parser
            SAXParser sp = spf.newSAXParser();

            File file = new File(filename);

            //parse the file and also register this class for call backs
            sp.parse(file, handler);

            handler.getmzML().setOBO(obo);

            return handler.getmzML();
        } catch (SAXException ex) {
            logger.log(Level.SEVERE, null, ex);

            throw new MzMLParseException("SAXException: " + ex);
        } catch (FileNotFoundException ex) {
            Logger.getLogger(MzMLHeaderHandler.class.getName()).log(Level.SEVERE, null, ex);

            throw new MzMLParseException("File not found: " + filename);
        } catch (IOException ex) {
            Logger.getLogger(MzMLHeaderHandler.class.getName()).log(Level.SEVERE, null, ex);

            throw new MzMLParseException("IOException: " + ex);
        } catch (ParserConfigurationException ex) {
            Logger.getLogger(MzMLHeaderHandler.class.getName()).log(Level.SEVERE, null, ex);

            throw new MzMLParseException("ParserConfigurationException: " + ex);
        }
    }

    public static MzML parsemzMLHeader(String filename) throws MzMLParseException {
        return parsemzMLHeader(filename, true);
    }

    protected int getCountAttribute(Attributes attributes) {
        String countString = attributes.getValue("count");
        int count;

        if (countString == null) {
            count = 0;
        } else {
            count = Integer.parseInt(countString);
        }

        return count;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {

        // Most common attribute at the start to reduce the number of comparisons needed
        if (qName.equals("cvParam")) {
            if (currentContent != null) {
                String accession = attributes.getValue("accession");

                OBOTerm term = obo.getTerm(accession);

                // If the term does not exist within any OBO, convert to UserParam for the sake of continuing parsing
                // Notify listeners of the fact that an issue occured and we attempted to resolve it
                if (term == null) {
                    UserParam userParam = new UserParam(attributes.getValue("accession"), attributes.getValue("value"), obo.getTerm(attributes.getValue("unitAccession")));
                    currentContent.addUserParam(userParam);

                    CVParamAccessionNotFoundException notFound = new CVParamAccessionNotFoundException(attributes.getValue("accession"));
                    notFound.fixAttempted(userParam);
                    notFound.setIssueLocation(currentContent);

                    notifyParserListeners(notFound);
                } else {
                    if(term.isObsolete()) {
                        ObsoleteTermUsed obsoleteIssue = new ObsoleteTermUsed(term);
                        obsoleteIssue.setIssueLocation(currentContent);

                        notifyParserListeners(obsoleteIssue);
                    }
                    
                    try {
                        CVParam.CVParamType paramType = CVParam.getCVParamType(term);
                        //System.out.println(term + " " + paramType);
                        //CVParam.CVParamType paramType = CVParam.getCVParamType(accession);
                        CVParam cvParam;

                        String value = attributes.getValue("value");
                        OBOTerm units = obo.getTerm(attributes.getValue("unitAccession"));

                        try {
                            if (paramType.equals(CVParam.CVParamType.String)) {
                                cvParam = new StringCVParam(term, value, units);
                            } else if (paramType.equals(CVParam.CVParamType.Empty)) {
                                cvParam = new EmptyCVParam(term, units);
                                
                                if(value != null) {
                                    InvalidFormatIssue formatIssue = new InvalidFormatIssue(term, attributes.getValue("value"));
                                    formatIssue.setIssueLocation(currentContent);

                                    notifyParserListeners(formatIssue);
                                }
                            } else if (paramType.equals(CVParam.CVParamType.Long)) {
                                cvParam = new LongCVParam(term, Long.parseLong(value), units);
                            } else if (paramType.equals(CVParam.CVParamType.Double)) {
                                cvParam = new DoubleCVParam(term, Double.parseDouble(value), units);
                            } else if (paramType.equals(CVParam.CVParamType.Boolean)) {
                                cvParam = new BooleanCVParam(term, Boolean.parseBoolean(value), units);
                            } else if (paramType.equals(CVParam.CVParamType.Integer)) {
                                cvParam = new IntegerCVParam(term, Integer.parseInt(value), units);
                            } else {
                                cvParam = new StringCVParam(term, attributes.getValue("value"), obo.getTerm(attributes.getValue("unitAccession")));

                                InvalidFormatIssue formatIssue = new InvalidFormatIssue(term, paramType);
                                formatIssue.fixAttemptedByChangingType((StringCVParam) cvParam);
                                formatIssue.setIssueLocation(currentContent);

                                notifyParserListeners(formatIssue);

                            }
                        } catch (NumberFormatException nfe) {
                            cvParam = new StringCVParam(term, attributes.getValue("value"), obo.getTerm(attributes.getValue("unitAccession")));

//                            notifyParserListeners(new NonFatalParseException("Failed value conversion " + nfe, nfe));                          
                            InvalidFormatIssue formatIssue = new InvalidFormatIssue(term, attributes.getValue("value"));
                            formatIssue.fixAttemptedByChangingType((StringCVParam) cvParam);
                            formatIssue.setIssueLocation(currentContent);

                            notifyParserListeners(formatIssue);
                        }

                        currentContent.addCVParam(cvParam);
                    } catch (NonFatalParseException ex) {
                        ex.setIssueLocation(currentContent);

                        notifyParserListeners(ex);

                        Logger.getLogger(MzMLHeaderHandler.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            } else {
                throw new InvalidMzML("<cvParam> tag without a parent.");
            }
        } else if (qName.equals("referenceableParamGroupRef")) {
            boolean foundReference = false;

            if (referenceableParamGroupList != null) {
                ReferenceableParamGroup group = referenceableParamGroupList.getReferenceableParamGroup(attributes.getValue("ref"));

                if (group != null) {
                    ReferenceableParamGroupRef ref = new ReferenceableParamGroupRef(group);

                    if (currentContent != null) {
                        foundReference = true;

                        currentContent.addReferenceableParamGroupRef(ref);
                    }
                }
            }

            if (!foundReference) {
                MissingReferenceIssue missingRefIssue = new MissingReferenceIssue(attributes.getValue("ref"), "referenceableParamGroupRef", "ref");
                missingRefIssue.setIssueLocation(currentContent);
                missingRefIssue.fixAttemptedByRemovingReference();

                notifyParserListeners(missingRefIssue);
            }
        } else if (qName.equals("userParam")) {
            if (currentContent != null) {
                UserParam userParam = new UserParam(attributes.getValue("name"));

                String type = attributes.getValue("type");
                if (type != null) {
                    userParam.setType(type);
                }
                String value = attributes.getValue("value");
                if (value != null) {
                    userParam.setValue(value);
                }

                userParam.setUnits(obo.getTerm(attributes.getValue("unitAccession")));

                currentContent.addUserParam(userParam);
            }
        } else if (qName.equalsIgnoreCase("mzML")) {
            mzML = new MzML(attributes.getValue("version"));

            mzML.setDataStorage(dataStorage);
            mzML.setOBO(obo);

            // Add optional attributes
            if (attributes.getValue("accession") != null) {
                mzML.setAccession(attributes.getValue("accession"));
            }

            if (attributes.getValue("id") != null) {
                mzML.setID(attributes.getValue("id"));
            }
        } else if (qName.equals("cvList")) {
            cvList = new CVList(Integer.parseInt(attributes.getValue("count")));

            try {
                mzML.setCVList(cvList);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<mzML> tag not defined prior to defining <cvList> tag.");
            }
        } else if (qName.equals("cv")) {
            CV cv = new CV(attributes.getValue("URI"), attributes.getValue("fullName"), attributes.getValue("id"));

            if (attributes.getValue("version") != null) {
                cv.setVersion(attributes.getValue("version"));
            }

            try {
                cvList.addCV(cv);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<cvList> tag not defined prior to defining <cv> tag.");
            }
        } else if (qName.equals("fileDescription")) {
            fileDescription = new FileDescription();

            mzML.setFileDescription(fileDescription);
        } else if (qName.equals("fileContent")) {
            FileContent fc = new FileContent();

            try {
                fileDescription.setFileContent(fc);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<fileDescription> tag not defined prior to defining <fileContent> tag.");
            }

            currentContent = fc;
        } else if (qName.equals("sourceFileList")) {
            sourceFileList = new SourceFileList(Integer.parseInt(attributes.getValue("count")));

            try {
                fileDescription.setSourceFileList(sourceFileList);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<fileDescription> tag not defined prior to defining <sourceFileList> tag.");
            }
        } else if (qName.equals("sourceFile")) {
            SourceFile sf = new SourceFile(attributes.getValue("id"), attributes.getValue("location"), attributes.getValue("name"));

            try {
                sourceFileList.addSourceFile(sf);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<sourceFileList> tag not defined prior to defining <sourceFile> tag.");
            }

            currentContent = sf;
        } else if (qName.equals("contact")) {
            Contact contact = new Contact();

            try {
                fileDescription.addContact(contact);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<fileDescription> tag not defined prior to defining <contact> tag.");
            }

            currentContent = contact;
        } else if (qName.equals("referenceableParamGroupList")) {
            referenceableParamGroupList = new ReferenceableParamGroupList(getCountAttribute(attributes));

            try {
                mzML.setReferenceableParamGroupList(referenceableParamGroupList);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<mzML> tag not defined prior to defining <referenceableParamGroupList> tag.");
            }
        } else if (qName.equals("referenceableParamGroup")) {
            ReferenceableParamGroup rpg = new ReferenceableParamGroup(attributes.getValue("id"));

            currentContent = rpg;

            try {
                referenceableParamGroupList.addReferenceableParamGroup(rpg);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<referenceableParamGroupList> tag not defined prior to defining <referenceableParamGroup> tag.");
            }
        } else if (qName.equals("sampleList")) {
            sampleList = new SampleList(getCountAttribute(attributes));

            try {
                mzML.setSampleList(sampleList);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<mzML> tag not defined prior to defining <sampleList> tag.");
            }
        } else if (qName.equals("sample")) {
            Sample sample = new Sample(attributes.getValue("id"));

            if (attributes.getValue("name") != null) {
                sample.setName(attributes.getValue("name"));
            }

            try {
                sampleList.addSample(sample);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<sampleList> tag not defined prior to defining <sample> tag.");
            }

            currentContent = sample;
        } else if (qName.equals("softwareList")) {
            softwareList = new SoftwareList(getCountAttribute(attributes));

            try {
                mzML.setSoftwareList(softwareList);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<mzML> tag not defined prior to defining <softwareList> tag.");
            }
        } else if (qName.equals("software")) {
            Software sw = new Software(attributes.getValue("id"), attributes.getValue("version"));

            try {
                softwareList.addSoftware(sw);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<softwareList> tag not defined prior to defining <software> tag.");
            }
            currentContent = sw;
        } else if (qName.equals("scanSettingsList")) {
            scanSettingsList = new ScanSettingsList(Integer.parseInt(attributes.getValue("count")));

            try {
                mzML.setScanSettingsList(scanSettingsList);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<mzML> tag not defined prior to defining <scanSettingsList> tag.");
            }
        } else if (qName.equals("scanSettings")) {
            currentScanSettings = new ScanSettings(attributes.getValue("id"));

            try {
                scanSettingsList.addScanSettings(currentScanSettings);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<scanSettingsList> tag not defined prior to defining <scanSettings> tag.");
            }

            currentContent = currentScanSettings;
        } else if (qName.equals("sourceFileRefList")) {
            currentSourceFileRefList = new SourceFileRefList(Integer.parseInt(attributes.getValue("count")));

            try {
                currentScanSettings.setSourceFileRefList(currentSourceFileRefList);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<scanSettings> tag not defined prior to defining <sourceFileRefList> tag.");
            }
        } else if (qName.equals("sourceFileRef")) {
            String ref = attributes.getValue("ref");

            boolean foundReference = false;

            if (sourceFileList != null) {
                SourceFile sourceFile = sourceFileList.getSourceFile(ref);

                if (sourceFile != null) {
                    currentSourceFileRefList.addSourceFileRef(new SourceFileRef(sourceFile));

                    foundReference = true;
                }
            }

            if (!foundReference) {
                MissingReferenceIssue missingRefIssue = new MissingReferenceIssue(attributes.getValue("ref"), "sourceFileRef", "ref");
                missingRefIssue.setIssueLocation(currentContent);
                missingRefIssue.fixAttemptedByRemovingReference();

                notifyParserListeners(missingRefIssue);
            }
        } else if (qName.equals("targetList")) {
            currentTargetList = new TargetList(Integer.parseInt(attributes.getValue("count")));

            try {
                currentScanSettings.setTargetList(currentTargetList);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<scanSettings> tag not defined prior to defining <targetList> tag.");
            }
        } else if (qName.equals("target")) {
            Target target = new Target();

            try {
                currentTargetList.addTarget(target);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<targetList> tag not defined prior to defining <target> tag.");
            }

            currentContent = target;
        } else if (qName.equals("instrumentConfigurationList")) {
            instrumentConfigurationList = new InstrumentConfigurationList(getCountAttribute(attributes));

            try {
                mzML.setInstrumentConfigurationList(instrumentConfigurationList);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<mzML> tag not defined prior to defining <instrumentConfigurationList> tag.");
            }
        } else if (qName.equals("instrumentConfiguration")) {
            currentInstrumentConfiguration = new InstrumentConfiguration(attributes.getValue("id"));

            String scanSettingsRef = attributes.getValue("scanSettingsRef");

            if (scanSettingsRef != null) {
                ScanSettings scanSettings = scanSettingsList.getScanSettings(scanSettingsRef);

                if (scanSettings != null) {
                    currentInstrumentConfiguration.setScanSettingsRef(scanSettings);
                } else {
                    //throw new InvalidMzML("Can't find scanSettingsRef '" + scanSettingsRef + "' on instrumentConfiguration '" + currentInstrumentConfiguration.getID() + "'");

                    MissingReferenceIssue missingRefIssue = new MissingReferenceIssue(scanSettingsRef, "instrumentConfiguration", "scanSettingsRef");
                    missingRefIssue.setIssueLocation(currentContent);
                    missingRefIssue.fixAttemptedByRemovingReference();

                    notifyParserListeners(missingRefIssue);
                }
            }

            try {
                instrumentConfigurationList.addInstrumentConfiguration(currentInstrumentConfiguration);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<instrumentConfigurationList> tag not defined prior to defining <instrumentConfiguration> tag.");
            }

            currentContent = currentInstrumentConfiguration;
        } else if (qName.equals("componentList")) {
            currentComponentList = new ComponentList();

            try {
                currentInstrumentConfiguration.setComponentList(currentComponentList);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<instrumentConfiguration> tag not defined prior to defining <componentList> tag.");
            }
        } else if (qName.equals("source")) {
            Source source = new Source(Integer.parseInt(attributes.getValue("order")));

            try {
                currentComponentList.addSource(source);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<componentList> tag not defined prior to defining <source> tag.");
            }

            currentContent = source;
        } else if (qName.equals("analyzer")) {
            Analyser analyser = new Analyser(Integer.parseInt(attributes.getValue("order")));

            try {
                currentComponentList.addAnalyser(analyser);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<componentList> tag not defined prior to defining <analyser> tag.");
            }

            currentContent = analyser;
        } else if (qName.equals("detector")) {
            Detector detector = new Detector(Integer.parseInt(attributes.getValue("order")));

            try {
                currentComponentList.addDetector(detector);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<componentList> tag not defined prior to defining <detector> tag.");
            }

            currentContent = detector;
        } else if (qName.equals("softwareRef")) {
            String softwareRef = attributes.getValue("ref");

            boolean foundReference = false;

            if (softwareList != null && currentInstrumentConfiguration != null) {
                Software software = softwareList.getSoftware(softwareRef);

                if (software != null) {
                    foundReference = true;

                    currentInstrumentConfiguration.setSoftwareRef(new SoftwareRef(software));
                }
            }

            if (!foundReference) {
                MissingReferenceIssue missingRefIssue = new MissingReferenceIssue(softwareRef, "softwareRef", "ref");
                missingRefIssue.setIssueLocation(currentContent);
                missingRefIssue.fixAttemptedByRemovingReference();

                notifyParserListeners(missingRefIssue);

                //logger.log(Level.WARNING, "Invalid mzML file - could not find softwareRef ''{0}''. Attempting to continue...", softwareRef);
                // TODO: Reinstate these checks
                //throw new InvalidMzML("Can't find softwareRef '" + softwareRef + "' in instrumentConfiguration '" + currentInstrumentConfiguration.getID() + "'");
            }
        } else if (qName.equals("dataProcessingList")) {
            dataProcessingList = new DataProcessingList(getCountAttribute(attributes));

            try {
                mzML.setDataProcessingList(dataProcessingList);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<mzML> tag not defined prior to defining <dataProcessingList> tag.");
            }
        } else if (qName.equals("dataProcessing")) {
            DataProcessing dp = new DataProcessing(attributes.getValue("id"));

            try {
                dataProcessingList.addDataProcessing(dp);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<dataProcessingList> tag not defined prior to defining <dataProcessing> tag.");
            }

            currentDataProcessing = dp;
            currentContent = dp;
        } else if (qName.equals("processingMethod")) {
            String softwareRef = attributes.getValue("softwareRef");

            Software software = null;

            try {
                software = softwareList.getSoftware(softwareRef);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<softwareList> tag not defined prior to defining <processingMethod> tag.");
            }

            if (software != null) {
                ProcessingMethod pm = new ProcessingMethod(Integer.parseInt(attributes.getValue("order")), software);

                try {
                    currentDataProcessing.addProcessingMethod(pm);
                } catch (NullPointerException ex) {
                    throw new InvalidMzML("<dataProcessing> tag not defined prior to defining <processingMethod> tag.");
                }

                currentContent = pm;
            } else {
                logger.log(Level.WARNING, "Invalid mzML file - could not find softwareRef ''{0}''. Attempting to continue...", softwareRef);

                // TODO: reininstate these checks
                //throw new InvalidMzML("Can't find softwareRef '" + softwareRef + "'");
            }
        } else if (qName.equals("run")) {
            String instrumentConfigurationRef = attributes.getValue("defaultInstrumentConfigurationRef");

            InstrumentConfiguration instrumentConfiguration = null;

            if (instrumentConfigurationRef != null && !instrumentConfigurationRef.isEmpty()) {
                try {
                    instrumentConfiguration = instrumentConfigurationList.getInstrumentConfiguration(instrumentConfigurationRef);
                } catch (NullPointerException ex) {
                    throw new InvalidMzML("<instrumentConfigurationList> tag not defined prior to defining <run> tag.");
                }
            }

            if (instrumentConfiguration != null) {
                run = new Run(attributes.getValue("id"), instrumentConfiguration);
            } else {
                MissingReferenceIssue missingRefIssue = new MissingReferenceIssue(instrumentConfigurationRef, "run", "defaultInstrumentConfigurationRef");
                missingRefIssue.setIssueLocation(currentContent);

                // TODO: Workaround only in place because of ABSciex converter bug where 
                // the defaultInstrumentConfigurationRef is auto-incremented in every raster
                // line file but the instrumentConfiguration id remains as 'instrumentConfiguration1'					
                if (currentInstrumentConfiguration != null) {
                    //logger.log(Level.WARNING, "Invalid mzML file - could not find instrumentConfigurationRef ''{0}''. Attempting to continue...", instrumentConfigurationRef);
                    missingRefIssue.fixAttemptedByChangingReference(currentInstrumentConfiguration);

                    run = new Run(attributes.getValue("id"), currentInstrumentConfiguration);
                } else {
                    //logger.log(Level.WARNING, "Invalid mzML file - could not find instrumentConfigurationRef ''{0}''. Attempting to continue...", instrumentConfigurationRef);
                    missingRefIssue.fixAttemptedByRemovingReference();

                    run = new Run(attributes.getValue("id"), null);
                    //throw new InvalidMzML("Can't find instrumentConfigurationRef '" + instrumentConfigurationRef + "'");
                }

                notifyParserListeners(missingRefIssue);
            }

            String defaultSourceFileRef = attributes.getValue("defaultSourceFileRef");

            if (defaultSourceFileRef != null) {
                boolean foundRef = false;

                if (sourceFileList != null) {
                    SourceFile sourceFile = sourceFileList.getSourceFile(defaultSourceFileRef);

                    if (sourceFile != null) {
                        run.setDefaultSourceFileRef(sourceFile);
                        foundRef = true;
                    }
                }

                if (!foundRef) {
                    MissingReferenceIssue missingRefIssue = new MissingReferenceIssue(defaultSourceFileRef, "run", "defaultSourceFileRef");
                    missingRefIssue.setIssueLocation(currentContent);
                    missingRefIssue.fixAttemptedByRemovingReference();

                    notifyParserListeners(missingRefIssue);
                }
            }

            String sampleRef = attributes.getValue("sampleRef");

            if (sampleRef != null) {
                boolean foundRef = false;

                if (sampleList != null) {
                    Sample sample = sampleList.getSample(sampleRef);

                    if (sample != null) {
                        foundRef = true;
                        run.setSampleRef(sample);
                    }
                }

                if (!foundRef) {
                    MissingReferenceIssue missingRefIssue = new MissingReferenceIssue(sampleRef, "run", "sampleRef");
                    missingRefIssue.setIssueLocation(currentContent);
                    missingRefIssue.fixAttemptedByRemovingReference();

                    notifyParserListeners(missingRefIssue);
                }
            }

            String startTimeStamp = attributes.getValue("startTimeStamp");

            if (startTimeStamp != null) {
                Date parsed = new Date();

                try {
                    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                    parsed = format.parse(startTimeStamp);

                    run.setStartTimeStamp(parsed);
                } catch (ParseException pe) {
                    //throw new IllegalArgumentException();
                    InvalidFormatIssue formatIssue = new InvalidFormatIssue("startTimeStamp", "yyyy-MM-dd'T'HH:mm:ss", startTimeStamp);
                    formatIssue.setIssueLocation(currentContent);

                    notifyParserListeners(formatIssue);
                }
            }

            try {
                mzML.setRun(run);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<mzML> tag not defined prior to defining <run> tag.");
            }

            currentContent = run;
        } else if (qName.equals("spectrumList")) {
            String defaultDataProcessingRef = attributes.getValue("defaultDataProcessingRef");
            DataProcessing dataProcessing = null;
            
            boolean foundRef = false;
            
            if (defaultDataProcessingRef != null && dataProcessingList != null) {
                dataProcessing = dataProcessingList.getDataProcessing(defaultDataProcessingRef);

                if (dataProcessing != null) {
                    numberOfSpectra = Integer.parseInt(attributes.getValue("count"));
                    foundRef = true;
                }
            }
            
            if(!foundRef) {
                MissingReferenceIssue refIssue = new MissingReferenceIssue(defaultDataProcessingRef, "spectrumList", "defaultDataProcessingRef");
                refIssue.setIssueLocation(currentContent);
                refIssue.fixAttemptedByRemovingReference();

                notifyParserListeners(refIssue);

                // msconvert doesn't include default data processing so try and fix it
                //throw new InvalidMzML("No defaultProcessingRef attribute in spectrumList.");
                //spectrumList = new SpectrumList(Integer.parseInt(attributes.getValue("count")), dataProcessingList.getDataProcessing(0));
            }
            
            spectrumList = new SpectrumList(numberOfSpectra, dataProcessing);

            if (run == null) {
                throw new InvalidMzML("<run> tag not defined prior to defining <spectrumList> tag.");
            }

            run.setSpectrumList(spectrumList);
        } else if (qName.equals("spectrum")) {
            currentSpectrum = new Spectrum(attributes.getValue("id"), Integer.parseInt(attributes.getValue("defaultArrayLength")), Integer.parseInt(attributes.getValue("index")));

            String dataProcessingRef = attributes.getValue("dataProcessingRef");
            
            if (dataProcessingRef != null) {
                boolean foundRef = false;
                
                if(dataProcessingList != null) {
                    DataProcessing dataProcessing = dataProcessingList.getDataProcessing(dataProcessingRef);

                    if (dataProcessing != null) {
                        currentSpectrum.setDataProcessingRef(dataProcessing);
                        foundRef = true;
                    }
                }
                
                if(!foundRef) {
                    MissingReferenceIssue refIssue = new MissingReferenceIssue(dataProcessingRef, "spectrum", "dataProcessingRef");
                    refIssue.setIssueLocation(currentContent);
                    refIssue.fixAttemptedByRemovingReference();

                    notifyParserListeners(refIssue);
                }
            }

            String sourceFileRef = attributes.getValue("sourceFileRef");

            if (sourceFileRef != null) {
                boolean foundRef = false;
                
                if(sourceFileList != null) {
                    SourceFile sourceFile = sourceFileList.getSourceFile(sourceFileRef);

                    if (sourceFile != null) {
                        currentSpectrum.setSourceFileRef(sourceFile);
                        foundRef = true;
                    }
                }
                
                if(!foundRef) {
                    MissingReferenceIssue refIssue = new MissingReferenceIssue(sourceFileRef, "spectrum", "sourceFileRef");
                    refIssue.setIssueLocation(currentContent);
                    refIssue.fixAttemptedByRemovingReference();

                    notifyParserListeners(refIssue);
                }
            }

            if (attributes.getValue("spotID") != null) {
                currentSpectrum.setSpotID(attributes.getValue("spotID"));
            }

            processingSpectrum = true;

            try {
                spectrumList.addSpectrum(currentSpectrum);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<spectrumList> tag not defined prior to defining <spectrum> tag.");
            }

            currentContent = currentSpectrum;
        } else if (qName.equals("scanList")) {
            currentScanList = new ScanList(Integer.parseInt(attributes.getValue("count")));

            try {
                currentSpectrum.setScanList(currentScanList);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<spectrum> tag not defined prior to defining <scanList> tag.");
            }

            currentContent = currentScanList;
        } else if (qName.equals("scan")) {
            currentScan = new Scan();

            if (attributes.getValue("externalSpectrumID") != null) {
                currentScan.setExternalSpectrumID(attributes.getValue("externalSpectrumID"));
            }

            String instrumentConfigurationRef = attributes.getValue("instrumentConfigurationRef");

            if (instrumentConfigurationRef != null) {
                InstrumentConfiguration instrumentConfiguration = null;

                try {
                    instrumentConfiguration = instrumentConfigurationList.getInstrumentConfiguration(instrumentConfigurationRef);
                } catch (NullPointerException ex) {
                    throw new InvalidMzML("<instrumentConfigurationList> tag not defined prior to defining <scan> tag.");
                }

                if (instrumentConfiguration != null) {
                    currentScan.setInstrumentConfigurationRef(instrumentConfiguration);
                } else {
                    MissingReferenceIssue refIssue = new MissingReferenceIssue(instrumentConfigurationRef, "scan", "instrumentConfigurationRef");
                    refIssue.setIssueLocation(currentContent);
                    
                    // TODO: Workaround only in place because of ABSciex converter bug where 
                    // the defaultInstrumentConfigurationRef is auto-incremented in every raster
                    // line file but the instrumentConfiguration id remains as 'instrumentConfiguration1'					
                    if (currentInstrumentConfiguration != null) {
                        currentScan.setInstrumentConfigurationRef(currentInstrumentConfiguration);
                        refIssue.fixAttemptedByChangingReference(currentInstrumentConfiguration);
                    } else {
                        refIssue.fixAttemptedByRemovingReference();
                        //throw new InvalidMzML("Can't find instrumentConfigurationRef '" + instrumentConfigurationRef + "' referenced in scan.");
                    }
                    
                    notifyParserListeners(refIssue);
                }
            } else {
                InstrumentConfiguration defaultIC = run.getDefaultInstrumentConfiguration();

                if (defaultIC != null) {
                    currentScan.setInstrumentConfigurationRef(defaultIC);
                }
            }

            String sourceFileRef = attributes.getValue("sourceFileRef");

            if (sourceFileRef != null) {
                boolean foundRef = false;
                
                if(sourceFileList != null) {
                    SourceFile sourceFile = sourceFileList.getSourceFile(sourceFileRef);

                    if (sourceFile != null) {
                        currentScan.setSourceFileRef(sourceFile);
                        foundRef = true;
                    }
                }
                
                if(!foundRef) {
                    MissingReferenceIssue refIssue = new MissingReferenceIssue(sourceFileRef, "scan", "sourceFileRef");
                    refIssue.setIssueLocation(currentContent);
                    refIssue.fixAttemptedByRemovingReference();

                    notifyParserListeners(refIssue);
                }
            }

            if (attributes.getValue("spectrumRef") != null) {
                currentScan.setSpectrumRef(attributes.getValue("spectrumRef"));
            }

            try {
                currentScanList.addScan(currentScan);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<scanList> tag not defined prior to defining <scan> tag.");
            }

            currentContent = currentScan;
        } else if (qName.equals("scanWindowList")) {
            currentScanWindowList = new ScanWindowList(Integer.parseInt(attributes.getValue("count")));

            try {
                currentScan.setScanWindowList(currentScanWindowList);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<scan> tag not defined prior to defining <scanWindowList> tag.");
            }
        } else if (qName.equals("scanWindow")) {
            ScanWindow scanWindow = new ScanWindow();

            try {
                currentScanWindowList.addScanWindow(scanWindow);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<scanWindowList> tag not defined prior to defining <scanWindow> tag.");
            }

            currentContent = scanWindow;
        } else if (qName.equals("precursorList")) {
            currentPrecursorList = new PrecursorList(Integer.parseInt(attributes.getValue("count")));

            try {
                currentSpectrum.setPrecursorList(currentPrecursorList);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<spectrum> tag not defined prior to defining <precursorList> tag.");
            }
        } else if (qName.equals("precursor")) {
            processingPrecursor = true;
            currentPrecursor = new Precursor();

            if (attributes.getValue("externalSpectrumID") != null) {
                currentPrecursor.setExternalSpectrumID(attributes.getValue("externalSpectrumID"));
            }

            String sourceFileRef = attributes.getValue("sourceFileRef");

            if (sourceFileRef != null) {
                boolean foundRef = false;
                
                if(sourceFileList != null) {
                    SourceFile sourceFile = sourceFileList.getSourceFile(sourceFileRef);

                    if (sourceFile != null) {
                        foundRef = true;
                        currentPrecursor.setSourceFileRef(sourceFile);
                    }
                }
                
                if(!foundRef) {
                    MissingReferenceIssue refIssue = new MissingReferenceIssue(sourceFileRef, "precursor", "sourceFileRef");
                    refIssue.setIssueLocation(currentContent);
                    refIssue.fixAttemptedByRemovingReference();

                    notifyParserListeners(refIssue);
                }
            }

            if (attributes.getValue("spectrumRef") != null) {
                currentPrecursor.setSpectrumRef(attributes.getValue("spectrumRef"));
            }

            if (processingSpectrum) {
                try {
                    currentPrecursorList.addPrecursor(currentPrecursor);
                } catch (NullPointerException ex) {
                    throw new InvalidMzML("<precursorList> tag not defined prior to defining <precursor> tag.");
                }
            } else if (processingChromatogram) {
                try {
                    currentChromatogram.setPrecursor(currentPrecursor);
                } catch (NullPointerException ex) {
                    throw new InvalidMzML("<chromatogram> tag not defined prior to defining <precursor> tag.");
                }
            }
        } else if (qName.equals("isolationWindow")) {
            IsolationWindow isolationWindow = new IsolationWindow();

            if (processingPrecursor) {
                try {
                    currentPrecursor.setIsolationWindow(isolationWindow);
                } catch (NullPointerException ex) {
                    throw new InvalidMzML("<precursor> tag not defined prior to defining <isolationWindow> tag.");
                }
            } else if (processingProduct) {
                try {
                    currentProduct.setIsolationWindow(isolationWindow);
                } catch (NullPointerException ex) {
                    throw new InvalidMzML("<product> tag not defined prior to defining <isolationWindow> tag.");
                }
            }

            currentContent = isolationWindow;
        } else if (qName.equals("selectedIonList")) {
            currentSelectedIonList = new SelectedIonList(Integer.parseInt(attributes.getValue("count")));

            try {
                currentPrecursor.setSelectedIonList(currentSelectedIonList);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<precursor> tag not defined prior to defining <selectedIonList> tag.");
            }
        } else if (qName.equals("selectedIon")) {
            SelectedIon selectedIon = new SelectedIon();

            try {
                currentSelectedIonList.addSelectedIon(selectedIon);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<selectedIonList> tag not defined prior to defining <selectedIon> tag.");
            }

            currentContent = selectedIon;
        } else if (qName.equals("activation")) {
            Activation activation = new Activation();

            try {
                currentPrecursor.setActivation(activation);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<precursor> tag not defined prior to defining <activation> tag.");
            }

            currentContent = activation;
        } else if (qName.equals("productList")) {
            currentProductList = new ProductList(Integer.parseInt(attributes.getValue("count")));

            try {
                currentSpectrum.setProductList(currentProductList);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<spectrum> tag not defined prior to defining <productList> tag.");
            }
        } else if (qName.equals("product")) {
            processingProduct = true;
            currentProduct = new Product();

            if (processingSpectrum) {
                try {
                    currentProductList.addProduct(currentProduct);
                } catch (NullPointerException ex) {
                    throw new InvalidMzML("<productList> tag not defined prior to defining <product> tag.");
                }
            } else if (processingChromatogram) {
                try {
                    currentChromatogram.setProduct(currentProduct);
                } catch (NullPointerException ex) {
                    throw new InvalidMzML("<chromatogram> tag not defined prior to defining <product> tag.");
                }
            }
        } else if (qName.equals("binaryDataArrayList")) {
            currentBinaryDataArrayList = new BinaryDataArrayList(Integer.parseInt(attributes.getValue("count")));

            if (processingSpectrum) {
                try {
                    currentSpectrum.setBinaryDataArrayList(currentBinaryDataArrayList);
                } catch (NullPointerException ex) {
                    throw new InvalidMzML("<spectrum> tag not defined prior to defining <binaryDataArrayList> tag.");
                }
            } else if (processingChromatogram) {
                try {
                    currentChromatogram.setBinaryDataArrayList(currentBinaryDataArrayList);
                } catch (NullPointerException ex) {
                    throw new InvalidMzML("<chromatogram> tag not defined prior to defining <binaryDataArrayList> tag.");
                }
            }
        } else if (qName.equals("binaryDataArray")) {
            currentBinaryDataArray = new BinaryDataArray(Integer.parseInt(attributes.getValue("encodedLength")));

            if (attributes.getValue("arrayLength") != null) {
                currentBinaryDataArray.setArrayLength(Integer.parseInt(attributes.getValue("arrayLength")));
            }

            String dataProcessingRef = attributes.getValue("dataProcessingRef");

            if (dataProcessingRef != null) {
                boolean foundRef = false;
                DataProcessing dataProcessing = null;

                if(dataProcessingList != null) {
                    dataProcessing = dataProcessingList.getDataProcessing(dataProcessingRef);

                    if (dataProcessing != null) {
                        foundRef = true;
                        currentBinaryDataArray.setDataProcessingRef(dataProcessing);
                    }
                }
                
                if(!foundRef) {
                    MissingReferenceIssue refIssue = new MissingReferenceIssue(dataProcessingRef, "binaryDataArray", "dataProcessingRef");
                    refIssue.setIssueLocation(currentContent);
                    refIssue.fixAttemptedByRemovingReference();

                    notifyParserListeners(refIssue);
                }
            }

            try {
                currentBinaryDataArrayList.addBinaryDataArray(currentBinaryDataArray);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<binaryDataArrayList> tag not defined prior to defining <binaryDataArray> tag.");
            }

            currentContent = currentBinaryDataArray;
        } else if (qName.equals("binary")) {
            // Ignore binary data for the header
        } else if (qName.equals("chromatogramList")) {
            String defaultDataProcessingRef = attributes.getValue("defaultDataProcessingRef");

            if (defaultDataProcessingRef != null) {
                DataProcessing dataProcessing = null;

                try {
                    dataProcessing = dataProcessingList.getDataProcessing(defaultDataProcessingRef);
                } catch (NullPointerException ex) {
                    throw new InvalidMzML("<dataProcessingList> tag not defined prior to defining <chromatogramList> tag.");
                }

                if (dataProcessing != null) {
                    chromatogramList = new ChromatogramList(Integer.parseInt(attributes.getValue("count")), dataProcessing);
                } else {
                    throw new InvalidMzML("Can't find defaultDataProcessingRef '" + defaultDataProcessingRef + "' referenced by chromatogramList.");
                }
            } else {
                // msconvert doesn't include default data processing so try and fix it				
                throw new InvalidMzML("No defaultProcessingRef attribute in chromatogramList.");

                //chromatogramList = new ChromatogramList(Integer.parseInt(attributes.getValue("count")), dataProcessingList.getDataProcessing(0));
            }

            try {
                run.setChromatogramList(chromatogramList);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<run> tag not defined prior to defining <chromatogramList> tag.");
            }
        } else if (qName.equals("chromatogram")) {
            processingChromatogram = true;
            currentChromatogram = new Chromatogram(attributes.getValue("id"), Integer.parseInt(attributes.getValue("defaultArrayLength")), Integer.parseInt(attributes.getValue("index")));

            String dataProcessingRef = attributes.getValue("dataProcessingRef");

            if (dataProcessingRef != null) {
                DataProcessing dataProcessing = null;

                try {
                    dataProcessing = dataProcessingList.getDataProcessing(dataProcessingRef);
                } catch (NullPointerException ex) {
                    throw new InvalidMzML("<dataProcessingList> tag not defined prior to defining <chromatogram> tag.");
                }

                if (dataProcessing != null) {
                    currentChromatogram.setDataProcessingRef(dataProcessing);
                } else {
                    throw new InvalidMzML("Can't find dataProcessingRef '" + dataProcessingRef + "' referenced by chromatogram '" + currentChromatogram.getID() + "'.");
                }
            }

            try {
                chromatogramList.addChromatogram(currentChromatogram);
            } catch (NullPointerException ex) {
                throw new InvalidMzML("<chromatogramList> tag not defined prior to defining <chromatogram> tag.");
            }

            currentContent = currentChromatogram;
        } else if (qName.equals("offset") || qName.equals("indexListOffset")) {
            previousOffsetIDRef = currentOffsetIDRef;

//            logger.log(Level.INFO, "Current qName: {0} - {1}", new String[] {qName, attributes.getValue("idRef")});
            if (qName.equals("offset")) {
                this.currentOffsetIDRef = attributes.getValue("idRef");
            }

            offsetData.setLength(0);
            processingOffset = true;
        } else if (qName.equals("index")) {
            if (attributes.getValue("name").equals("chromatogram")) {
                this.processingChromatogram = true;
//                this.processingSpectrum = false;
            } else {
                this.processingSpectrum = true;
//                this.processingChromatogram = false;
            }
        } else if (qName.equals("indexedmzML") || qName.equals("indexList") || qName.equals("indexListOffset")) {
        } else {
            logger.log(Level.FINEST, "No processing for tag <{0}>", qName);
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        if (processingOffset) {
            offsetData.append(ch, start, length);
        }
    }

    protected MzMLDataContainer getDataContainer() {
        MzMLDataContainer dataContainer = null;

        // There is probably a better way to do this - store a HashMap of IDs and 
        // locations, then at the end of the file, sort the HashMap by location
        // and then assign the DataLocation
        if (processingSpectrum) {
            dataContainer = spectrumList.getSpectrum(previousOffsetIDRef);

            if (dataContainer == null) {
                dataContainer = spectrumList.getSpectrum(spectrumList.size() - 1);
            }
        } else {
            dataContainer = chromatogramList.getChromatogram(previousOffsetIDRef);

            if (dataContainer == null) {
                dataContainer = chromatogramList.getChromatogram(chromatogramList.size() - 1);
            }
        }

        return dataContainer;
    }

    protected long getOffset() {
        return Long.parseLong(offsetData.toString());
    }

    protected void setDataContainer(MzMLDataContainer dataContainer, long offset) {
        //System.out.println(previousOffset + " " + spectrum);		    
        if (previousOffset != -1) {
            if (openDataStorage && dataContainer != null) {
                DataLocation dataLocation = new DataLocation(dataStorage, previousOffset, (int) (offset - previousOffset));

//                            if(dataContainer.getID().equals("TIC"))
//                                System.out.println(dataLocation);
                //    System.out.println("DataLocation: " + dataLocation);
                dataContainer.setDataLocation(dataLocation);
            }

            //    System.out.println(previousOffsetIDRef + " " + dataLocation);
            //    System.out.println(spectrum.getDataLocation());
            //    System.out.println(spectrum.getBinaryDataArrayList().getBinaryDataArray(0));
            //    System.out.println(run.getSpectrumList().size());
            //    System.out.println(run.getSpectrumList().getSpectrum(0).getBinaryDataArrayList().getBinaryDataArray(0));
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        if (qName.equals("spectrum")) {
            processingSpectrum = false;
        } else if (qName.equals("chromatogram")) {
            processingChromatogram = false;
        } else if (qName.equals("precursor")) {
            processingPrecursor = false;
        } else if (qName.equals("product")) {
            processingProduct = false;
        } else if (qName.equals("binaryDataArrayList")) {
            currentBinaryDataArrayList.updatemzAndIntensityArray();
        } else if (qName.equals("offset") || qName.equals("indexListOffset")) {
            long offset = getOffset();

            MzMLDataContainer dataContainer = getDataContainer();

            if (processingSpectrum && processingChromatogram) {
                processingSpectrum = false;
            }

            setDataContainer(dataContainer, offset);

            previousOffset = offset;
            processingOffset = false;
//                case "mzML":
//                    for(Spectrum curSpectrum : spectrumList) {
//                        System.out.println(curSpectrum.getDataLocation());
//                        try {
//                            curSpectrum.getmzArray();
//                        } catch (IOException ex) {
//                            Logger.getLogger(MzMLHeaderHandler.class.getName()).log(Level.SEVERE, null, ex);
//                        }
//                    }
//                    
//                    break;
        }
    }

//	public int getSpectrumCount() {
//		return spectrumCount;
//	}
    public MzML getmzML() {
        return mzML;
    }

    public static void main(String[] args) {
        String filename = "/home/alan/workspace/imzMLConverter/RianRasterLine.mzML";
        File mzMLFile = new File(filename);
        File temporaryBinaryFile = new File(filename + ".tmp");

        OBO obo = new OBO("imagingMS.obo");
        MzMLHandler headerHandler;
        try {
            headerHandler = new MzMLHandler(obo, temporaryBinaryFile);

            SAXParserFactory sspf = SAXParserFactory.newInstance();

            //get a new instance of parser
            SAXParser sp = sspf.newSAXParser();

            //parse the file and also register this class for call backs
            sp.parse(mzMLFile, headerHandler);

            String encoding = "ISO-8859-1";

            OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream("RianTest.mzML"), encoding);
            BufferedWriter output = new BufferedWriter(out);

            //writer = new FileWriter(imzMLFilename + ".imzML");
            //			System.out.println(out.getEncoding() + " - " + xo.getFormat().getEncoding());
            //			xo.output(new Document(mzMLElement), out);
            output.write("<?xml version=\"1.0\" encoding=\"" + encoding + "\"?>\n");
            headerHandler.getmzML().outputXML(output, 0);

            output.flush();
            output.close();

            //temporaryBinaryFile.delete();
        } catch (FileNotFoundException e) {
            logger.log(Level.SEVERE, null, e);
        } catch (SAXException ex) {
            Logger.getLogger(MzMLHeaderHandler.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(MzMLHeaderHandler.class.getName()).log(Level.SEVERE, null, ex);
        } catch (ParserConfigurationException ex) {
            Logger.getLogger(MzMLHeaderHandler.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
