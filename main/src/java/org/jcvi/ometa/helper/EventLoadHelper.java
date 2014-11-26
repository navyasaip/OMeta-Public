package org.jcvi.ometa.helper;

import org.apache.commons.io.FileUtils;
import org.jcvi.ometa.bean_interface.ProjectSampleEventPresentationBusiness;
import org.jcvi.ometa.db_interface.ReadBeanPersister;
import org.jcvi.ometa.engine.MultiLoadParameter;
import org.jcvi.ometa.exception.DetailedException;
import org.jcvi.ometa.model.*;
import org.jcvi.ometa.utils.CommonTool;
import org.jcvi.ometa.utils.Constants;
import org.jcvi.ometa.utils.GuidGetter;
import org.jcvi.ometa.validation.DPCCValidator;
import org.jtc.common.util.property.PropertyHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * User: movence
 * Date: 10/14/14
 * Time: 3:05 PM
 * org.jcvi.ometa.helper
 */
public class EventLoadHelper {
    private ReadBeanPersister readPersister;
    private final String fileStoragePath;
    private String originalPath; // original path for relative file paths
    private String submissionId; // submission id

    public EventLoadHelper(ReadBeanPersister readPersister) {
        Properties props = PropertyHelper.getHostnameProperties(Constants.PROPERTIES_FILE_NAME);
        if(readPersister == null) {
            this.readPersister = new ReadBeanPersister(props);
        } else {
            this.readPersister = readPersister;
        }
        this.fileStoragePath = props.getProperty(Constants.CONIFG_FILE_STORAGE_PATH);
    }

    public EventLoadHelper(ProjectSampleEventPresentationBusiness pseb) {
        this(new ReadBeanPersister(pseb));
    }

    public void gridListToMultiLoadParameter(MultiLoadParameter loadParameter, List<GridBean> gridList, String projectName, String eventName, String status) throws Exception {

        if(eventName == null || eventName.isEmpty()) {
            throw new Exception("event name is missing.");
        }

        boolean isProjectRegistration = eventName.contains(Constants.EVENT_PROJECT_REGISTRATION);
        boolean isProjectUpdate = eventName.contains(Constants.EVENT_PROJECT_UPDATE);
        boolean isSampleRegistration = eventName.contains(Constants.EVENT_SAMPLE_REGISTRATION);

        Project loadingProject = null;
        Sample loadingSample = null;

        int gridRowIndex = 0;
        for(GridBean gBean : gridList) {
            if(gBean!=null) {
                if(isProjectRegistration && gBean.getProjectName() != null) {
                    loadingProject = new Project();
                    loadingProject.setProjectName(gBean.getProjectName());
                    loadingProject.setIsPublic(gBean.getProjectPublic() == null ? 0 : Integer.valueOf(gBean.getProjectPublic()));

                    loadingProject.setIsSecure(1); //secure all new projects
                } else {
                    if(projectName == null || projectName.isEmpty()) {
                        projectName = gBean.getProjectName();
                    }
                    loadingProject = this.readPersister.getProject(projectName);

                    if(!isProjectUpdate) {
                        if(!isSampleRegistration && (gBean.getSampleName() == null || gBean.getSampleName().isEmpty())) { //skip row without sample name
                            continue;
                        }
                        Sample existingSample = this.readPersister.getSample(loadingProject.getProjectId(), gBean.getSampleName());
                        if(existingSample == null) {
                            if(isSampleRegistration) {
                                loadingSample = new Sample();

                                String sampleIdentifierValue = this.getAttributeValue(gBean.getBeanList(), Constants.ATTR_SAMPLE_IDENTIFIER);
                                String sampleIdentifier = (sampleIdentifierValue == null || sampleIdentifierValue.isEmpty() ? "" : sampleIdentifierValue + "_");
                                loadingSample.setSampleName(sampleIdentifier + CommonTool.getGuid());

                                //loadingSample.setParentSampleName(gBean.getParentSampleName());
                                loadingSample.setIsPublic(1); //Integer.valueOf(gBean.getSamplePublic() == null ? "0" : gBean.getSamplePublic())); //default to NO
                                loadingSample.setSampleLevel(1);

                                gBean.setSampleName(loadingSample.getSampleName()); // set sample name to the grid bean
                            }
                        } else {
                            loadingSample = existingSample;
                        }
                    }
                }

                List<FileReadAttributeBean> fBeanList = gBean.getBeanList();
                // process empty attribute lists events for project/sample registrations
                if((fBeanList != null && fBeanList.size() > 0) || isProjectRegistration || isSampleRegistration) {
                    this.createMultiLoadParameter(loadParameter, projectName, eventName, loadingProject,  loadingSample, fBeanList, status, ++gridRowIndex);
                }
            }
        }
    }

