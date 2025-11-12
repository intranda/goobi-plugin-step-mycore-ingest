package de.intranda.goobi.plugins.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JacksonXmlRootElement(localName = "receipt")
public class IngestReceipt {

	@JsonFormat(pattern = "yyyy-MM-dd-HHmmssSSS")
    private LocalDateTime beginn = LocalDateTime.now();
	@JsonFormat(pattern = "yyyy-MM-dd-HHmmssSSS")
	private LocalDateTime end;
    
	private String status = "NEW";
    private String details = "";
    private String volume;
    private String derivative;
    
    private List<IngestFile> files = new ArrayList<>();
}