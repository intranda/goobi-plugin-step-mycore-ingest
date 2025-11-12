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

	@JacksonXmlProperty(isAttribute = true)
	private String mycoreChecksum;

	@JacksonXmlProperty(isAttribute = true)
	private String mycoreMimeType;

	@JacksonXmlProperty(isAttribute = true)
	private Long mycoreSize;
	
	@JacksonXmlProperty(isAttribute = true)
	private String mycoreUrl;
	
	@JacksonXmlProperty(isAttribute = true)
	private String goobiFileType;
	
	@JacksonXmlProperty(isAttribute = true)
	private String goobiFilePath;

	@JacksonXmlProperty(isAttribute = true)
	private String goobiChecksum;

	@JacksonXmlProperty(isAttribute = true)
	private Long goobiSize;
	
}