    public void createMultiLoadParameter(
            MultiLoadParameter loadParameter, String projectName, String eventName,
            Project project, Sample sample, List<FileReadAttributeBean> frab, String status, int rowIndex) throws Exception {

        loadParameter.setEventName(eventName);

        boolean isProjectRegistration = (eventName.contains(Constants.EVENT_PROJECT_REGISTRATION) && project.getProjectName() != null && !project.getProjectName().isEmpty());
        boolean isSampleRegistration = (eventName.contains(Constants.EVENT_SAMPLE_REGISTRATION) && sample.getSampleName() != null && !sample.getSampleName().isEmpty());

        List<FileReadAttributeBean> loadingList = null;

        if (isProjectRegistration) {
            if (frab != null && frab.size() > 0) {
                loadingList = this.feedAndFilterFileReadBeans(eventName, isProjectRegistration ? project.getProjectName() : projectName, null, frab);
            }
        }else{
            if (frab != null && frab.size() > 0) {
            loadingList = this.feedAndFilterFileReadBeans(eventName, isProjectRegistration ? project.getProjectName() : projectName, sample.getSampleName(), frab);
            }
        }

        if (isProjectRegistration) {
            /*
            *   loads all meta attributes from the parent
            *   by hkim 6/11/13
            */
            Project parentProject = this.readPersister.getProject(projectName);

            List<EventMetaAttribute> emas = readPersister.getEventMetaAttributes(parentProject.getProjectId()); //, Constants.EVENT_PROJECT_REGISTRATION);
            List<EventMetaAttribute> newEmas = null;
            if (emas != null && emas.size() > 0) {
                newEmas = new ArrayList<EventMetaAttribute>(emas.size());
                for (EventMetaAttribute ema : emas) {
                    EventMetaAttribute newEma = CommonTool.createEMA(
                            null, project.getProjectName(), ema.getEventName(), ema.getAttributeName(),
                            ema.isRequired(), ema.isActive(), ema.getDataType(), ema.getDesc(),
                            ema.getOntology(), ema.getLabel(), ema.getOptions(), ema.isSampleRequired(), ema.getOrder());
                    newEma.setEventTypeLookupId(ema.getEventTypeLookupId());
                    newEma.setNameLookupId(ema.getNameLookupId());
                    newEmas.add(newEma);
                }
            }
            List<SampleMetaAttribute> smas = readPersister.getSampleMetaAttributes(parentProject.getProjectId());
            List<SampleMetaAttribute> newSmas = null;
            if(smas != null && smas.size() > 0) {
                newSmas = new ArrayList<SampleMetaAttribute>(smas.size());
                for(SampleMetaAttribute sma : smas) {
                    SampleMetaAttribute newSma = new SampleMetaAttribute();
                    newSma.setProjectName(project.getProjectName());
                    newSma.setAttributeName(sma.getAttributeName());
                    newSma.setNameLookupId(sma.getNameLookupId());
                    newSma.setDataType(sma.getDataType());
                    newSma.setDesc(sma.getDesc());
                    newSma.setLabel(sma.getLabel());
                    newSma.setOntology(sma.getOntology());
                    newSma.setOptions(sma.getOptions());
                    newSma.setRequired(sma.isRequired());
                    newSma.setActive(sma.isActive());
                    newSmas.add(newSma);
                }
            }
            List<ProjectMetaAttribute> pmas = readPersister.getProjectMetaAttributes(parentProject.getProjectId());
            List<ProjectMetaAttribute> newPmas = null;
            if (pmas != null && pmas.size() > 0) {
                newPmas = new ArrayList<ProjectMetaAttribute>(pmas.size());
                for (ProjectMetaAttribute pma : pmas) {
                    ProjectMetaAttribute newPma = new ProjectMetaAttribute();
                    newPma.setProjectName(project.getProjectName());
                    newPma.setAttributeName(pma.getAttributeName());
                    newPma.setDataType(pma.getDataType());
                    newPma.setDesc(pma.getDesc());
                    newPma.setLabel(pma.getLabel());
                    newPma.setNameLookupId(pma.getNameLookupId());
                    newPma.setOntology(pma.getOntology());
                    newPma.setOptions(pma.getOptions());
                    newPma.setRequired(pma.isRequired());
                    newPma.setActive(pma.isActive());
                    newPmas.add(newPma);
                }
            }

            loadParameter.addProjectPair(feedProjectData(project, parentProject), loadingList, newPmas, newSmas, newEmas, rowIndex);
        } else {
            boolean listHasData = loadingList != null && loadingList.size() > 0;
            boolean isProjectLevelEvent = isProjectRegistration || eventName.toLowerCase().contains(Constants.EVENT_PROJECT_UPDATE.toLowerCase());
            boolean isSampleLevelEvent = isSampleRegistration || eventName.toLowerCase().contains(Constants.EVENT_SAMPLE_UPDATE.toLowerCase());
            boolean isStatusGiven = status != null && !status.isEmpty();

            if(project == null || project.getProjectName() == null || project.getProjectName().isEmpty()) {
                project = this.readPersister.getProject(projectName);
            }

            if(sample.getSampleName() == null || sample.getSampleName().isEmpty()) {
                throw new Exception("Sample is required.");
            }

            //if save button is pressed then status should be set to Editing and status should be validated for Data Submitted to DPCC
            if(isStatusGiven && listHasData && !isProjectLevelEvent) {
                if(status.equals("submit") || status.equals("validate")) { //run DPCC validation
                    this.validateDataForDPCC(loadingList, rowIndex,eventName);
                }

                if(isSampleLevelEvent) { // && !status.equals("validate")) { //do not update status for validate request
                    this.updateSampleStatus(loadingList, project, sample.getSampleName(), status, rowIndex);
                }
            }

            if(listHasData) {
                if(isSampleRegistration) {
                    this.addSubmissionId(loadingList, project, sample.getSampleName(), rowIndex); // set submission id
                    loadParameter.addSamplePair(feedSampleData(sample, project), loadingList, rowIndex);
                } else {
                    loadParameter.addEvents(eventName, loadingList, rowIndex);
                }
            }
        }
    }

