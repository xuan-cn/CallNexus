package org.dromara.ivr.compiler;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class IvrNodeCompilerRegistry {

    private final Map<String, IvrNodeCompiler> compilers;

    public IvrNodeCompilerRegistry(List<IvrNodeCompiler> compilers) {
        Map<String, IvrNodeCompiler> registered = new LinkedHashMap<>();
        for (IvrNodeCompiler compiler : compilers) {
            String nodeType = normalize(compiler.nodeType());
            IvrNodeCompiler duplicated = registered.putIfAbsent(nodeType, compiler);
            if (duplicated != null) {
                throw new IllegalStateException("重复的 IVR 节点编译器类型: " + nodeType);
            }
        }
        this.compilers = Map.copyOf(registered);
    }

    public IvrNodeCompiler require(String nodeType) {
        IvrNodeCompiler compiler = nodeType == null ? null : compilers.get(normalize(nodeType));
        if (compiler == null) {
            throw new ServiceException("IVR_NODE_TYPE_NOT_SUPPORTED");
        }
        return compiler;
    }

    private String normalize(String nodeType) {
        if (nodeType == null || nodeType.isBlank()) {
            throw new ServiceException("IVR_NODE_INVALID");
        }
        return nodeType.trim().toUpperCase(Locale.ROOT);
    }
}
