# Sprint 1：角色、分类与文章结构

实现日期：2026-11-16。本次在现有 POC 实例上建立四类角色、主分类空间、文章元数据、分类首页、示例文章和媒体验证页。默认访问地址仍为 `XWIKI_URL`（默认 `http://127.0.0.1:8080`）；本次本地验证因宿主 8080 被其他容器占用，使用 `http://127.0.0.1:8081`。

## 已安装组件

- XWiki Standard 17.10.15 LTS
- CKEditor Integration 17.10.15
- Chart.js Integration 1.6.3
- Embed macro 1.1.3
- Favorites Application 1.4.4
- Publication Workflow Application 2.4.9

Chart.js Integration 的完整许可与依赖仍需在对外分发前复核；其余扩展的许可记录见 `docs/components.md` 与扩展页面。

## 扩展安装清单

本脚本不安装扩展；在 Extension Manager 中安装下列版本后再运行初始化：

| 扩展 ID | 版本 |
| --- | --- |
| `org.xwiki.platform:xwiki-platform-distribution-flavor-mainwiki` | 17.10.15 |
| `org.xwiki.platform:xwiki-platform-ckeditor-ui` | 17.10.15 |
| `org.xwiki.contrib:application-chartjs-ui` | 1.6.3 |
| `org.xwiki.contrib.embed:embed-macro-ui` | 1.1.3 |
| `org.xwiki.contrib:application-favorites` | 1.4.4 |
| `org.xwiki.contrib.workflow-publication:xwiki-workflow-publication-application` | 2.4.9 |

## 角色与组

| 角色 | 组 | 说明 |
| --- | --- | --- |
| 管理 | `XWiki.LuxManagers` | 全局 admin 权限；成员包含 `XWiki.Admin` 和已有的 `XWiki.whiteshiro` |
| 编辑者 | `XWiki.LuxEditors` | 可读 Knowledge，可在 Drafts 创建、编辑、源码编辑 |
| 审核人 | `XWiki.LuxReviewers` | 可读 Knowledge 和 Drafts，不能编辑正文 |
| 读者 | `XWiki.LuxReaders` | 可读 Knowledge、评论、收藏，不能读 Drafts、不能编辑 |

测试账号：`poceditor`、`pocreviewer`、`pocreader`，密码在本地 `.secrets/<用户名>_password`，不提交 Git。

## 分类结构

```text
Knowledge
  ├── Technical   技术文档
  ├── Operations  操作手册
  ├── Faults      故障处理
  └── Archive     归档
```

- 主分类 = 空间，承载权限。
- 标签用于交叉分类。
- `Knowledge.DocumentLibrary` 汇总所有带 `ArticleClass` 的文章。
- 分类首页用 Velocity 查询列出本分类文章。

## 文章模型

### ArticleClass

`LuxPedia.Code.ArticleClass` 字段：

- `title` 标题
- `primaryCategory` 主分类
- `tagsText` 标签文本
- `owner` 负责人
- `status` 状态：draft / submitted / approved / rejected / published
- `summary` 摘要
- `applicableVersion` 适用版本
- `effectiveDate` 生效日期
- `relatedArticles` 相关文章
- `approvedRevision` 已批准版本
- `approvedBy` 批准人
- `approvedAt` 批准时间

### CategoryClass

`LuxPedia.Code.CategoryClass` 字段：

- `name` 分类名称
- `parent` 上级分类
- `owner` 分类负责人
- `template` 默认模板
- `description` 分类描述
- `sortOrder` 排序

## 页面

- `Main.WebHome`：LuxPedia 入口。
- `Knowledge.WebHome`：知识库入口。
- `Knowledge.DocumentLibrary`：文档库列表。
- `Knowledge.CategoryGuide`：分类说明。
- `Knowledge.CategoryIndex`：分类文章数量总览。
- `Knowledge.Technical.WebHome` 等：分类首页。
- `Knowledge.Technical.SampleArticle`：示例技术文档，含图片、视频、Chart.js 图表。
- `Knowledge.Technical.MacroShowcase`：图片、视频、图表示例。
- `Drafts.WebHome`：草稿区。
- `Drafts.SampleArticle`：草稿示例。
- `LuxPedia.Templates.Article`：文章模板。
- `LuxPedia.Code.BootstrapSprint1`：隐藏的类初始化脚本页。
- `LuxPedia.Code.SourceEditUIX`：隐藏的源码编辑菜单入口。

## 权限验证

已实际验证：

| 场景 | 结果 |
| --- | --- |
| 匿名访问 Knowledge / Drafts | 401 |
| 匿名直接访问创建页面 | 403 |
| 匿名编辑/评论/删除/注册 | 全局拒绝 |
| 读者访问 Knowledge | 200 |
| 读者访问 Drafts | 403 |
| 读者编辑 Knowledge | 403 |
| 编辑者编辑 Knowledge | 403 |
| 编辑者编辑 Drafts / 源码编辑 | 200 |
| 审核人查看 Drafts | 200 |
| 审核人编辑 Drafts / 源码编辑 | 403 |

## 编辑与媒体验证

