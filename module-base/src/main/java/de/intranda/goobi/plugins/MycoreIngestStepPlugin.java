package de.intranda.goobi.plugins;


import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.goobi.beans.GoobiProperty;
import org.goobi.beans.JournalEntry;
import org.goobi.beans.JournalEntry.EntryType;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;

import de.intranda.goobi.plugins.model.IngestFile;
import de.intranda.goobi.plugins.model.IngestReceipt;
import de.intranda.goobi.plugins.model.MycoreDirectory;
import de.intranda.goobi.plugins.model.MycoreFile;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.export.download.ExportMets;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import de.sub.goobi.persistence.managers.JournalManager;
import kong.unirest.core.HttpResponse;
import kong.unirest.core.Unirest;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j2
public class MycoreIngestStepPlugin implements IStepPluginVersion2 {

	@Getter
	private String title = "intranda_step_mycore_ingest";
	@Getter
	private Step step;
	private String returnPath;

	private String xslt;
	private String mycoreApi;
	private String mycoreLogin;
	private String mycorePassword;
	private IngestReceipt receipt;
	private List<IngestFile> derivatives;
	private List<IngestFile> altos;
	@Override
	public void initialize(Step step, String returnPath) {
		this.returnPath = returnPath;
		this.step = step;

		// read parameters from correct block in configuration file
		SubnodeConfiguration myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
		xslt = myconfig.getString("xslt-url", "http://files.intranda.com/123");
		mycoreApi = myconfig.getString("mycore-api", "https://mycore.io/123");
		mycoreLogin = myconfig.getString("mycore-login", "login");
		mycorePassword = myconfig.getString("mycore-password", "password");
		log.info("MycoreIngest step plugin initialized");
	}

	@Override
	public PluginGuiType getPluginGuiType() {
		return PluginGuiType.NONE;
	}

	@Override
	public String getPagePath() {
		return "/uii/plugin_step_mycore_ingest.xhtml";
	}

	@Override
	public PluginType getType() {
		return PluginType.Step;
	}

	@Override
	public String cancel() {
		return "/uii" + returnPath;
	}

	@Override
	public String finish() {
		return "/uii" + returnPath;
	}

	@Override
	public int getInterfaceVersion() {
		return 0;
	}

	@Override
	public HashMap<String, StepReturnValue> validate() {
		return null;
	}

	@Override
	public boolean execute() {
		PluginReturnValue ret = run();
		return ret != PluginReturnValue.ERROR;
	}

	@Override
	public PluginReturnValue run() {
		derivatives = new ArrayList<>();
		altos = new ArrayList<>();
		receipt = new IngestReceipt();
		receipt.setStatus("STARTED");
		
		// export the mets file
		Path metsfile;
		try {
			metsfile = exportMetsFile();
		} catch (PreferencesException | WriteException | DocStructHasNoTypeException | MetadataTypeNotAllowedException
				| ReadException | TypeNotAllowedForParentException | IOException | InterruptedException
				| ExportFileException | UghHelperException | SwapException | DAOException e) {
			log.error("Error while executing the METS-Export", e);
			return PluginReturnValue.ERROR;
		}

		// do an xslt convert of the mets file
		String xmlResult = null;
		try {
			xmlResult = xslTranform(metsfile);
		} catch (IOException | TransformerException e) {
			log.error("Error while doing the XSLT processing for the METS file", e);
			writeErrorToJournal("Error while doing the XSLT processing for the METS file: " + e.getMessage());
			return PluginReturnValue.ERROR;
		}
		
		// create volume in mycore
		String volumeLocation = null;
		try {
			volumeLocation = createVolume(xmlResult);
			log.info("MyCoRe passed back this URL for the volume: " + volumeLocation);
			receipt.setVolume(volumeLocation);
		} catch (IOException e) {
			log.error("Error while creating the volume", e);
			writeErrorToJournal("Error while creating the volume: " + e.getMessage());
			return PluginReturnValue.ERROR;
		}
		
		// create derivative in mycore
		String derivativeLocation = null;
		try {
			derivativeLocation = createDerivativeForVolume(volumeLocation, xmlResult);
			log.info("MyCoRe passed back this URL for the derivative: " + derivativeLocation);
			receipt.setDerivative(derivativeLocation);
		} catch (IOException e) {
			log.error("Error while creating the derivative", e);
			writeErrorToJournal("Error while creating the derivative: " + e.getMessage());
			return PluginReturnValue.ERROR;
		}
		
		try {
			// upload image derivatives
			uploadImageFilesToDerivative(derivativeLocation);
			
			for (IngestFile i : derivatives) {
				System.out.println(i.getName());
			}
			System.out.println("-------------------------");
			
			// upload ALTO files
			uploadAltoFilesToDerivative(derivativeLocation);

			for (IngestFile i : altos) {
				System.out.println(i.getName());
			}
			System.out.println("-------------------------");
			
			// upload regular METS file
			uploadFileToDerivative(derivativeLocation, metsfile, "application/xml", "", "goobi_mets.xml");
			
			// upload METS anchor file
			String anchor = metsfile.toString().replace("_mets.xml", "_mets_anchor.xml");
			uploadFileToDerivative(derivativeLocation, Paths.get(anchor), "application/xml", "", "goobi_mets_anchor.xml");
			
			// validate content for images and mets file
			validateIngestedContent(derivativeLocation + "/contents/", derivatives);
			validateIngestedContent(derivativeLocation + "/contents/ocr/alto/", altos);
			
			// add files into receipt
			receipt.getFiles().addAll(derivatives);
			receipt.getFiles().addAll(altos);
			
			log.info("Images were uploaded to MyCoRe derivative");
		} catch (IOException | SwapException e) {
			log.error("Error while uploading images to the derivative", e);
			writeErrorToJournal("Error while uploading images to the derivative: " + e.getMessage());
			return PluginReturnValue.ERROR;
		}
		
		
		log.info("MycoreIngest step plugin executed");
		writeReceipt(true, "Ingest finished successfull");
		return PluginReturnValue.FINISH;
	}

