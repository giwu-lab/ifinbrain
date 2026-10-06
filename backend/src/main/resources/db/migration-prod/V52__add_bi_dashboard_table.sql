-- V52: BI 大屏表 sys_bi_dashboard
--
-- 与 sys_form_design 同构（fields_json → layout_json）：组件与画布强绑定，
-- 位置/尺寸/层级/数据绑定都存在 layout_json 里，不拆独立 widget 表。
-- 拆表只会带来 N+1 与一致性成本，等 P2 做「组件模板库 / 跨大屏复用」时再考虑。
--
-- 字段说明：
--   code            业务编码，全局唯一（URL / 复制时生成 _copy 后缀都靠它）
--   layout_json     画布定义（version / canvas / theme / widgets），TEXT 大 JSON
--   theme_json      大屏级主题覆盖；播放页可直接读，省掉每次 parse layout
--   refresh_interval 自动刷新秒数；0 = 不自动刷新
--   status          0=草稿 / 1=已发布
--   sort            轮播排序
--
-- created_by 不建外键 —— 与 sys_query / sys_form_design 一致（created_by 允许 'system'）。
-- 时间列用 TIMESTAMP（不用 TIMESTAMPTZ）：全库 created_at/updated_at 统一 TIMESTAMP，
-- JPA 侧统一 LocalDateTime；本表单独用 TIMESTAMPTZ 会让 published_at 的时区语义
-- 与其它表不一致，收益为零。

CREATE TABLE IF NOT EXISTS sys_bi_dashboard (
  id               BIGSERIAL     PRIMARY KEY,
  code             VARCHAR(64)  NOT NULL UNIQUE,
  name             VARCHAR(128) NOT NULL,
  description      VARCHAR(512),
  layout_json      TEXT         NOT NULL,
  theme_json       TEXT,
  refresh_interval INTEGER      NOT NULL DEFAULT 0,
  status           SMALLINT     NOT NULL DEFAULT 0,
  published_at     TIMESTAMP,
  sort             INTEGER      NOT NULL DEFAULT 0,
  created_by       VARCHAR(64)  NOT NULL,
  created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 列表页主查询：status 过滤 + sort 排序；轮播（只看已发布）也走同一索引
CREATE INDEX IF NOT EXISTS idx_bi_dash_status ON sys_bi_dashboard (status, sort);
