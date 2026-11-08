# 组织导入示例数据（M1 / FR-M1-04）

> 依据：`docs/design/03-M1-M2-账号组织课程设计.md` 第 290–291 行
> 「专业列：`name`；自然键 `school_id + name`」
> 「班级列：`major_name`、`grade`、`name`、`status`（可选，默认 active）；自然键 `major_id + grade + name`」

## 字段说明

### 专业（`majors-sample.csv`）

| 列 | 必填 | 说明 |
|---|---|---|
| `name` | 是 | 专业名称，≤100 字符，**学校内**唯一 |

系统**不存专业代码**（`majors` 表只有 `id / school_id / name / status`）。若要按教育部代码对接教务系统，需先加列（契约变更）。

### 班级（`classes-sample.csv`）

| 列 | 必填 | 说明 |
|---|---|---|
| `major_name` | 是 | 必须是本校**已导入且启用**的专业名 |
| `grade` | 是 | 入学届别，如 `2024`，≤20 字符 |
| `name` | 是 | 班名，如 `1班`，≤50 字符 |
| `status` | 否 | `active`（默认）/ `inactive` |

## 导入方式

管理端 → 「学校与组织」→ 专业 / 班级的导入按钮；或直接调用：

```bash
curl -X POST http://localhost:8090/api/v1/admin/majors/import \
  -H "Authorization: Bearer <管理员 JWT>" \
  -F "file=@docs/samples/org-import/majors-sample.csv"

curl -X POST http://localhost:8090/api/v1/admin/classes/import \
  -H "Authorization: Bearer <管理员 JWT>" \
  -F "file=@docs/samples/org-import/classes-sample.csv"
```

**顺序不能颠倒**：班级依赖专业先存在。

## 导入规则（已在代码中实现）

| 规则 | 行为 |
|---|---|
| 单文件上限 | 500 行 / 5 MB |
| 任一行出错 | **整批不写入**，响应 `details.errors[]` 含行号 / 列 / 原因 |
| 重复专业名 | 幂等更新（不报错） |
| 重复班级自然键 | 幂等更新 |
| 专业不存在 / 已停用 | 该行报错，整批失败 |
| 超过列宽 | 该行报错（专业名 >100、年级 >20、班名 >50） |

## 拿到真实数据后

1. 用真实专业名替换 `majors-sample.csv` 的 `name` 列；
2. 用真实班级替换 `classes-sample.csv`（`major_name` 必须与第 1 步的名称**逐字一致**）；
3. 保留表头行，UTF-8 编码（Excel 另存为 CSV 时选 UTF-8）。
