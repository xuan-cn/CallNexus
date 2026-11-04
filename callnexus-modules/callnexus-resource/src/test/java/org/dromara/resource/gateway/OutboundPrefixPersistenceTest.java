package org.dromara.resource.gateway;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.dromara.resource.gateway.domain.FreeSwitchGateway;
import org.dromara.resource.outboundline.domain.OutboundLinePolicy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class OutboundPrefixPersistenceTest {
    @ParameterizedTest
    @ValueSource(classes = {FreeSwitchGateway.class, OutboundLinePolicy.class})
    void clearedPrefixStillAppearsInUpdateSql(Class<?> type) throws Exception {
        assertPrefixAssignment(type, null);
    }

    @ParameterizedTest
    @ValueSource(classes = {FreeSwitchGateway.class, OutboundLinePolicy.class})
    void nonEmptyPrefixStillAppearsInUpdateSql(Class<?> type) throws Exception {
        assertPrefixAssignment(type, "9");
    }

    private void assertPrefixAssignment(Class<?> type, String prefix) throws Exception {
        var configuration = new MybatisConfiguration();
        var table = TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "prefix-test"), type);
        Object entity = type.getDeclaredConstructor().newInstance();
        type.getMethod("setId", Long.class).invoke(entity, 1L);
        type.getMethod("setOutboundPrefix", String.class).invoke(entity, prefix);
        var sqlSource = new XMLLanguageDriver().createSqlSource(configuration,
            "<script>UPDATE " + table.getTableName() + " <set>" + table.getAllSqlSet(false, "et.")
                + "</set> WHERE id=#{et.id}</script>", Map.class);
        var sql = sqlSource.getBoundSql(Map.of("et", entity));
        assertTrue(sql.getSql().contains("outbound_prefix=?"), sql.getSql());
        assertTrue(sql.getParameterMappings().stream().anyMatch(mapping -> "et.outboundPrefix".equals(mapping.getProperty())));
        assertEquals(prefix, type.getMethod("getOutboundPrefix").invoke(entity));
    }
}
