# M3 教师意图：数据管线与线性 baseline

> 范围：离线训练与数据导出。生产 AI 服务不引入 scikit-learn / numpy；
> 第一版模型产物由 Java `LinearTeacherIntentClassifier` 本地推理。

## 1. 标签

与 Java `TeacherIntent` 一致，多标签：

| label | 含义 |
|---|---|
| `ask_class` | 向全班提问 |
| `ask_student` | 向某个学生提问 |
| `call_on` | 点名让学生回答 |
| `prompt_student_question` | 让学生提问 |
| `manage_discipline` | 课堂纪律 / 提醒 |
| `comment_answer` | 评论学生回答 |
| `end_dialogue` | 坐下 / 结束本轮问答 |
| `end_qa` | 结束问答、回到讲授 |
| `other` | 讲解、过渡、寒暄 |

一句话可以多标签，例如「很好，请坐」= `comment_answer` + `end_dialogue`。

## 2. 数据来源

1. **真实 ASR 文本 + 动作弱标注**（`export_weak_labels.py`）：
   - `teacher.reply` 事件带 `text_id` / `intent`，是状态机接受的动作标签；
   - `teacher.called_on` / `teacher.marked` / `teacher.management_action` /
     `classroom.question_asked` / `dialogue.ended` / `classroom.qa_ended`
     在文本结束后的窗口内作为弱标签。
2. **受控课例 + 人工标注**：作为 golden test，不能只用弱标注评估。
3. **合成种子**（`seed_data.py`）：只用于打通管线和冷启动，不代表真实分布。

## 3. 命令

```bash
cd ai
python3 -m venv .venv-train
.venv-train/bin/pip install -r training/teacher_intent/requirements.txt

# 生成种子数据（可选；train.py 在缺失时会自动生成）
PYTHONPATH=. .venv-train/bin/python -m training.teacher_intent.seed_data

# 训练 + 导出模型 / 指标
PYTHONPATH=. .venv-train/bin/python -m training.teacher_intent.train \
  --data training/teacher_intent/data/seed.jsonl

# 加入真实弱标注（先用 exporter 导出）
PYTHONPATH=. .venv-train/bin/python -m training.teacher_intent.export_weak_labels \
  --dsn 'postgresql://shijiangtai:shijiangtai_dev@localhost:5432/shijiangtai' \
  --out training/teacher_intent/data/weak.jsonl

# 生成人工标注表（Excel / LibreOffice 可直接打开）
PYTHONPATH=. .venv-train/bin/python -m training.teacher_intent.annotate \
  --input training/teacher_intent/data/weak.jsonl \
  --out training/teacher_intent/data/annotation.csv \
  --limit 500
# 在表格里把每个 label 改成 1/0；讲解 / 过渡句请显式把 other 设为 1。
PYTHONPATH=. .venv-train/bin/python -m training.teacher_intent.merge_annotations \
  --annotations training/teacher_intent/data/annotation.csv \
  --out training/teacher_intent/data/golden.jsonl

PYTHONPATH=. .venv-train/bin/python -m training.teacher_intent.train \
  --data training/teacher_intent/data/golden.jsonl \
  --data training/teacher_intent/data/weak.jsonl \
  --data training/teacher_intent/data/seed.jsonl
```

产物：

- `ai/models/teacher_intent/v1/model.json`：Java / Python 共用；
- `ai/models/teacher_intent/v1/metrics.json`：每标签 P/R/F1、阈值、数据量。

## 4. 模型格式

纯 JSON，无 pickle：

```json
{
  "format": "linear-char-ngram-v1",
  "version": "teacher-intent-linear-v1",
  "labels": ["ask_class", "..."],
  "ngram_min": 1,
  "ngram_max": 3,
  "vocabulary": ["同", "同学", "..."],
  "idf": [1.23, "..."],
  "weights": [[0.1, "..."], "..."],
  "intercepts": [0.2, "..."],
  "thresholds": {"ask_class": 0.75, "..."}
}
```

训练使用字符 1–3 gram TF-IDF + 每标签独立 logistic regression；推理只做稀疏点积 +
sigmoid。`infer.py` 是纯 Python 参考实现，与 Java 实现使用同一套
normalize / mask / TF-IDF / sigmoid 规则。

## 5. Java 启用

默认仍使用 Java 规则，不需要模型文件。配置模型路径后启用混合分类：

```bash
# 路径按 Java 进程工作目录解析；最稳妥是绝对路径。
M3_CLASSROOM_TEACHER_INTENT_MODEL_PATH=/absolute/path/to/ai/models/teacher_intent/v1/model.json
# 从 server/ 启动时可用 ../ai/models/teacher_intent/v1/model.json
# 或 Spring 参数 --m3.classroom.teacher-intent.model-path=...

# 模式：off（默认，纯规则）/ shadow（只记录模型预测，不改变课堂）/ active（混合）
M3_CLASSROOM_TEACHER_INTENT_MODE=shadow

# active 模式建议只开放低风险意图；call_on / end_* 继续走规则
M3_CLASSROOM_TEACHER_INTENT_ACTIVE_LABELS=ask_class,ask_student,prompt_student_question,comment_answer
```

`active` 且标签包含 `ask_class` / `ask_student` 时，讲解态高置信（≥0.85）提问可直接
触发语音问答；`shadow` 只记录日志，不改变课堂。

- 混合策略：模型与规则按标签取最大置信度；`student_name` / `polarity` 仍来自规则；
  模型加载失败自动回退规则。
- 高风险动作阈值仍由 `SessionActor` 控制：follow-up 0.75、call-on 0.85、
  end_dialogue / end_qa 0.90。
- 第一版不做自动评价 / 自动标记；模型只影响意图，不改变事件契约。

## 6. 评估与数据治理

- 按 `session_id` / 模板分组切分，禁止同节课句子泄漏到测试集。
- 种子数据指标只能证明管线可运行，不能作为上线依据。
- 真实 golden set 至少 300–500 条人工标注 ASR 文本，重点看：
  - 关键动作 precision（call_on / end_dialogue / end_qa）目标 ≥ 0.95；
  - 每标签召回、macro F1、校准误差；
  - Java 推理 p95 延迟（目标 < 10 ms）。
- 训练前把学生姓名替换为 `某同学`；真实姓名只留在受控 DB，不进入模型文件。
- 真实课堂数据需取得录制 / 标注授权，并设定保留期与访问控制。
