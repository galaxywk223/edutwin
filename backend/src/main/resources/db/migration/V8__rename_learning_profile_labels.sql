UPDATE lms_lesson
SET title = '认识你的学情画像',
    body = '本课程通过学习行为形成可追踪的学情画像。完成练习后，系统会异步更新掌握度、学业风险、学习计划和教师统计。'
WHERE title = '认识你的学习孪生'
  AND body = '本课程通过学习行为构建可验证的学习孪生。完成练习后，系统会异步更新掌握度、风险、学习计划和教师统计。';

UPDATE lms_assessment
SET description = '用两道客观题检查对学情分析闭环的理解。'
WHERE title = '第一课小测'
  AND description = '用两道客观题检查对学习孪生闭环的理解。';

UPDATE lms_assessment_question
SET options_json = JSON_SET(options_json, '$[0].label', '学情画像与学习计划')
WHERE prompt = '学生提交答案后，哪一项会异步刷新？'
  AND JSON_UNQUOTE(JSON_EXTRACT(options_json, '$[0].choiceId')) = 'twin'
  AND JSON_UNQUOTE(JSON_EXTRACT(options_json, '$[0].label')) = '孪生快照与学习计划';
