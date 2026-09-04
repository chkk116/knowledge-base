-- v118 默认词库种子（确保「默认词库」词数 != 0）
-- 幂等、自包含：
--   1) 默认词库不存在就先建；
--   2) 去重只针对「默认词库本身」，所以哪怕这些词已存在于别的词库，
--      默认词库也一定会被填满（这正是上一版"还是 0"的根因：全表去重导致整批跳过）；
--   3) 末尾带一条验证 SELECT，跑完直接看 word_count。
-- 执行方式：Navicat / 命令行 / 后端 datasource 控制台均可，无需重启。

-- 1) 确保「默认词库」存在（builtin=1，对应后端 SensitiveWordFilter 的查找条件）
INSERT INTO kb_banned_word_library (name, description, enabled, builtin, priority)
SELECT '默认词库', '系统默认词库', 1, 1, 0
WHERE NOT EXISTS (SELECT 1 FROM kb_banned_word_library WHERE name = '默认词库');

-- 1.5) 关键：把「孤儿词」（library_id 为 NULL 的历史词条）归入默认词库。
--      这是 dev 环境（ddl-auto=update 自动建表、跳过 v117 归库语句）词数显示 0 的真正根因。
UPDATE kb_banned_word b
JOIN (SELECT id FROM kb_banned_word_library WHERE name = '默认词库' ORDER BY id LIMIT 1) l
SET b.library_id = l.id
WHERE b.library_id IS NULL;

-- 2) 把种子词写进「默认词库」（去重仅限本库，保证本库非空）
INSERT INTO kb_banned_word (word, library_id, created_at)
SELECT w.word, lib.id, NOW()
FROM (
    SELECT '垃圾'            AS word UNION ALL
    SELECT '蠢货' UNION ALL
    SELECT '傻逼' UNION ALL
    SELECT '滚蛋' UNION ALL
    SELECT '广告加微信' UNION ALL
    SELECT '代开发票' UNION ALL
    SELECT '菠菜' UNION ALL
    SELECT '博彩' UNION ALL
    SELECT '澳门威尼斯人' UNION ALL
    SELECT '色情' UNION ALL
    SELECT '暴力' UNION ALL
    SELECT '枪支' UNION ALL
    SELECT '诈骗' UNION ALL
    SELECT 'cnm'  UNION ALL
    SELECT 'wcnm' UNION ALL
    SELECT 'nmsl' UNION ALL
    SELECT 'nmd'  UNION ALL
    SELECT 'tmd'  UNION ALL
    SELECT 'sb'   UNION ALL
    SELECT 'mdzz' UNION ALL
    SELECT '草你妈' UNION ALL
    SELECT '操你妈' UNION ALL
    SELECT '草泥马' UNION ALL
    SELECT '你妈的' UNION ALL
    SELECT '他妈的' UNION ALL
    SELECT '尼玛' UNION ALL
    SELECT '妈的' UNION ALL
    SELECT '妈了个逼' UNION ALL
    SELECT '傻屄' UNION ALL
    SELECT '贱人' UNION ALL
    SELECT '婊子' UNION ALL
    SELECT '妓女' UNION ALL
    SELECT '混蛋' UNION ALL
    SELECT '浑蛋' UNION ALL
    SELECT '王八蛋' UNION ALL
    SELECT '废物' UNION ALL
    SELECT '去死' UNION ALL
    SELECT '死全家' UNION ALL
    SELECT 'fuck'  UNION ALL
    SELECT 'shit'  UNION ALL
    SELECT 'bitch' UNION ALL
    SELECT 'asshole' UNION ALL
    SELECT 'bastard' UNION ALL
    SELECT 'damn'
) w
CROSS JOIN (SELECT id FROM kb_banned_word_library WHERE name = '默认词库' ORDER BY id LIMIT 1) lib
WHERE NOT EXISTS (
    SELECT 1 FROM kb_banned_word b WHERE b.word = w.word AND b.library_id = lib.id
);

-- 3) 验证：默认词库的词数（应 > 0）
SELECT id, name, enabled,
       (SELECT COUNT(*) FROM kb_banned_word b WHERE b.library_id = id) AS word_count
FROM kb_banned_word_library
WHERE name = '默认词库';
