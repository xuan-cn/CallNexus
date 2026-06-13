package org.dromara.ivr.compiler;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

@Component
public class ExtensionNodeCompiler implements IvrNodeCompiler {

    @Override
    public String nodeType() {
        return "EXTENSION";
    }

    @Override
    public void validate(IvrNodeValidationContext context) {
        if (context.node().config().path("extension").asText().isBlank()) {
            throw new ServiceException("请填写转接分机号");
        }
        context.requireTerminal();
    }

    @Override
    public void compile(IvrNodeContext context) {
        context.renderSupport().appendNodeStart(context.xml(), context.flow().getId(), context.node());
        context.xml().append("      <action application=\"bridge\" data=\"user/")
            .append(context.renderSupport().escape(context.node().config().path("extension").asText()))
            .append("@")
            .append(context.renderSupport().escape(context.sipDomain()))
            .append("\"/>\n");
        context.renderSupport().appendNodeEnd(context.xml());
    }
}
