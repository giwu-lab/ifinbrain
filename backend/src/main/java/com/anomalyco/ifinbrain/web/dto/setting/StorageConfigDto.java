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
        /**
         * Access Key 真实状态：missing（未存）/ ok（已存且能解密）/ failed（已存但当前主密钥无法解密）。
         * 前端用此字段显示对应的状态文案，与解析页 API Key 三态一致。
         */
        private String accessKeyStatus = "missing";
        /** Secret Key 真实状态：missing / ok / failed。 */
        private String secretKeyStatus = "missing";
    }
}