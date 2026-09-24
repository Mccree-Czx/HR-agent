-- HR Agent 生产库结构(MySQL 8+)
-- 执行方式:mysql -u root -p hr_agent < schema.sql

CREATE TABLE IF NOT EXISTS jd (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    title         VARCHAR(200) NOT NULL COMMENT '岗位名称',
    external_jd   TEXT COMMENT '对外 JD',
    internal_notes TEXT COMMENT '对内寻源关键词/备注',
    salary_min    INT COMMENT '薪资下限(元/月)',
    salary_max    INT COMMENT '薪资上限(元/月)',
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/CLOSED',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='岗位JD';

CREATE TABLE IF NOT EXISTS liepin_account (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    name             VARCHAR(100) NOT NULL COMMENT '账号备注名',
    user_data_dir    VARCHAR(500) COMMENT 'Chrome user-data-dir 路径',
    login_status     VARCHAR(20) NOT NULL DEFAULT 'NEED_SCAN' COMMENT 'NORMAL/NEED_SCAN/RESTRICTED',
    circuit_breaker  TINYINT(1) NOT NULL DEFAULT 0 COMMENT '熔断标记:1=停止调度',
    greet_mode       VARCHAR(20) NOT NULL DEFAULT 'AUTO' COMMENT '打招呼模式 AUTO/MANUAL',
    daily_greet_quota INT NOT NULL DEFAULT 20 COMMENT '每日打招呼配额上限',
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='猎聘账号';

CREATE TABLE IF NOT EXISTS search_task (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    jd_id          BIGINT NOT NULL,
    account_id     BIGINT NOT NULL,
    keywords       VARCHAR(500) COMMENT '搜索关键词',
    status         VARCHAR(20) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED/RUNNING/DONE/FAILED',
    lease_expire_at DATETIME COMMENT '租约到期时间(心跳续约)',
    retry_count    INT NOT NULL DEFAULT 0,
    error_msg      VARCHAR(1000),
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_status (status),
    KEY idx_jd (jd_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='搜索任务';

CREATE TABLE IF NOT EXISTS candidate (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    resume_id     VARCHAR(100) NOT NULL COMMENT '猎聘简历ID',
    name          VARCHAR(100) NOT NULL,
    snapshot      TEXT COMMENT '在线简历快照(结构化字段 JSON)',
    score         INT COMMENT '最新评分',
    pass_status   VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PASS/FAIL/PENDING',
    jd_id         BIGINT COMMENT '来源岗位',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_resume (resume_id, name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='候选人';

CREATE TABLE IF NOT EXISTS score_record (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    candidate_id BIGINT NOT NULL,
    jd_id        BIGINT NOT NULL,
    score        INT NOT NULL COMMENT '评分',
    reason       TEXT COMMENT 'AI 评分理由',
    rule_version VARCHAR(50) COMMENT '评分细则版本',
    model        VARCHAR(50) COMMENT '模型名',
    token_usage  INT COMMENT 'token 消耗',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_candidate (candidate_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='评分记录';

CREATE TABLE IF NOT EXISTS greeting_record (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    candidate_id BIGINT NOT NULL,
    account_id   BIGINT NOT NULL COMMENT '执行打招呼的账号',
    message      TEXT COMMENT '打招呼话术',
    status       VARCHAR(20) NOT NULL DEFAULT 'SENT' COMMENT 'SENT/AGREED/NO_RESPONSE',
    mode         VARCHAR(20) NOT NULL COMMENT 'AUTO/MANUAL',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_candidate (candidate_id) COMMENT '全局防重复联系:同一候选人仅可被联系一次',
    KEY idx_account (account_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='打招呼记录';

CREATE TABLE IF NOT EXISTS resume_file (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    candidate_id BIGINT NOT NULL,
    bucket       VARCHAR(100) COMMENT '存储桶/目录',
    object_key   VARCHAR(500) COMMENT '对象键/相对路径',
    format       VARCHAR(20) COMMENT 'pdf/docx 等',
    size         BIGINT COMMENT '字节数',
    sha256       VARCHAR(64),
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_candidate (candidate_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='简历文件元数据';

CREATE TABLE IF NOT EXISTS sys_user (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(100) NOT NULL,
    password   VARCHAR(200) NOT NULL COMMENT 'BCrypt',
    role       VARCHAR(20) NOT NULL DEFAULT 'HR' COMMENT 'ADMIN/HR',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='团队用户';

CREATE TABLE IF NOT EXISTS user_jd (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    jd_id   BIGINT NOT NULL,
    UNIQUE KEY uk_user_jd (user_id, jd_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户岗位分配';

CREATE TABLE IF NOT EXISTS op_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator    VARCHAR(100) COMMENT '操作人用户名',
    action      VARCHAR(50) COMMENT '动作',
    target_type VARCHAR(50) COMMENT '对象类型',
    target_id   VARCHAR(50) COMMENT '对象ID',
    detail      TEXT,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='审计日志';
