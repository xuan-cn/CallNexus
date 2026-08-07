package org.dromara.test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class CallNexusMainTest {
    public static void main(String[] args) {
        String userName = "cnx_-ghjNsqEIknBbbYIqV25iydU";
        String passWord = "cnxs__FPuXtLDELzV3YQ-Tals_zldQG1BqanLr2UioAI1pOYYS6BQ";

        // 1. 拼接 username:password
        String credentials = userName + ":" + passWord;

        // 2. Base64 编码
        String encodedCredentials = Base64.getEncoder()
            .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        System.out.println("Basic ：" + encodedCredentials);

    }
}
