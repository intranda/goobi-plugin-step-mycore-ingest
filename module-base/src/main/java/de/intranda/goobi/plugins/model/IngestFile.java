package de.intranda.goobi.plugins.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter @Setter
public class IngestFile {

	@JacksonXmlProperty(isAttribute = true)
	private OffsetDateTime modified;

	@JacksonXmlProperty(isAttribute = true)
	private String name;

	@JacksonXmlProperty(isAttribute = false)
	private String mycoreChecksum;

	@JacksonXmlProperty(isAttribute = false)
	private String mycoreMimeType;

	@JacksonXmlProperty(isAttribute = false)
	private Long mycoreSize;
	
	@JacksonXmlProperty(isAttribute = false)
	private String mycoreUrl;
	
	@JacksonXmlProperty(isAttribute = false)
	private String goobiFileType;
	
	@JacksonXmlProperty(isAttribute = false)
	private String goobiFilePath;

	@JacksonXmlProperty(isAttribute = false)
	private String goobiChecksum;

	@JacksonXmlProperty(isAttribute = false)
	private Long goobiSize;
	
	@JacksonXmlProperty(isAttribute = true)
	private boolean valid = false;

	@JacksonXmlProperty(isAttribute = true)
	private int uploadCounter;
	
}
