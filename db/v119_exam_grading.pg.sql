-- v119 主观题阅卷（PostgreSQL）
-- 生产 KB_DDL_AUTO=validate：必须先执行本文件，再启动新后端。
-- pending：主观题（FILL/ESSAY）交卷时标记为待阅卷，不判分；阅卷后置 false 并写 score。
-- comment：阅卷批注（可选），学员在成绩详情可见。

ALTER TABLE kb_exam_answer ADD COLUMN IF NOT EXISTS pending BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE kb_exam_answer ADD COLUMN IF NOT EXISTS comment VARCHAR(500);

-- 待阅卷列表按 pending 过滤，用部分索引只索引 true 的行
CREATE INDEX IF NOT EXISTS idx_exam_answer_pending ON kb_exam_answer (pending) WHERE pending = TRUE;