    private Project feedProjectData(Project project, Project parentProject) throws Exception {
        if(parentProject != null) {
            project.setParentProjectName(parentProject.getProjectName());
            project.setParentProjectId(parentProject.getProjectId());
            project.setProjectLevel(parentProject.getProjectLevel() + 1);

            project.setEditGroup(parentProject.getEditGroup());
            project.setViewGroup(parentProject.getViewGroup());
        } else {
            project.setProjectLevel(1);
        }
        return project;
    }

    private Sample feedSampleData(Sample sample, Project project) throws Exception {
        sample.setProjectId(project.getProjectId());
        sample.setProjectName(project.getProjectName());

        //set project level by adding 1 to selected parent project's level
        if(sample.getParentSampleName()==null || sample.getParentSampleName().equals("0")) {
            sample.setParentSampleName(null);
            sample.setParentSampleId(null);
            sample.setSampleLevel(1);
        } else {
            String parentSampleName = sample.getParentSampleName();
            if (parentSampleName != null && !parentSampleName.isEmpty() && !parentSampleName.equals("0")) {
                Sample selectedParentSample = readPersister.getSample(project.getProjectId(), parentSampleName);

                if(selectedParentSample != null && selectedParentSample.getSampleId() != null) {
                    sample.setSampleLevel(selectedParentSample.getSampleLevel() + 1);
                    sample.setParentSampleId(selectedParentSample.getSampleId());
                }
            }
        }
        return sample;
    }

