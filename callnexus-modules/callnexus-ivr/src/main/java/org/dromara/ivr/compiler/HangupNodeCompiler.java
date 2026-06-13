package org.dromara.ivr.compiler;

import org.springframework.stereotype.Component;

@Component
public class HangupNodeCompiler implements IvrNodeCompiler {

    @Override
    public String nodeType() {
        return "HANGUP";
    }

    @Override
    public void validate(IvrNodeValidationContext context) {
        context.requireTerminal();
    }

    @Override
    public void compile(IvrNodeContext context) {
        context.renderSupport().appendNodeStart(context.xml(), context.flow().getId(), context.node());
        context.renderSupport().appendHangup(context.xml(), "NORMAL_CLEARING");
        context.renderSupport().appendNodeEnd(context.xml());
    }
}
