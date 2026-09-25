package com.anomalyco.ifinbrain.web.dto.setting;

import lombok.Data;

@Data
public class StorageConfigDto {
    private String type = "local";
    private Local local = new Local();
    private S3 s3 = new S3();

    @lombok.Data public static class Local { private String root = "./data/storage"; }
    @lombok.Data public static class S3 {
        private String endpoint;
        private String region = "us-east-1";
        private String bucket;
        private String accessKey;
        private String secretKey;
        private boolean pathStyle = true;
    }
}