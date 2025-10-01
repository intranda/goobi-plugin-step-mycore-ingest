package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.util.HashMap;

import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.goobi.beans.GoobiProperty;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.export.download.ExportMets;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
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
		
		System.out.println("xslt:     " + xslt);
		System.out.println("api:      " + mycoreApi);
		System.out.println("login:    " + mycoreLogin);
		System.out.println("password: " + mycorePassword);
		Path metsfile;
		
		// export the mets file
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
			return PluginReturnValue.ERROR;
		}
		
		// create volume in mycore
		String location = null;
		try {
			location = createVolumeInMyCore(xmlResult);
			System.out.println(location + " was given back by MyCore");
		} catch (IOException e) {
			log.error("Error while creating the volume", e);
			return PluginReturnValue.ERROR;
		}
		
		log.info("MycoreIngest step plugin executed");
		return PluginReturnValue.FINISH;
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
        
      

	
	private String createVolumeInMyCore(String sourceXml) throws IOException {
		HttpResponse<String> response = Unirest.post(mycoreApi + "objects")
		  .header("Content-Type", "application/xml")
		  .basicAuth(mycoreLogin, mycorePassword)
		  .body(sourceXml)
		  .asString();

		if (response.isSuccess()) {
		    String location = response.getHeaders().getFirst("location");
		    if (location != null) {
		    	return location;
		    } else {
		    	throw new IOException("No location could be found of created volume in MyCoRe.");
		    }
		} else {
			throw new IOException("Response of MyCoRe for creation of volume was not successful: " + response.getStatus() + " - " + response.getBody());
		}
	}
}
