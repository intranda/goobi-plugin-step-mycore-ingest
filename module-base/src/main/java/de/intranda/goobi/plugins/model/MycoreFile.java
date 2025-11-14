package de.intranda.goobi.plugins.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter @Setter
public class MycoreFile {

	@JacksonXmlProperty(isAttribute = true)
	private OffsetDateTime modified;

	@JacksonXmlProperty(isAttribute = true)
	private String name;

	@JacksonXmlProperty(isAttribute = true)
	private String md5;

	@JacksonXmlProperty(isAttribute = true)
	private String mimeType;

	@JacksonXmlProperty(isAttribute = true)
	private Long size;

}