    private List<FileReadAttributeBean> feedAndFilterFileReadBeans(String eventName, String projectName, String sampleName, List<FileReadAttributeBean> loadingList) throws Exception {
        List<FileReadAttributeBean> processedList = new ArrayList<FileReadAttributeBean>();

        for(FileReadAttributeBean fBean : loadingList) { //skip invalid attribute bean
            boolean hasValue = !fBean.getAttributeName().equals("0") && fBean.getAttributeValue() != null
                    && !fBean.getAttributeValue().isEmpty() && !fBean.getAttributeValue().toLowerCase().equals("null");
            boolean hasUploadFile  = fBean.getUpload() != null && fBean.getUploadFileName() != null && !fBean.getUploadFileName().isEmpty();
            if (!hasValue && !hasUploadFile) { // skip empty file read bean  - && !fBean.getAttributeValue().equals("0")
                continue;
            }

            if(fBean.getProjectName() == null || eventName.contains(Constants.EVENT_PROJECT_REGISTRATION)) {
                fBean.setProjectName(projectName);
            }
            if(fBean.getSampleName() == null && !eventName.contains(Constants.EVENT_PROJECT_REGISTRATION) && !eventName.contains(Constants.EVENT_PROJECT_UPDATE)) {
                if(sampleName == null || sampleName.isEmpty()) {
                    throw new Exception("sample does not exist or sample name is empty.");
                }
                fBean.setSampleName(sampleName);
            }

            String attributeName = fBean.getAttributeName();
            LookupValue lv = this.readPersister.getLookupValue(attributeName, Constants.LOOKUP_VALUE_TYPE_ATTRIBUTE); //search for attribute name from the lookup table
            if(lv == null) {
                throw new Exception("attribute '" + attributeName + "' does not exist.");
            } else {
                if(lv.getDataType().equals(Constants.FILE_DATA_TYPE)) { //process file upload
                    this.processFileUpload(fBean, projectName, sampleName);
                }
            }

            processedList.add(fBean);
        }

        return processedList;
    }

