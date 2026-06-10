package org.dromara.resource.freeswitch.xmlcurl;

public interface FreeSwitchXmlCurlHandler {
    boolean supports(FreeSwitchXmlCurlRequest request);

    String handle(FreeSwitchXmlCurlRequest request);
}