- 默认编辑器设置为 Wysiwyg；`/bin/edit/Drafts/SampleArticle` 打开 CKEditor。
- 有编辑权限的用户会在内容菜单看到“源码编辑”按钮，直接打开 `?editor=wiki`；无编辑权限的审核人、读者和匿名用户不显示该按钮。
- CKEditor 工具栏也提供 Source 切换；本轮未重新执行浏览器点击保存，历史与回滚通过 REST/临时页面验证。
- Wiki 源码编辑 `/bin/edit/Drafts/SampleArticle?editor=wiki` 可用。
- 文章页面顶部通过 Velocity 读取 `ArticleClass`，显示主分类、状态、负责人、适用版本、标签和摘要。
- 图片：`architecture.svg` 在正文加载。
- 视频：`sample.mp4` 通过 Embed 宏在正文内嵌播放。
- 图表：Chart.js 宏在正文渲染 canvas。
- 目录：`{{toc/}}` 正常生成。
- 页面历史：`/history` 可访问。
- 回滚：在临时页面验证 1.1 → 2.1 → 回滚生成 3.1，内容恢复为 1.1。
- 附件：图片和视频附件字节与 `examples/attachments/` 一致。

## 自动化检查

```bash
XWIKI_URL=http://127.0.0.1:8081 python3 tests/sprint1_smoke.py
```

本次结果：14 项全部通过。

## 重新应用

脚本：

```bash
XWIKI_URL=http://127.0.0.1:8080 \
XWIKI_SUPERADMIN_USER=superadmin \
XWIKI_SUPERADMIN_PASSWORD='<临时超级管理员密码>' \
python3 scripts/apply_sprint1.py --apply
```

说明：

- `XWIKI_SUPERADMIN_*` 只在需要创建 `ArticleClass` / `CategoryClass` 时使用；类已存在时可省略。
- 脚本是幂等的：已存在的页面、组、用户、对象和权限会跳过或更新属性。
- 不覆盖已存在的用户密码；新用户密码写入 `.secrets/`。
- 运行前应先安装 CKEditor、Chart.js、Embed、Favorites、Publication Workflow；本脚本不负责扩展安装。

## 分类管理（管理员）

当前主分类：BOP-ACTIVE、BOP-PASSIVE，以及试点示例 Technical、Operations、Faults、Archive。两个 BOP 分类的说明文字待管理员补充。

后续新增分类有两种方式，结果相同（都会在 `Knowledge` 下创建空间、分类首页和 `CategoryClass` 对象，并自动出现在分类索引中）：

1. **网页表单**：管理员登录后打开 `http://127.0.0.1:8081/bin/view/Knowledge/CategoryAdmin`，填写代号、名称、说明、负责人，点击“创建分类”。非管理员打开该页面看不到表单。
2. **命令行**：

```bash
export XWIKI_URL=http://127.0.0.1:8081
python3 scripts/wiki_admin.py list-categories
python3 scripts/wiki_admin.py add-category BOP-NEW "BOP-NEW" --description "分类说明" --owner XWiki.Admin
```

代号只允许 1–40 位字母、数字和连字符。重复代号不会覆盖已有分类。

## 账号管理（管理员）

- 系统中的管理员账号是 `XWiki.Admin`（密码在 `.secrets/admin_password`）。它同时拥有全局 admin 权限。
- 管理员权限来自两处：`XWiki.Admin` 用户直接授权，以及 `XWiki.LuxManagers` 组（组内成员都是管理员）。
- 超级管理员 `superadmin` 默认关闭，不要长期开启。

新增管理员（推荐命令行，保证组和权限一致）：

```bash
python3 scripts/wiki_admin.py add-admin 用户名 --first 名 --last 姓
```

新增普通账号并分配角色：

```bash
python3 scripts/wiki_admin.py add-user 用户名 --group Editors --first 名 --last 姓
# --group 可选：Editors（编辑者）、Reviewers（审核人）、Readers（读者）、Managers（管理）
```

初始密码写入 `.secrets/<用户名>_password`（权限 600），应通过安全渠道交给本人，并要求首次登录后修改。

也可以在 XWiki 界面的 Administration → Users 中创建用户，并把用户加入对应组；界面入口名称以你当前版本为准，未在本次验证。

## 分类说明

`list.md` 的 CM-01～CM-10 描述的是分类管理能力，不包含具体业务分类名称。当前使用 Technical / Operations / Faults / Archive 四个示例主分类；主分类是 `Knowledge` 下的空间，标签用于交叉分类。文章页面顶部显示主分类、状态、负责人、版本、标签和摘要，`Knowledge.CategoryIndex` 显示各分类文章数。

当前限制：

- 一篇文章只有一个主分类 + 多个标签；CM-03 的“一篇文章属于一个或多个分类”尚未实现为多个分类对象。
- 分类负责人、模板字段已建立，但审批分配和批量迁移 UI 尚未实现。
- 具体业务分类树需要按实际部门/产品/工序确认后替换示例分类。

## 尚未实现

- 单级审批 UI / ApprovalRecord 自动流转。
- 文件引用索引与删除保护。
- 统一审计日志。
- 分类统计、分类负责人审批分配界面。
- Draw.io。
- Excel 在线编辑。
- 多级审批、超时提醒、撤回的完整状态机。
- 中文搜索分词专项优化。