    private void validateDataForDPCC(List<FileReadAttributeBean> loadingList, int index,String eventName) throws Exception {
        String attributeName = null;
        Collection errorMessages = new ArrayList();
        try {
            for(FileReadAttributeBean fBean : loadingList) {
                if(fBean.getAttributeName().toLowerCase().contains("Collection_Date".toLowerCase())) {
                    attributeName = fBean.getAttributeName();
                    try{
                        DPCCValidator.validateDate(fBean.getAttributeValue());
                    }catch(Exception e){
                        errorMessages.add(attributeName+" is invalid. "+ e.getMessage());
                    }
                }
                if(fBean.getAttributeName().toLowerCase().contains("Receipt_Date".toLowerCase())) {
                   attributeName = fBean.getAttributeName();
                   try{
                       DPCCValidator.validateDate(fBean.getAttributeValue());
                   }catch(Exception e){
                       errorMessages.add(attributeName+" is invalid. "+ e.getMessage());
                   }
                }
                if(fBean.getAttributeName().toLowerCase().contains("Influenza_Vaccination_Date".toLowerCase())) {
                   attributeName = fBean.getAttributeName();
                   try{
                       DPCCValidator.validateDate(fBean.getAttributeValue());
                   }catch(Exception e){
                       errorMessages.add(attributeName+" is invalid. "+ e.getMessage());
                   }
                }
                if(fBean.getAttributeName().toLowerCase().contains("Test_for_Influenza_Serology".toLowerCase())) {
                   attributeName = fBean.getAttributeName();
                   try{
                       //DPCCValidator.validateSerologyTestAndResult(fBean, loadingList);
                       DPCCValidator.validatePairs("Test_for_Influenza_Serology","^[a-zA-Z0-9><=_\\-(),\\s ]*$","Text with space and comma","Serology_Test_Result","^[PNU,\\s]*$","P/N/U",loadingList);
                   }catch(Exception e){
                       errorMessages.add(e.getMessage());
                   }
                }

                if(fBean.getAttributeName().toLowerCase().contains("Influenza_Test_Type".toLowerCase())) {
                   attributeName = fBean.getAttributeName();
                   try{
                       //DPCCValidator.validateSerologyTestAndResult(fBean, loadingList);
                       DPCCValidator.validatePairs("Influenza_Test_Type","^([a-zA-Z0-9><=_\\-(),\\s ]*|NT|NA|,)*$","Text/NT(Not tested)/NA","Influenza_Test_Result","^(P|N|NT|NA|,|\\s)*$","P/N/NT/NA",loadingList);
                   }catch(Exception e){
                       errorMessages.add(e.getMessage());
                   }
                }

                if(fBean.getAttributeName().toLowerCase().contains("Other_Pathogens_Tested".toLowerCase())) {
                   attributeName = fBean.getAttributeName();
                   try{
                       //DPCCValidator.validateSerologyTestAndResult(fBean, loadingList);
                       DPCCValidator.validatePairs("Other_Pathogens_Tested","^([a-zA-Z0-9><=_\\-(),\\s ]*|NT|NA|,)*$","Text/NT(Not tested)/NA","Other_Pathogen_Test_Result","^(P|N|U|NT|NA|,|\\s)*$","P/N/U/NT/NA",loadingList);
                   }catch(Exception e){
                       errorMessages.add(e.getMessage());
                   }
                }
                if(fBean.getAttributeName().toLowerCase().contains("Duration_of_Poultry_Exposure".toLowerCase())) {
                   attributeName = fBean.getAttributeName();
                   try{
                       //DPCCValidator.validateSerologyTestAndResult(fBean, loadingList);
                       DPCCValidator.validatePairs("Other_Pathogens_Tested","^([a-zA-Z0-9><=_\\-(),\\s ]*|NT|NA|,)*$","Text/NT(Not tested)/NA","Other_Pathogen_Test_Result","^(P|N|U|NT|NA|,|\\s)*$","P/N/U/NT/NA",loadingList);
                   }catch(Exception e){
                       errorMessages.add(e.getMessage());
                   }
                }
                if (Constants.DURATION_ATTRIBUTES.contains(fBean.getAttributeName().toLowerCase())) {
                   attributeName = fBean.getAttributeName();
                   try{
                       //DPCCValidator.validateSerologyTestAndResult(fBean, loadingList);
                       DPCCValidator.validateRegEx(attributeName,fBean.getAttributeValue(),"^(([0-9.]*[ ]*[days|month|years])|NA|U|Unknown)*$","Number (2 days, 0.33 days) or NA or Unknown");
                   }catch(Exception e){
                       errorMessages.add(attributeName+": data is invalid. Allowed values are: "+ e.getMessage());
                   }
                }
                if (eventName.contains("Human Surveillance")){
                    if (fBean.getAttributeName().toLowerCase().contains("Age".toLowerCase())) {
                       attributeName = fBean.getAttributeName();
                       String message="Number (2 years, 0.33 years) or NA or U or Unknown";
                       try{
                           //DPCCValidator.validateSerologyTestAndResult(fBean, loadingList);
                           //DPCCValidator.validateNumberPattern(String value, String pattern,Boolean isNumber,String otherPattern, String message)
                           Boolean ret = DPCCValidator.validateNumberPattern(attributeName,"^((\\d*\\.?\\d*[\\s]*mL))$",Boolean.TRUE,"^(NA|U|Unknown)*$","Number (2 years, 0.33 years) or NA or U or Unknown");
                           //DPCCValidator.validateRegEx(attributeName,fBean.getAttributeValue(),"^(([0-9.]*[ ]*[years])|NA|U|Unknown)*$","Number (2 years, 0.33 years) or NA or U or Unknown");
                           if (!ret){
                             errorMessages.add(attributeName+": data is invalid. Allowed values are: "+ message);
                           }

                       }catch(Exception e){
                           errorMessages.add(attributeName+": data is invalid. Allowed values are: "+ message);
                       }
                    }
                }
                if (fBean.getAttributeName().toLowerCase().contains("Vaccine_Dosage".toLowerCase())) {
                   attributeName = fBean.getAttributeName();
                   String message="Number (2 years, 0.33 years) or NA or U or Unknown";
                   try{
                       //DPCCValidator.validateSerologyTestAndResult(fBean, loadingList);
                       Boolean ret = DPCCValidator.validateNumberPattern(attributeName,"^((\\d*\\.?\\d*[\\s]*mL))$",Boolean.TRUE,"^(NA|U|Unknown)*$",message);
                       //DPCCValidator.validateRegEx(attributeName,fBean.getAttributeValue(),"^(([0-9.]*[ ]*[mL])|NA|U|Unknown)*$","number (0.05 mL) or NA or Unknown");
                       if (!ret){
                         errorMessages.add(attributeName+": data is invalid. Allowed values are: "+ message);
                       }
                   }catch(Exception e){
                       errorMessages.add(attributeName+": data is invalid. Allowed values are: "+ message);
                   }
                }

                //"^(([0-9.]*[ ]*[days|month|years])|NA|U|Unknown)*$"
                //throw DetailedException with all error messages
                if (!errorMessages.isEmpty()) {
                    DetailedException dex = new DetailedException(index,errorMessages.toString().replaceAll("^(\\[)|(\\])$", ""));
                    throw dex;
                }
            }
        }
        catch (DetailedException e){
            throw e;
        }
        catch(Exception ex) {
            DetailedException dex = new DetailedException(index, "DPCC validation Failed." + ex.getMessage()+ "'");
            throw dex;
        }
    }