	/**
	 * Finish the receipt and write it into the filesystem
	 * 
	 * @param status
	 * @param details
	 */
	private void writeReceipt(boolean status, String details) {
		receipt.setStatus(status?"FINISHED":"ERROR");
		receipt.setDetails(details);
		receipt.setEnd(LocalDateTime.now());
		
		// write object as xml file
   		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmssSSS");
		JavaTimeModule jsr310 = new JavaTimeModule();
		jsr310.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(formatter));
		jsr310.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(formatter));
		ObjectMapper om = new XmlMapper()
				.registerModule(jsr310)
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		om.setSerializationInclusion(Include.NON_EMPTY);
        om.enable(SerializationFeature.INDENT_OUTPUT);

        
        try {
	        Path folder = Paths.get(step.getProzess().getProcessDataDirectory(),
	                    ConfigurationHelper.getInstance().getFolderForInternalJournalFiles());
	        if (!StorageProvider.getInstance().isFileExists(folder)) {
	            StorageProvider.getInstance().createDirectories(folder);
	        }
	        String filename = "ingest-receipt-" + receipt.getEnd().format(formatter) + ".xml";
	        Path file = Path.of(folder.toString(), filename);
	        om.writeValue(file.toFile(), receipt);
        
            JournalEntry entry = new JournalEntry(step.getProzess().getId(), new Date(), "- automatic -",
                    LogType.FILE, "Receipt for the ingest into MyCoRe created", EntryType.PROCESS);
            entry.setFilename(file.toString());
            JournalManager.saveJournalEntry(entry);

		} catch (IOException | SwapException e) {
			log.error("Error writing the receipt to the filesystem", e);
		}

	}

	/**
	 * simple helper to write error message into journal
	 * @param message
	 */
	private void writeErrorToJournal(String message) {
		JournalEntry entry = new JournalEntry(step.getProzess().getId(), new Date(), "- automatic -",
                LogType.ERROR, message, EntryType.PROCESS);
        JournalManager.saveJournalEntry(entry);
	}
	
	/**
	 * do a regular export of a METS file into temp folder
	 * 
	 * @param successful
	 * @return
	 * @throws PreferencesException
	 * @throws WriteException
	 * @throws DocStructHasNoTypeException
	 * @throws MetadataTypeNotAllowedException
	 * @throws ReadException
	 * @throws TypeNotAllowedForParentException
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExportFileException
	 * @throws UghHelperException
	 * @throws SwapException
	 * @throws DAOException
	 */
	private Path exportMetsFile()
			throws PreferencesException, WriteException, DocStructHasNoTypeException, MetadataTypeNotAllowedException,
			ReadException, TypeNotAllowedForParentException, IOException, InterruptedException, ExportFileException,
			UghHelperException, SwapException, DAOException {
		
		ExportMets export = new ExportMets();
		boolean success = export.startExport(step.getProzess(), ConfigurationHelper.getInstance().getTemporaryFolder());
		Path metsfile = Paths.get(ConfigurationHelper.getInstance().getTemporaryFolder(),
				step.getProzess().getTitel() + "_mets.xml");
		if (!success) {
			throw new IOException("Export to file " + metsfile + " was not successfull");
		}
		return metsfile;
	}
	
	
	/**
	 * do the xsl transformation of the mets file
	 * @param metsfile
	 * @throws IOException 
	 * @throws TransformerException 
	 */
	private String xslTranform(Path metsfile) throws IOException, TransformerException {  
            	
    	// first get the mycore id from a property
		String mycoreId = null;
    	for (GoobiProperty gp : step.getProzess().getEigenschaftenList()) {
            if (gp.getPropertyName().equalsIgnoreCase("MyCore-ID")) {
            	mycoreId = gp.getPropertyValue();
                break;
            }
    	}
    	
    	// if property exists to transformation
    	if (mycoreId!=null) {
    		
        	Source xmlSource = new StreamSource(metsfile.toFile());  
            URL xsltUrl = new URL(xslt);  
            Source xsltSource = new StreamSource(xsltUrl.openStream());  
  
            TransformerFactory factory = TransformerFactory.newInstance();  
            Transformer transformer = factory.newTransformer(xsltSource);  
            transformer.setParameter("parentID", mycoreId);  
          
            StringWriter writer = new StringWriter();
            StreamResult result = new StreamResult(writer);
            transformer.transform(xmlSource, result);
            String xmlString = writer.toString();
            return xmlString;
            
    	} else {
    		throw new IOException("No MyCoRe identifier could be found as property with name 'MyCore-ID'");
    	}
	}
	
	/**
	 * create a volume inside of MyCoRe
	 * @param sourceXml
	 * @return
	 * @throws IOException
	 */
	private String createVolume(String sourceXml) throws IOException {
		HttpResponse<String> response = Unirest.post(mycoreApi + "objects")
		  .header("Content-Type", "application/xml")
		  .header("Accept", "application/xml")
		  .basicAuth(mycoreLogin, mycorePassword)
		  .body(sourceXml)
		  .asString();

		if (response.isSuccess()) {
		    String location = response.getHeaders().getFirst("location");
		    if (location != null) {
		    	return location;
		    } else {
		    	throw new IOException("No location could be found for created volume in MyCoRe.");
		    }
		} else {
			throw new IOException("Response of MyCoRe for creation of volume was not successful: " + response.getStatus() + " - " + response.getBody());
		}
	}
	
	/**
	 * create derivative for a volume inside of MyCoRe
	 * @param inLocation
	 * @param sourceXml
	 * @return
	 * @throws IOException
	 */
	private String createDerivativeForVolume(String inLocation, String sourceXml) throws IOException {
		HttpResponse<String> response = Unirest.post(inLocation + "/derivates")
		  .header("Content-Type", "application/xml")
		  .header("Accept", "application/xml")
		  .basicAuth(mycoreLogin, mycorePassword)
		  .body(sourceXml)
		  .asString();

		if (response.isSuccess()) {
		    String location = response.getHeaders().getFirst("location");
		    if (location != null) {
		    	return location;
		    } else {
		    	throw new IOException("No location could be found for created derivative in MyCoRe.");
		    }
		} else {
			throw new IOException("Response of MyCoRe for creation of derivative was not successful: " + response.getStatus() + " - " + response.getBody());
		}
	}
	

	/**
	 * upload images to derivative in MyCoRe
	 * 
	 * @param inLocation
	 * @throws IOException
	 * @throws SwapException
	 */
	private void uploadImageFilesToDerivative(String inLocation) throws IOException, SwapException {
		String folder = step.getProzess().getImagesTifDirectory(false);
		List<Path> list = StorageProvider.getInstance().listFiles(folder);
		for (Path p : list) {
			IngestFile f = new IngestFile();
			f.setGoobiFilePath(p.toString());
			f.setName(p.getFileName().toString());
			f.setGoobiFileType("media");
			f.setGoobiSize(Files.size(p));
			derivatives.add(f);
			uploadFileToDerivative(inLocation, p, "image/tif", "", p.getFileName().toString());
		}
	}
	
	/**
	 * upload alto files to derivative in MyCoRe
	 * 
	 * @param inLocation
	 * @throws IOException
	 * @throws SwapException
	 */
	private void uploadAltoFilesToDerivative(String inLocation) throws IOException, SwapException {
		String folder = step.getProzess().getOcrAltoDirectory();
		List<Path> list = StorageProvider.getInstance().listFiles(folder);
		for (Path p : list) {
			IngestFile f = new IngestFile();
			f.setGoobiFilePath(p.toString());
			f.setName(p.getFileName().toString());
			f.setGoobiFileType("alto");
			f.setGoobiSize(Files.size(p));
			altos.add(f);
			uploadFileToDerivative(inLocation, p, "application/xml", "ocr/alto/", p.getFileName().toString());
		}
	}

	
	/**
	 * upload a file to derivative in MyCoRe
	 * 
	 * @param inLocation
	 * @param p
	 * @param mimetype
	 * @throws IOException
	 */
	private void uploadFileToDerivative(String inLocation, Path p, String mimetype, String subfolder, String filename) throws IOException {
		log.info("Upload file " + p.toString() + " to MyCoRe");
		HttpResponse<InputStream> response = Unirest.put(inLocation + "/contents/" + subfolder + filename)
		        .header("Content-Type", mimetype)
		        .header("Accept", "application/xml")
		        .basicAuth(mycoreLogin, mycorePassword)
		        .body(p.toFile())
		        .asObject(InputStream.class);

		if (response.getStatus() < 200 || response.getStatus() >= 300) {
		    throw new IOException("Response of MyCoRe for creation of derivative was not successful: " 
		        + response.getStatus());
		}
	}
	
	
	/**
	 * Validate content after the mycore ingest
	 * 
	 * @param inLocation
	 * @throws JsonMappingException
	 * @throws JsonProcessingException
	 */
	private void validateIngestedContent(String inLocation, List<IngestFile> list) throws JsonMappingException, JsonProcessingException {
		log.info("Validate the content of ingested content under " + inLocation + " in MyCoRe");
		HttpResponse<String> response = Unirest.get(inLocation)
				.header("Accept", "application/xml")
				.basicAuth(mycoreLogin, mycorePassword)
				.asString();
		
		XmlMapper xml = new XmlMapper();
        xml.registerModule(new JavaTimeModule());
        xml.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        xml.enable(SerializationFeature.INDENT_OUTPUT);
        xml.configure(ToXmlGenerator.Feature.WRITE_XML_DECLARATION, true);
        MycoreDirectory dn = xml.readValue(response.getBody(), MycoreDirectory.class);
		for (MycoreFile	mf : dn.getFiles()) {
			System.out.println(mf.getName());
			for (IngestFile f : list) {
				if (f.getName().equals(mf.getName())) {
					f.setMycoreChecksum(mf.getMd5());
					f.setMycoreMimeType(mf.getMimeType());
					f.setMycoreSize(mf.getSize());
					f.setMycoreUrl(inLocation + mf.getName());					
				}
			}
		}

	}
	
//	public static void main(String[] args) {
//		HttpResponse<String> response = Unirest.get("https://zs-test.thulb.uni-jena.de/api/v2/objects/jportal_jpvolume_00003142/derivates/jportal_derivate_00002172/contents/")
//		  .header("Authorization", "Basic Z29vYmk6dGVzdDEyMy4=")
//		  .asString();
//
//		
//		
//		
//		System.out.println(response.getBody().toString());
//
//		XmlMapper xml = new XmlMapper();
//        xml.registerModule(new JavaTimeModule());
//        xml.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
//        xml.enable(SerializationFeature.INDENT_OUTPUT);
//        xml.configure(ToXmlGenerator.Feature.WRITE_XML_DECLARATION, true);
//        try {
//        	MycoreDirectory dn = xml.readValue(response.getBody(), MycoreDirectory.class);
//			System.out.println(dn.getModified());
//			for (MycoreFile	f : dn.getFiles()) {
//				System.out.println(f.getName() + " - " + f.getMd5() + " - " + f.getMimeType());
//			}
//		} catch (JsonProcessingException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//        
//	}
	
	
}
