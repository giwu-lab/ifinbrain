package com.anomalyco.ifinbrain.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "ifinbrain")
public class iFinBrainProperties {

    private final Cors cors = new Cors();
    private final Storage storage = new Storage();
    private final Security security = new Security();

    @Data
    public static class Security {
        /** 主密钥（>=32 bytes），用于 AES-256-GCM 加密 API Key。 */
        private String masterKey;
        /**
         * 是否允许 CryptoUtil.decrypt() 接受非 v1: 前缀的明文值。
         * 仅用于一次性迁移期（review M17）；生产环境务必设 false。
         * 默认 true 保持向后兼容；迁移完成后在 application.yml 显式关掉：
         *   ifinbrain:
         *     security:
         *       allow-legacy-plaintext: false
         */
        private boolean allowLegacyPlaintext = true;
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>();
    }

    @Data
    public static class Storage {
        private String type = "local";
        private final Local local = new Local();
        private final S3 s3 = new S3();

        @Data
        public static class Local {
            private String root = "./data/storage";
        }

        @Data
        public static class S3 {
            private String endpoint;
            private String region = "us-east-1";
            private String bucket;
            private String accessKey;
            private String secretKey;
            private boolean pathStyle = true;
        }
    }
}