package org.dromara.resource.freeswitch.xml.directory;

import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.sip.domain.response.SipDirectoryAccountResponse;
import org.springframework.stereotype.Component;

@Component
public class FreeSwitchDirectoryXmlRenderer {
    public String render(SipDirectoryAccountResponse account) {
        String extension = FreeSwitchXmlRenderer.escape(account.getExtension());
        String domain = FreeSwitchXmlRenderer.escape(account.getDomain());
        String displayName = FreeSwitchXmlRenderer.escape(account.getDisplayName());
        String password = FreeSwitchXmlRenderer.escape(account.getAuthPassword());
        return """
            <document type="freeswitch/xml">
              <section name="directory">
                <domain name="%s">
                  <params>
                    <param name="dial-string" value="{sip_invite_domain=${dialed_domain},presence_id=${dialed_user}@${dialed_domain}}${sofia_contact(${dialed_user}@${dialed_domain})}"/>
                  </params>
                  <groups>
                    <group name="default">
                      <users>
                        <user id="%s">
                          <params>
                            <param name="password" value="%s"/>
                          </params>
                          <variables>
                            <variable name="user_context" value="default"/>
                            <variable name="effective_caller_id_number" value="%s"/>
                            <variable name="effective_caller_id_name" value="%s"/>
                          </variables>
                        </user>
                      </users>
                    </group>
                  </groups>
                </domain>
              </section>
            </document>
            """.formatted(domain, extension, password, extension, displayName);
    }
}
