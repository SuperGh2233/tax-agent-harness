# T03 种子存储

状态：active。业务字段来源仅 docs/BUSINESS_CONTRACT.md 与官方原件。企业、用户各自文本主键；权限主键(user_id, enterprise_id)，枚举保留中文；发票和流水主键(enterprise_id,id)。日期 ISO yyyy-MM-dd 文本；金额 amount_cents INTEGER，禁止浮点，流水金额正数；用途和备注 NOT NULL 空字符串保留。规则主键(id,version)，启用/已停用、生效日期和原文保存，适用企业由 rule_enterprises 关联；不推定失效日期。

显式 --initialize-data [--database=路径] CLI 在启动 Spring 前执行并退出，默认 data/harness.db。schema 与全部种子在一个事务中执行，ON CONFLICT 主键 DO NOTHING 保留已有修改，其他约束错误回滚。普通启动不连接数据库。不提供清库或覆盖模式；恢复基线使用新的数据库路径。自动化契约验证见 DataInitializerTest。
