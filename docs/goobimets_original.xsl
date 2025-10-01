<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0" 
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:mets="http://www.loc.gov/METS/"
    xmlns:mods="http://www.loc.gov/mods/v3"
    exclude-result-prefixes="mets mods">

    <xsl:output method="xml" indent="yes" encoding="UTF-8" media-type="text/xml"/>

    <xsl:param name="parentID"/>

    <xsl:template match="/">
        <xsl:variable name="title" select="/mets:mets/mets:dmdSec/mets:mdWrap/mets:xmlData/mods:mods/mods:part/mods:detail[@type='volume']/mods:number"/>
        <xsl:variable name="date" select="/mets:mets/mets:dmdSec/mets:mdWrap/mets:xmlData/mods:mods/mods:originInfo[@eventType='publication']/mods:dateIssued"/>

        <mycoreobject
            xmlns:xlink="http://www.w3.org/1999/xlink" ID="jportal_jpvolume_00000001">
            <structure>
                <parents class="MCRMetaLinkID">
                    <parent inherited="0" xlink:type="locator"
                        xlink:href="{$parentID}" />
                </parents>
            </structure>
            <metadata>
                <maintitles class="MCRMetaLangText" heritable="false" notinherit="true">
                    <maintitle inherited="0" form="plain"><xsl:value-of select="$title"/></maintitle>
                </maintitles>
                <dates class="JPMetaDate" heritable="false" notinherit="true">
                    <date type="published" inherited="0" date="{$date}" />
                </dates>
            </metadata>
            <service />
        </mycoreobject>

    </xsl:template>

</xsl:stylesheet>