    private void updateSampleStatus(List<FileReadAttributeBean> loadingList, Project project, String sampleName, String status, int index) throws Exception {
        try {

            Sample sample = this.readPersister.getSample(project.getProjectId(), sampleName);
            if(sample != null) { //it could be sample registration event
                List<SampleAttribute> saList = this.readPersister.getSampleAttributes(sample.getSampleId());
                for(SampleAttribute sa : saList) {
                    if(sa.getMetaAttribute().getLookupValue().getName().equals(Constants.ATTR_SAMPLE_STATUS)) {
                        if(sa.getAttributeStringValue() != null && sa.getAttributeStringValue().equals(Constants.DPCC_STATUS_SUBMITTED)) {
                            throw new Exception(Constants.ERROR_DPCC_NOT_MODIFIABLE);
                        }
                    }
                }
            }

            String strStatus = status.equals("submit") ? Constants.DPCC_STATUS_SUBMITTED : Constants.DPCC_STATUS_EDITING;

            this.findAndSetAttributeValue(loadingList, project, sampleName, Constants.ATTR_SAMPLE_STATUS, strStatus, index);

        } catch(Exception ex) {
            ex.printStackTrace();
            DetailedException dex = new DetailedException(index, ex.getMessage());
            throw dex;
        }
    }

