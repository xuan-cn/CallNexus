package org.dromara.ivr.compiler;

import org.springframework.stereotype.Component;

@Component
public class StartNodeCompiler implements IvrNodeCompiler {

    @Override
    public String nodeType() {
        return "START";
    }

    @Override
    public void validate(IvrNodeValidationContext context) {
        context.requireSingleDefaultRoute();
    }

    @Override
    public void compile(IvrNodeContext context) {
        context.renderSupport().appendNodeStart(context.xml(), context.flow().getId(), context.node());
        context.renderSupport().appendTransfer(context.xml(), context.flow().getId(), context.graph().defaultTarget(context.node().id()));
        context.renderSupport().appendNodeEnd(context.xml());
    }
}
