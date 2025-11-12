package de.intranda.goobi.plugins.model;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@JacksonXmlRootElement(localName = "directory")
public class MycoreDirectory {

	@JacksonXmlProperty(isAttribute = true)
	private OffsetDateTime modified;

	@JacksonXmlProperty(isAttribute = true)
	private String name;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "directory")
    private List<MycoreDirectory> directory;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "file")
    private List<MycoreFile> files;

}
