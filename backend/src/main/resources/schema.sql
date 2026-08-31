-- 游戏百科知识库表结构(幂等,可重复执行)
-- 生产 MySQL / 本地 H2(MODE=MySQL)均可执行
--
-- 说明:表不带库名前缀,建在"连接串指定的库"上(多数据源/多环境隔离的关键)。
--  - MySQL:首次使用某库前先建库,再执行本脚本:
--      CREATE DATABASE IF NOT EXISTS ai_chat_dev CHARACTER SET utf8mb4;
--      CREATE DATABASE IF NOT EXISTS ai_chat_prod CHARACTER SET utf8mb4;
--      mysql -u root -p ai_chat_dev < schema.sql
--  - H2(本地):直接建在当前库,无需 CREATE SCHEMA。

CREATE TABLE IF NOT EXISTS game (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  name        VARCHAR(100) NOT NULL,
  category    VARCHAR(50),
  platform    VARCHAR(100),
  publisher   VARCHAR(100),
  release_date DATE,
  summary     TEXT,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS article (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  game_id     BIGINT NOT NULL,
  title       VARCHAR(200) NOT NULL,
  type        VARCHAR(30),
  source      VARCHAR(200),
  content     MEDIUMTEXT,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_article_game (game_id)
);

CREATE TABLE IF NOT EXISTS chunk (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  game_id     BIGINT NOT NULL,
  article_id  BIGINT,
  content     TEXT NOT NULL,
  embedding   TEXT,                       -- 512 维 float 数组(JSON 字符串),持久化用
  meta        VARCHAR(255),
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_chunk_game (game_id)
);

-- 多会话(智能问答)
CREATE TABLE IF NOT EXISTS conversation (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  title       VARCHAR(200) NOT NULL,      -- 默认取首条提问前 20 字
  game_id     BIGINT,                     -- 会话绑定的游戏(空=通用)
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 会话消息
CREATE TABLE IF NOT EXISTS chat_message (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT NOT NULL,
  role            VARCHAR(20) NOT NULL,   -- user / assistant
  content         MEDIUMTEXT NOT NULL,
  references_json TEXT,                   -- 参考来源(JSON 数组,仅 assistant)
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_msg_conv (conversation_id)
);
