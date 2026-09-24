# HR-agent(猎聘)

基于猎聘的 HR Agent:AI 按岗位 JD 主动搜索猎聘人才库,评分筛选在线简历,通过者自动打招呼,候选人同意后拉取完整简历入库。

## 架构

单机集中式:

- **backend/**:Spring Boot 3 + Java 17 + MyBatis-Plus + MySQL,通过子进程驱动 liepin-cli(Node.js CLI,浏览器自动化)
- **frontend/**:Vue3 + Element Plus 管理后台(岗位/账号/用户/搜索任务/候选人评分/打招呼/简历库)
- **deploy/**:部署脚本(fork 版 liepin-cli 安装、MySQL + MinIO docker-compose)
- AI 层:AgentScope Java 框架,底层接国内模型 API(DeepSeek/Qwen/GLM)
- 简历存储:MinIO 对象存储 + MySQL 元数据(StorageService 抽象,可切本地文件系统)

## 开发环境要求

- JDK 17+
- Maven 3.9+
- Node.js 20+ / npm
- MySQL 8+(本地开发)
- Chrome(阶段 2 起,liepin-cli 需要,且必须为有头模式)
- Docker(可选,MinIO 部署)

## 本地启动

```bash
# 1. 建库(首次)
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS hr_agent DEFAULT CHARACTER SET utf8mb4;"
mysql -uroot -p hr_agent < backend/src/main/resources/sql/schema.sql

# 2. 后端(默认连 localhost:3306,root;可用 DB_PASSWORD 等环境变量覆盖)
cd backend && mvn spring-boot:run
# 首次启动自动创建管理员:admin / admin123(请尽快修改)

# 3. 前端(开发模式,代理 /api 到 8080)
cd frontend && npm install && npm run dev
```

## 测试

```bash
cd backend && mvn test   # H2 内存库,16 个用例覆盖 DAO/接口/权限
```

## 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| DB_HOST / DB_PORT / DB_NAME | localhost / 3306 / hr_agent | MySQL 连接 |
| DB_USERNAME / DB_PASSWORD | root / (空) | MySQL 凭证 |
| JWT_SECRET | (dev 默认值) | 生产必须修改 |
| JWT_EXPIRE_HOURS | 12 | token 有效期 |
| SERVER_PORT | 8080 | 后端端口 |

## 阶段进度

- [x] 阶段 1:项目骨架 + 基础数据(JD/账号/用户管理、JWT 权限、审计日志、前端骨架)
- [ ] 阶段 2:能力 spike + liepin-cli 封装与搜索
- [ ] 阶段 3:AgentScope 评分 Agent + 打招呼
- [ ] 阶段 4:简历入库(MinIO)+ 台账 + 权限
- [ ] 阶段 5:集成验证与运维加固

## 合规提示

本系统通过浏览器自动化操作猎聘,存在账号被限制/封禁的合规风险,使用者需自行评估并承担相应责任。详见实施计划中的《合规与风险声明》。