    private void processFileUpload(FileReadAttributeBean fBean, String projectName, String sampleName) throws Exception {

        try {
            File fileToUpload = null;
            boolean isTempFile = true;

            if(fBean.getUpload() == null || fBean.getUploadFileName() == null) {
                String filePath = (this.originalPath == null ? "" : this.originalPath) + File.separator + fBean.getAttributeValue();
                fileToUpload = new File(filePath);

                fBean.setUpload(fileToUpload);
                fBean.setUploadFileName(fileToUpload.getName());

                isTempFile = false;
            }

            fileToUpload = fBean.getUpload();

            if(!fileToUpload.exists() || !fileToUpload.canRead() || !fileToUpload.isFile()) {
                throw new Exception("file '" + fBean.getUploadFileName() + "' does not exist!");
            }

            String storagePathProject = projectName.replaceAll(" ", "_"); //project folder
            String storagePathSample = (sampleName != null && !sampleName.isEmpty() ? sampleName.replaceAll(" ", "_") : "project"); //sample folder
            Date date = Calendar.getInstance().getTime();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
            String storagePathDate = sdf.format(date);

            String storagePath = storagePathProject + File.separator + storagePathSample + File.separator + storagePathDate;

            File storingDirectory = new File(this.fileStoragePath + File.separator + storagePath);
            storingDirectory.mkdirs();

            GuidGetter guidGetter = new GuidGetter();
            Long guid = guidGetter.getGuid();

            String destFileName = guid + "_" + fBean.getUploadFileName();
            File destFile = new File(storingDirectory.getAbsolutePath() + File.separator + destFileName);

            FileUtils.copyFile(fileToUpload, destFile);

            fBean.setAttributeValue(storagePath + File.separator + destFileName);

            if(isTempFile) {
                fBean.getUpload().delete();
            }

        } catch(Exception ex) {
            throw new Exception(ex.getMessage());
        }
    }

    private void addSubmissionId(List<FileReadAttributeBean> loadingList, Project project, String sampleName, int index) throws Exception {
        if(submissionId == null || submissionId.isEmpty()) {
            GuidGetter guidGetter = new GuidGetter();
            submissionId = Long.toString(guidGetter.getGuid());
        }

        this.findAndSetAttributeValue(loadingList, project, sampleName, Constants.ATTR_SUBMISSION_ID, this.submissionId, index);
    }

    private void findAndSetAttributeValue(
            List<FileReadAttributeBean> loadingList, Project project, String sampleName,
            String attributeName, String attributeValue, int index) throws Exception {
        boolean foundAttribute = false;

        try {

            for(FileReadAttributeBean fBean : loadingList) {
                if(fBean.getAttributeName().toLowerCase().equals(attributeName.toLowerCase())) {
                    fBean.setAttributeValue(attributeValue);
                    foundAttribute = true;
                }
            }
            if(!foundAttribute) {
                List<SampleMetaAttribute> smaList = this.readPersister.getSampleMetaAttributes(project.getProjectId());
                for(SampleMetaAttribute sma : smaList) {
                    if(sma.getLookupValue().getName().equals(attributeName)) {
                        foundAttribute = true;
                    }
                }

                if(foundAttribute) { //manually add submission id
                    FileReadAttributeBean submissionBean = new FileReadAttributeBean();
                    submissionBean.setAttributeName(attributeName);
                    submissionBean.setAttributeValue(attributeValue);
                    submissionBean.setProjectName(project.getProjectName());
                    submissionBean.setSampleName(sampleName);
                    loadingList.add(submissionBean);
                } else {
                    throw new Exception("'" + attributeName + "' attribute not found.");
                }
            }
        } catch(Exception ex) {
            ex.printStackTrace();
            DetailedException dex = new DetailedException(index, ex.getMessage());
            throw dex;
        }
    }

    private String getAttributeValue(List<FileReadAttributeBean> loadingList, String attributeName) throws Exception {
        String attributeValue = null;

        for(FileReadAttributeBean fBean : loadingList) {
            if(fBean.getAttributeName().toLowerCase().equals(attributeName.toLowerCase())) {
                attributeValue = fBean.getAttributeValue();
            }
        }

        return attributeValue;
    }

    public String getOriginalPath() {
        return originalPath;
    }

    public void setOriginalPath(String originalPath) {
        this.originalPath = originalPath;
    }

    public String getSubmissionId() {
        return submissionId;
    }

    public void setSubmissionId(String submissionId) {
        this.submissionId = submissionId;
    }
}
