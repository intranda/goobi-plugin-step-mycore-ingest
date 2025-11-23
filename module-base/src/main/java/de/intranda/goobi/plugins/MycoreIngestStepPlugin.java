package de.intranda.goobi.plugins;

import java.io.FileInputStream;
import java.io.IOException;
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

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.goobi.beans.GoobiProperty;
import org.goobi.beans.GoobiProperty.PropertyOwnerType;
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
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.enums.PropertyType;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import de.sub.goobi.persistence.managers.JournalManager;
import de.sub.goobi.persistence.managers.PropertyManager;
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
	private List<IngestFile> metses;
	private List<IngestFile> medias;
	private List<IngestFile> altos;
	private int ingestMaxTries = 3;
	private int ingestCurrentTry = 1;
	private boolean ingestOk = false;
	private String ingestMessage = "";

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
		ingestMaxTries = myconfig.getInt("max-tries", 3);
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
		ingestCurrentTry = 0;
		metses = new ArrayList<>();
		medias = new ArrayList<>();
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
			
			// upload regular METS file
			IngestFile fmets = new IngestFile();
			fmets.setGoobiFilePath(metsfile.toString());
			fmets.setName("goobi_mets.xml");
			fmets.setGoobiFileType("mets");
			fmets.setGoobiSize(Files.size(metsfile));
			fmets.setGoobiChecksum(md5Hex(metsfile));
			metses.add(fmets);
			
			// upload METS anchor file
			Path anchor = Paths.get(metsfile.toString().replace("_mets.xml", "_mets_anchor.xml"));
			IngestFile fmetsanchor = new IngestFile();
			fmetsanchor.setGoobiFilePath(anchor.toString());
			fmetsanchor.setName("goobi_mets_anchor.xml");
			fmetsanchor.setGoobiFileType("mets");
			fmetsanchor.setGoobiSize(Files.size(anchor));
			fmetsanchor.setGoobiChecksum(md5Hex(anchor));
			metses.add(fmetsanchor);
			
			// try several times to ingest the files
			while (!ingestOk && ingestCurrentTry < ingestMaxTries) {
				ingestCurrentTry++;
				
				// if not uploaded successfully before try it two more times max 
				if (!fmets.isValid() && fmets.getUploadCounter()<ingestMaxTries) {
					fmets.setUploadCounter(fmets.getUploadCounter() + 1);
					uploadFile(derivativeLocation + "/contents/", metsfile, "application/xml", "goobi_mets.xml");
				}
				if (!fmetsanchor.isValid() && fmetsanchor.getUploadCounter()<ingestMaxTries) {
					fmetsanchor.setUploadCounter(fmetsanchor.getUploadCounter() + 1);
					uploadFile(derivativeLocation + "/contents/", anchor, "application/xml", "goobi_mets_anchor.xml");
				}				
				
				// upload image derivatives and ALTO files
				uploadFolder(step.getProzess().getImagesTifDirectory(false), "media", medias,
						derivativeLocation + "/contents/", "image/tif");
				uploadFolder(step.getProzess().getOcrAltoDirectory(), "alto", altos,
						derivativeLocation + "/contents/ocr/alto/", "application/xml");

				// request content information for images and mets file
				requestIngestedContentInformation(derivativeLocation, "/contents/", metses);
				requestIngestedContentInformation(derivativeLocation, "/contents/", medias);
				requestIngestedContentInformation(derivativeLocation, "/contents/ocr/alto/", altos);

				validateFiles(derivativeLocation, "/contents/", "/contents/ocr/alto/");
			}
			
			// add files into receipt
			receipt.getFiles().addAll(metses);
			receipt.getFiles().addAll(medias);
			receipt.getFiles().addAll(altos);
			log.info("Images were uploaded to MyCoRe derivative");
		} catch (IOException | SwapException e) {
			log.error("Error while uploading images to the derivative", e);
			writeErrorToJournal("Error while uploading images to the derivative: " + e.getMessage());
			return PluginReturnValue.ERROR;
		}

		// write summary information into properties
		try {
			writeProperty("Ingest Status", String.valueOf(ingestOk));
			writeProperty("Ingest Details", ingestMessage);
			writeProperty("Ingest Timestamp", LocalDateTime.now().toString());
			writeProperty("Derivat URL", derivativeLocation);
			writeSummaryProperties();
			log.info("Properties with ingest results into MyCoRe were created");
		} catch (IOException | SwapException | DAOException e) {
			log.error("Error while writing summary information of MyCoRe ingest as properties", e);
			writeErrorToJournal(
					"Error while writing summary information of MyCoRe ingest as properties: " + e.getMessage());
			return PluginReturnValue.ERROR;
		}

		writeReceipt(ingestOk, ingestMessage);
		log.info("MycoreIngest step plugin executed");		
		if (ingestOk) {
			return PluginReturnValue.FINISH;			
		} else {
			return PluginReturnValue.ERROR;						
		}
	}

	/**
	 * write summary information into the properties of the process
	 * 
	 * @throws DAOException
	 * @throws SwapException
	 * @throws IOException
	 */
	private void writeSummaryProperties() throws IOException, SwapException, DAOException {

		// File sizes master in Goobi
		long sizeMaster = 0;
		String masterfolder = step.getProzess().getImagesOrigDirectory(false);
		List<Path> list = StorageProvider.getInstance().listFiles(masterfolder);
		for (Path p : list) {
			sizeMaster += Files.size(p);
		}
		writeProperty("Speicherplatz Master Goobi", String.valueOf(sizeMaster));

		// File sizes media in Goobi and MyCoRe
		long sizeMediaGoobi = 0;
		long sizeMediaMyCoRe = 0;
		for (IngestFile i : medias) {
			sizeMediaGoobi += i.getGoobiSize();
			sizeMediaMyCoRe += i.getMycoreSize();
		}
		writeProperty("Speicherplatz Derivate Goobi", String.valueOf(sizeMediaGoobi));
		writeProperty("Speicherplatz Derivate MyCoRe", String.valueOf(sizeMediaMyCoRe));

		// File sizes alto in Goobi and MyCoRe
		long sizeAltoGoobi = 0;
		long sizeAltoMyCoRe = 0;
		for (IngestFile i : altos) {
			sizeAltoGoobi += i.getGoobiSize();
			sizeAltoMyCoRe += i.getMycoreSize();
		}
		writeProperty("Speicherplatz ALTO Goobi", String.valueOf(sizeAltoGoobi));
		writeProperty("Speicherplatz ALTO MyCoRe", String.valueOf(sizeAltoMyCoRe));

	}

	/**
	 * write one specific property
	 * 
	 * @param string
	 * @param valueOf
	 */
	private void writeProperty(String name, String value) {
		GoobiProperty prop = new GoobiProperty(PropertyOwnerType.PROCESS);
		prop.setOwner(step.getProzess());
		prop.setPropertyName(name);
		prop.setType(PropertyType.getByName("String"));
		prop.setPropertyValue(value);
		prop.setContainer("MyCoRe");
		step.getProzess().getEigenschaften().add(prop);
		PropertyManager.saveProperty(prop);
	}

	/**
	 * Finish the receipt and write it into the filesystem
	 * 
	 * @param status
	 * @param details
	 */
	private void writeReceipt(boolean status, String details) {
		receipt.setStatus(status ? "FINISHED" : "ERROR");
		receipt.setDetails(details);
		receipt.setEnd(LocalDateTime.now());

		// write object as xml file
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmssSSS");
		JavaTimeModule jsr310 = new JavaTimeModule();
		jsr310.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(formatter));
		jsr310.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(formatter));
		ObjectMapper om = new XmlMapper().registerModule(jsr310)
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

			JournalEntry entry = new JournalEntry(step.getProzess().getId(), new Date(), "- automatic -", LogType.FILE,
					"Receipt for the ingest into MyCoRe created", EntryType.PROCESS);
			entry.setFilename(file.toString());
			JournalManager.saveJournalEntry(entry);

		} catch (IOException | SwapException e) {
			log.error("Error writing the receipt to the filesystem", e);
		}

	}

	/**
	 * simple helper to write error message into journal
	 * 
	 * @param message
	 */
	private void writeErrorToJournal(String message) {
		JournalEntry entry = new JournalEntry(step.getProzess().getId(), new Date(), "- automatic -", LogType.ERROR,
				message, EntryType.PROCESS);
		JournalManager.saveJournalEntry(entry);
		writeReceipt(false, message);
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
	private Path exportMetsFile() throws PreferencesException, WriteException, DocStructHasNoTypeException,
			MetadataTypeNotAllowedException, ReadException, TypeNotAllowedForParentException, IOException,
			InterruptedException, ExportFileException, UghHelperException, SwapException, DAOException {

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
	 * 
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
		if (mycoreId != null) {

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
	 * 
	 * @param sourceXml
	 * @return
	 * @throws IOException
	 */
	private String createVolume(String sourceXml) throws IOException {
		HttpResponse<String> response = Unirest.post(mycoreApi + "objects").header("Content-Type", "application/xml")
				.header("Accept", "application/xml").basicAuth(mycoreLogin, mycorePassword).body(sourceXml).asString();

		if (response.isSuccess()) {
			String location = response.getHeaders().getFirst("location");
			if (location != null) {
				return location;
			} else {
				throw new IOException("No location could be found for created volume in MyCoRe.");
			}
		} else {
			throw new IOException("Response of MyCoRe for creation of volume was not successful: "
					+ response.getStatus() + " - " + response.getBody());
		}
	}

	/**
	 * create derivative for a volume inside of MyCoRe
	 * 
	 * @param inLocation
	 * @param sourceXml
	 * @return
	 * @throws IOException
	 */
	private String createDerivativeForVolume(String inLocation, String sourceXml) throws IOException {
		HttpResponse<String> response = Unirest.post(inLocation + "/derivates")
				.header("Content-Type", "application/xml").header("Accept", "application/xml")
				.basicAuth(mycoreLogin, mycorePassword).body(sourceXml).asString();

		if (response.isSuccess()) {
			String location = response.getHeaders().getFirst("location");
			if (location != null) {
				return location;
			} else {
				throw new IOException("No location could be found for created derivative in MyCoRe.");
			}
		} else {
			throw new IOException("Response of MyCoRe for creation of derivative was not successful: "
					+ response.getStatus() + " - " + response.getBody());
		}
	}

	/**
	 * upload all files of a folder to derivative in MyCoRe
	 * 
	 * @param folder
	 * @param type
	 * @param list
	 * @param location
	 * @param mimetype
	 * @throws IOException
	 * @throws SwapException
	 */
	private void uploadFolder(String folder, String type, List<IngestFile> list, String location, String mimetype)
			throws IOException, SwapException {
		List<Path> filelist = StorageProvider.getInstance().listFiles(folder);
		for (Path p : filelist) {
			IngestFile f = null;
			for (IngestFile inf : list) {
				// if file is known, reupload it
				if (inf.getGoobiFilePath().equals(p.toString())) {
					f = inf;
					break;
				}
			}
			// if file is unknown, create and add it
			if (f==null) {
				f = new IngestFile();
				f.setGoobiFilePath(p.toString());
				f.setName(p.getFileName().toString());
				f.setGoobiFileType(type);
				f.setGoobiSize(Files.size(p));
				f.setGoobiChecksum(md5Hex(p));
				list.add(f);
			}
			
			// if not uploaded successfully before try it two more times max 
			if (!f.isValid() && f.getUploadCounter()<3) {
				f.setUploadCounter(f.getUploadCounter() + 1);
				uploadFile(location, p, mimetype, p.getFileName().toString());
			}
		}
	}

	/**
	 * upload a file to derivative in MyCoRe
	 * 
	 * @param location
	 * @param p
	 * @param mimetype
	 * @param filename
	 * @throws IOException
	 */
	private void uploadFile(String location, Path p, String mimetype, String filename) throws IOException {
		log.info("Upload file " + p.toString() + " to MyCoRe");
		int count = 0;
		boolean success = false;
		int status = 0;

		// try up to 3 times to upload a file
		while (!success && count < 3) {
			count++;
			try {
				HttpResponse<String> response = Unirest.put(location + filename).header("Content-Type", mimetype)
						.basicAuth(mycoreLogin, mycorePassword).body(Files.readAllBytes(p)).asString();
				success = true;
				status = response.getStatus();
			} catch (IOException e) {
				log.error("Error while uploading file (" + count + ")", e);
			}
		}

		if (status < 200 || status >= 300) {
			throw new IOException("Response of MyCoRe for creation of derivative was not successful: " + status);
		}
	}

	/**
	 * Validate content after the mycore ingest
	 * 
	 * @param inLocation
	 * @throws SwapException
	 * @throws IOException
	 */
	private void requestIngestedContentInformation(String inLocation, String locationSuffix, List<IngestFile> list)
			throws IOException, SwapException {
		log.info("Request content of ingested content under " + inLocation + locationSuffix + " in MyCoRe");
		HttpResponse<String> response = Unirest.get(inLocation + locationSuffix).header("Accept", "application/xml")
				.basicAuth(mycoreLogin, mycorePassword).asString();

		XmlMapper xml = new XmlMapper();
		xml.registerModule(new JavaTimeModule());
		xml.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		xml.enable(SerializationFeature.INDENT_OUTPUT);
		xml.configure(ToXmlGenerator.Feature.WRITE_XML_DECLARATION, true);
		MycoreDirectory dn = xml.readValue(response.getBody(), MycoreDirectory.class);
		for (MycoreFile mf : dn.getFiles()) {
			for (IngestFile f : list) {
				if (f.getName().equals(mf.getName())) {
					f.setMycoreChecksum(mf.getMd5());
					f.setMycoreMimeType(mf.getMimeType());
					f.setMycoreSize(mf.getSize());
					f.setMycoreUrl(inLocation + locationSuffix + mf.getName());
					f.setValid(f.getGoobiChecksum().equals(f.getMycoreChecksum()));
				}
			}
		}
	}

	/**
	 * Generate MD5 Checksum for file
	 * 
	 * @param p
	 * @return
	 * @throws IOException
	 */
	public static String md5Hex(Path p) throws IOException {
		try (FileInputStream fis = new FileInputStream(p.toFile())) {
			return DigestUtils.md5Hex(fis);
		}
	}

	/**
	 * validate uploaded content and reupload if needed
	 * 
	 * @param derivativeLocation
	 * @param suffixImages
	 * @param listImages
	 * @param suffixAlto
	 * @param listAlto
	 * @throws IOException
	 */
	private void validateFiles(String location, String pathimages, String pathalto) throws IOException {
		List<IngestFile> allLists = new ArrayList<>();
		allLists.addAll(metses);
		allLists.addAll(medias);
		allLists.addAll(altos);
		
		// check all image checksums
		for (IngestFile f : allLists) {
			if (!f.getMycoreChecksum().equals(f.getGoobiChecksum())) {
				ingestOk = false;
				ingestMessage = "Checksums do not match";
				return;
			}
		}
		
		ingestOk = true;
		ingestMessage = "Ingest successfull";

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
