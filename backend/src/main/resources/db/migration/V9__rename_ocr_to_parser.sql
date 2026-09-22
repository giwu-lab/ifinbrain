-- V9: 识别 API 重构为通用「解析 API」（parser）。
-- 命名统一：
--     ocr.*           -> parser.*
-- 此次重构是为了未来接入多种解析服务（自有解析、阿里云 OCR、腾讯云 OCR…），
-- 所有路径（extract / batch / health）现在都可由前端在 系统 → API 设置 页面配置。
-- 幂等：oc.* 不存在时跳过（fresh install）。

UPDATE sys_setting SET key_name = 'parser.enabled'        WHERE key_name = 'ocr.enabled';
UPDATE sys_setting SET key_name = 'parser.baseUrl'        WHERE key_name = 'ocr.baseUrl';
UPDATE sys_setting SET key_name = 'parser.apiKey'         WHERE key_name = 'ocr.apiKey';
UPDATE sys_setting SET key_name = 'parser.timeoutSeconds' WHERE key_name = 'ocr.timeoutSeconds';

-- 新增 3 个 path 配置 key（幂等：已存在则不覆盖， 让用户手动改后保持不变）
INSERT INTO sys_setting (key_name, value_text)
  SELECT 'parser.extractPath', '/extract'
  WHERE NOT EXISTS (SELECT 1 FROM sys_setting WHERE key_name = 'parser.extractPath');

INSERT INTO sys_setting (key_name, value_text)
  SELECT 'parser.batchPath', '/extract/batch'
  WHERE NOT EXISTS (SELECT 1 FROM sys_setting WHERE key_name = 'parser.batchPath');

INSERT INTO sys_setting (key_name, value_text)
  SELECT 'parser.healthPath', '/health'
  WHERE NOT EXISTS (SELECT 1 FROM sys_setting WHERE key_name = 'parser.healthPath');