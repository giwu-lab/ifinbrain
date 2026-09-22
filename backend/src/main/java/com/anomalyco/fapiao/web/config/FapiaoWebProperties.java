package com.anomalyco.fapiao.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "fapiao-web")
public class FapiaoWebProperties {

    private final Cors cors = new Cors();
    private final Storage storage = new Storage();
    private final Security security = new Security();

    @Data
    public static class Security {
        /** 主密钥（>=32 bytes），用于 AES-256-GCM 加密 API Key。 */
        private String masterKey;
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