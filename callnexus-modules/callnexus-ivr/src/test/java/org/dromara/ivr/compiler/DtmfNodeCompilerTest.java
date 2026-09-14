package org.dromara.ivr.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.ivr.domain.IvrFlow;
import org.dromara.ivr.graph.IvrEdgeDefinition;
import org.dromara.ivr.graph.IvrGraphDefinition;
import org.dromara.ivr.graph.IvrNodeDefinition;
import org.dromara.ivr.support.IvrDialplanRenderSupport;
import org.dromara.ivr.support.IvrMediaPathResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("dev")
class DtmfNodeCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DtmfNodeCompiler compiler = new DtmfNodeCompiler();
    private final IvrDialplanRenderSupport renderSupport = new IvrDialplanRenderSupport();
    private final IvrMediaPathResolver mediaPathResolver = new IvrMediaPathResolver(null, null, null) {
        @Override
        public void validatePublishedForGroup(Long mediaId, Long nodeGroupId) {
        }

        @Override
        public String resolveTargetPath(Long mediaId, Long nodeId) {
            return mediaId != null && mediaId == 20L ? "/audio/invalid.wav" : "/audio/menu.wav";
        }
    };

    @Test
    void shouldCompileConfiguredDigitsAndFailureRoute() throws Exception {
        IvrFlow flow = flow();
        IvrNodeDefinition menu = menu("""
            {"mediaId":10,"invalidMediaId":20,"inputTimeoutSeconds":6,"maxAttempts":2}
            """);
        IvrGraphDefinition graph = graph(menu, List.of(
            new IvrEdgeDefinition("e1", "menu", "sales", "1"),
            new IvrEdgeDefinition("e3", "menu", "service", "3"),
            new IvrEdgeDefinition("ef", "menu", "hangup", "FAILURE")
        ));
        StringBuilder xml = new StringBuilder();

        compiler.validate(new IvrNodeValidationContext(flow, graph, menu, mediaPathResolver));
        compiler.compile(new IvrNodeContext(
            "000000", flow, 7L, "example.test", "10086", graph, menu, xml, renderSupport, mediaPathResolver
        ));

        assertThat(xml.toString())
            .contains("application=\"unset\" data=\"ivr_digit_menu\"")
            .contains("play_and_get_digits\" data=\"1 1 2 6000 # /audio/menu.wav /audio/invalid.wav ivr_digit_menu [13] 2000")
            .contains("callnexus_ivr_99_menu_FAILURE XML ${context}")
            .contains("destination_number\" expression=\"^callnexus_ivr_99_menu_FAILURE$\"")
            .contains("transfer\" data=\"callnexus_ivr_99_hangup XML ${context}\"");
    }

    @Test
    void shouldRequireFailureRoute() throws Exception {
        IvrFlow flow = flow();
        IvrNodeDefinition menu = menu("{" + "\"mediaId\":10" + "}");
        IvrGraphDefinition graph = graph(menu, List.of(new IvrEdgeDefinition("e1", "menu", "sales", "1")));

        assertThatThrownBy(() -> compiler.validate(new IvrNodeValidationContext(flow, graph, menu, mediaPathResolver)))
            .isInstanceOf(ServiceException.class)
            .hasMessage("请为按键菜单配置一条失败处理路由");
    }

    private IvrFlow flow() {
        IvrFlow flow = new IvrFlow();
        flow.setId(99L);
        flow.setNodeGroupId(8L);
        return flow;
    }

    private IvrNodeDefinition menu(String config) throws Exception {
        return new IvrNodeDefinition("menu", "DTMF", "按键菜单", objectMapper.readTree(config));
    }

    private IvrGraphDefinition graph(IvrNodeDefinition menu, List<IvrEdgeDefinition> edges) {
        return new IvrGraphDefinition(List.of(menu), edges, Map.of(menu.id(), menu), Map.of(menu.id(), edges));
    }
}
