-- 为已发布的 query-page 补默认 icon = 'Document'，保持与 PublishDialog 默认值一致。
-- 只影响 query_id 非 NULL 且 icon 为 NULL/空的行（不影响未来用户主动选过的 icon）。
UPDATE sys_menu
   SET icon = 'Document'
 WHERE query_id IS NOT NULL
   AND (icon IS NULL OR icon = '');