# Teacher-intent linear baseline v1

- 训练脚本：`ai/training/teacher_intent/train.py`
- 数据：`ai/training/teacher_intent/data/seed.jsonl`（**合成种子**，仅用于打通管线）
- 格式：`linear-char-ngram-v1`，字符 1–3 gram TF-IDF + 每标签独立 logistic regression
- 推理：Java `LinearTeacherIntentClassifier`（本地、无网络、无 Python 运行时依赖）
- 版本：`teacher-intent-linear-v1`

## 启用

生产默认仍使用规则分类。配置模型路径后启用“模型 + 规则”混合分类：

```bash
# 路径按 Java 进程工作目录解析；最稳妥是绝对路径。
M3_CLASSROOM_TEACHER_INTENT_MODEL_PATH=/absolute/path/to/ai/models/teacher_intent/v1/model.json
# 从 server/ 启动时可用 ../ai/models/teacher_intent/v1/model.json
# 或 --m3.classroom.teacher-intent.model-path=...

# 模式：off（默认，纯规则）/ shadow（只记录模型预测，不改变课堂）/ active（混合）
M3_CLASSROOM_TEACHER_INTENT_MODE=shadow

# active 模式建议只开放低风险意图；call_on / end_* 继续走规则
M3_CLASSROOM_TEACHER_INTENT_ACTIVE_LABELS=ask_class,ask_student,prompt_student_question,comment_answer
```

`active` 且标签包含 `ask_class` / `ask_student` 时，讲解态高置信（≥0.85）提问可直接
触发语音问答；`shadow` 只记录日志，不改变课堂。

模型加载失败、文件不存在或格式不匹配时自动回退规则，不影响启动。

## 重要限制

种子数据是模板生成的合成文本，**指标不代表真实课堂效果**。真实上线前必须：

1. 用 `export_weak_labels.py` 导出真实 `teacher.reply` / 按钮弱标注；
2. 人工标注 300–500 条真实 ASR 文本作为 golden set；
3. 按 `session_id` / 模板分组切分后重新训练和评估；
4. 先在影子模式对照，再按意图逐步启用自动动作。

当前 `metrics.json` 的 macro F1 约 0.78（合成测试集），只能证明训练、导出、Java/Python
推理一致性与管线可用。
