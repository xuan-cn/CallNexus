package org.dromara.ivr.compiler;

public interface IvrNodeCompiler {

    String nodeType();

    void validate(IvrNodeValidationContext context);

    void compile(IvrNodeContext context);
}
