package org.dromara;

import org.dromara.common.mybatis.config.MybatisPlusConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.context.annotation.Import;

/**
 * 启动程序
 *
 * @author Lion Li
 */

@SpringBootApplication
@Import(MybatisPlusConfig.class)
public class CallNexusApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CallNexusApplication.class);
        application.setApplicationStartup(new BufferingApplicationStartup(2048));
        application.run(args);
        System.out.println(" CallNexus Server 呼叫服务 启动成功");
    }

}
