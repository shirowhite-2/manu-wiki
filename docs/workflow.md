# Publication Workflow 现成扩展核对与 POC 操作说明

> 当前分支新增了保存前的服务端角色校验和默认英文/可切换中文的界面，部署顺序、权限矩阵和旧稿迁移见[角色与界面说明](roles-and-interface.md)。以下上游扩展说明和实例记录保留作历史参考；运行 Python 应用脚本前，必须先安装 `workflow-policy` 组件。新增策略尚未在真实实例重新验收。

核对日期：2026-11-12。目标环境：XWiki 17.10.15 LTS。

**状态：2.4.9已安装到XWiki17.10.15；两角色的起草→提交审核→审核者发布流程已在实例实测通过。完整三角色、通知、目录树和附件同步等仍待验收，详见文末。**

## 选用扩展

| 项目 | 核对结果 |
| --- | --- |
| 名称 | Publication Workflow Application |
| 当前稳定版 | `2.4.9`；官方 Maven 元数据的 `latest` 和 `release` 均为该值 |
| 安装 Extension ID | `org.xwiki.contrib.workflow-publication:xwiki-workflow-publication-application` |
| 类型 | XAR，支持 Extension Manager |
| 直接依赖 | `org.xwiki.contrib.workflow-publication:xwiki-workflow-publication-api:2.4.9` |
| 最低支持版本 | 发布 tag 的 README 明确为 **XWiki 12.10.4**，发布 POM 采用相同平台基线 |
| 本 POC 兼容性 | 已安装到17.10.15并验证最小流程；不代表全部功能兼容性认证 |
| 许可 | 扩展目录标为 **LGPL 2.1**；发布 JAR 的 `META-INF/LICENSE` 为 2.1，源码文件头允许 2.1 或后续版本。README 写作 LGPL v3，与其他材料不一致，应保留此差异，不能把 README 单行当作唯一依据 |

官方依据：[扩展目录](https://extensions.xwiki.org/xwiki/bin/view/Extension/XWiki%20Publication%20Workflow%20Application)、[2.4.9 扩展元数据](https://extensions.xwiki.org/xwiki/rest/repository/extensions/org.xwiki.contrib.workflow-publication%3Axwiki-workflow-publication-application/versions/2.4.9)、[Maven 版本元数据](https://nexus.xwiki.org/nexus/content/groups/public/org/xwiki/contrib/workflow-publication/xwiki-workflow-publication-application/maven-metadata.xml)、[发布 tag README](https://github.com/xwiki-contrib/workflow-publication/blob/xwiki-workflow-publication-2.4.9/README.md)、[发布 POM](https://nexus.xwiki.org/nexus/content/groups/public/org/xwiki/contrib/workflow-publication/xwiki-workflow-publication/2.4.9/xwiki-workflow-publication-2.4.9.pom)、[含 LICENSE 的发布 API JAR](https://nexus.xwiki.org/nexus/content/groups/public/org/xwiki/contrib/workflow-publication/xwiki-workflow-publication-api/2.4.9/xwiki-workflow-publication-api-2.4.9.jar)。

## 用现成界面配置

以下是完整三角色配置参考；当前实例采用更简单的两角色配置，实际名称见文末，无需编写工作流代码。

1. 管理员在扩展管理器中按上述 ID 安装 `2.4.9`，核对应用及依赖的安装结果。
2. 在 Wiki 管理界面创建三个组：`XWiki.ManuContributors`（作者）、`XWiki.ManuModerators`（审核）、`XWiki.ManuPublishers`（批准发布）。用三个普通测试账号分别加入，另设普通读者账号。
3. 在 `PublicationWorkflow` 下使用 `PublicationWorkflowConfigTemplate` 模板建立配置页，例如 `PublicationWorkflow.ManuWorkflow`。将三组分别填入 contributor、moderator、validator；界面中的 Publishers 对应源码字段 `validator`。
4. 建立独立草稿区域与发布区域，例如 `ManuDrafts`、`ManuPublished`。将 `PublicationWorkflow.PublicationWorkflowPanel` 加入两区域的面板配置。内置 `{{publicationworkflow /}}` 宏也可提供操作界面；优先使用面板。
5. 在草稿区域新建一篇测试页，选择配置页和发布目标后启动流程。首轮只测单页，暂不选 `includeChildren`。依次用作者提交审核、审核员提交审批、发布员批准并确认发布；另测拒绝返回和再次编辑。

官方用法见[扩展使用说明](https://extensions.xwiki.org/xwiki/bin/view/Extension/XWiki%20Publication%20Workflow%20Application#HUsage)。配置字段及默认值见[2.4.9 配置类](https://github.com/xwiki-contrib/workflow-publication/blob/xwiki-workflow-publication-2.4.9/xwiki-workflow-publication-application/src/main/resources/PublicationWorkflow/PublicationWorkflowConfigClass.xml)。

通用部署可用 `Set drafts to hidden = Yes` 减少草稿在普通页面索引中的显示，但它不是访问控制。本实例为让审核队列列出待审页面设置为 `draftsHidden = 0`；草稿内容仍由 Drafts 空间的 VIEW ACL 保护。两角色配置保持 `Skip draft rights = No`、`Move strategy = Do nothing`，viewer/commenter 组为空。

若只需要一次审核，可不设置 moderator，由 publisher 承担审核与发布。三组方案则便于演示作者、审核、批准发布三个职责，但扩展没有强制同一人不得兼任。

## 状态、角色与权限

完整三组配置的主流程为：

```text
draft → moderating → validating → valid → published
           │              │
           └→ draft       └→ moderating

published → Edit draft → draft（旧发布页保留）
```

`valid` 是已批准但尚未发布的可选中间状态；界面的批准和发布动作可连贯完成。以下是 `skipDraftRights=false` 时扩展对**草稿页**设置的权限，不是发布页权限：

| 草稿状态 | 作者组 | 审核组 | 批准发布组 |
| --- | --- | --- | --- |
| `draft` | 查看、评论、编辑 | 查看、评论、编辑 | 查看、评论、编辑 |
| `moderating` | 查看 | 查看、评论、编辑 | 查看、评论、编辑 |
| `validating` / `valid` | 查看 | 查看 | 查看、评论、编辑 |
| `published` | 保留发布前 ACL | 保留发布前 ACL | 保留发布前 ACL |

重新进入草稿状态会恢复三组编辑权限。Publisher 可执行审核与贡献操作，Moderator 可执行贡献操作，Wiki 管理员可执行所有这些操作。因此验收必须使用分离的普通账号，不能只用管理员看到按钮便断言权限正确。

源码依据：[草稿权限](https://github.com/xwiki-contrib/workflow-publication/blob/xwiki-workflow-publication-2.4.9/xwiki-workflow-publication-api/src/main/java/org/xwiki/workflowpublication/internal/DefaultPublicationWorkflow.java#L590)、[审核权限](https://github.com/xwiki-contrib/workflow-publication/blob/xwiki-workflow-publication-2.4.9/xwiki-workflow-publication-api/src/main/java/org/xwiki/workflowpublication/internal/DefaultPublicationWorkflow.java#L740)、[审批权限](https://github.com/xwiki-contrib/workflow-publication/blob/xwiki-workflow-publication-2.4.9/xwiki-workflow-publication-api/src/main/java/org/xwiki/workflowpublication/internal/DefaultPublicationWorkflow.java#L833)、[角色能力检查](https://github.com/xwiki-contrib/workflow-publication/blob/xwiki-workflow-publication-2.4.9/xwiki-workflow-publication-api/src/main/java/org/xwiki/workflowpublication/internal/DefaultPublicationRoles.java#L135)。

**发布区域的 ACL 由管理员另行设置。** 应让普通读者可读发布页、不能读草稿，业务账号通过工作流修改内容，并验证其无法绕过流程直接编辑发布页。不要把草稿自动权限或页面 hidden 标记当作发布区域的权限配置。

## 草稿与发布副本的行为

一份内容有草稿和发布目标两个页面引用。首次发布创建目标；再次修改草稿不会改变已经发布的内容，再次发布才更新目标。开始新一轮贡献不会自动撤下旧发布版。

发布同步正文、元数据、常规对象、附件及语言版本，保存为目标页的新修订，不搬运整个草稿修订历史。草稿的评论、评分、ACL 和工作流对象不直接覆盖目标对应对象；目标自己的权限得以保留。精确过滤类包括 `XWiki.XWikiComments`、`XWiki.RatingsClass`、`XWiki.AverageRatingsClass`、`XWiki.XWikiRights`、`XWiki.XWikiGlobalRights`、`PublicationWorkflow.PublicationWorkflowClass`。

发布不是只新增内容的备份：草稿删除的附件或翻译可在下次发布时从目标同步移除。勾选 `includeChildren` 才把子页面纳入流程及递归发布，同时会同步删除没有草稿对应项的已发布子页。首轮单页验收后再单独测试目录树。

源码依据：[发布操作](https://github.com/xwiki-contrib/workflow-publication/blob/xwiki-workflow-publication-2.4.9/xwiki-workflow-publication-api/src/main/java/org/xwiki/workflowpublication/internal/DefaultPublicationWorkflow.java#L954)、[重新编辑](https://github.com/xwiki-contrib/workflow-publication/blob/xwiki-workflow-publication-2.4.9/xwiki-workflow-publication-api/src/main/java/org/xwiki/workflowpublication/internal/DefaultPublicationWorkflow.java#L1083)、[复制过滤及附件同步](https://github.com/xwiki-contrib/workflow-publication/blob/xwiki-workflow-publication-2.4.9/xwiki-workflow-publication-api/src/main/java/org/xwiki/workflowpublication/internal/DefaultPublicationWorkflow.java#L1263)、[目标、翻译与子页处理](https://github.com/xwiki-contrib/workflow-publication/blob/xwiki-workflow-publication-2.4.9/xwiki-workflow-publication-api/src/main/java/org/xwiki/workflowpublication/internal/DefaultPublicationWorkflow.java#L1465)。

归档会将发布内容标为 hidden；取消发布会删除发布目标并把内容送回草稿。两项应使用可丢弃的测试内容另行验证。[官方行为说明](https://extensions.xwiki.org/xwiki/bin/view/Extension/XWiki%20Publication%20Workflow%20Application#HUsage)

## 当前实例验收记录

当前实例更新日期：2026-11-17。旧的 `PublicationWorkflow.POC` / `Drafts.WorkflowDemo` 记录来自早期 POC，当前实例不存在；以本节配置为准。

当前配置页为 `LuxPedia.Workflow.Config`：

| 字段 | 当前值 |
| --- | --- |
| contributor | `XWiki.LuxEditors` |
| moderator | 空（两角色） |
| validator | `XWiki.LuxReviewers` |
| defaultDraftSpace | `Drafts` |
| defaultTargetSpace | `Knowledge` |
| draftsHidden | `0`（审核列表可列出待审核稿；Drafts 仍由 VIEW 权限保护） |
| skipDraftRights | `0` |
| moveStrategy | `doNothing` |

管理员可用 `python3 scripts/apply_workflow.py --apply` 幂等恢复配置、Drafts/Knowledge 工作流面板、编辑者/审核人草稿权限，以及现有文章的工作流关联。默认不写入，需显式传 `--apply`。

### 用户操作

1. **新建文章**：在 Drafts 创建文章；编辑者在 CKEditor 上方选择“审核通过后发布到分类”（默认文章主分类），然后点 **保存并提交审核**。系统保存分类元数据、自动开始工作流并提交审核，不需要展开高级选项或手填发布位置。
2. **修改已发布文章**：在 Knowledge 的原文章页面打开右侧 Workflow 面板，点击 **Go to draft**。系统打开与原发布页关联的草稿；编辑者修改后点 **保存并提交审核**，目标位置保持原样。审核人发布后更新原 Knowledge 页面。
3. **审核与发布**：编辑者提交后状态为 `validating`。审核人可在 Workflow 面板执行 Publish 或 Refuse。
4. **审核队列**：从 `Drafts.WebHome` 点击 **待审核队列**，或从 **帮助中心 → Workflow → 审核队列** 进入（`LuxPedia.Workflow.ReviewQueue`）；列表显示 validating 状态的待审文章。待审状态显示为“待审核，尚未发布”，避免误认为已经通过。
5. **管理员**：编辑器仍保留普通 **Done / Save**，不会自动提交审核。

目前的 `Knowledge` 中 ArticleClass 页面会登记为工作流发布目标；已有同分类、同名 Drafts 页面会自动关联到原目标。其余历史文章从 Knowledge 点击 Go to draft 时，会在 Drafts 创建关联草稿。Drafts 页面不再依赖 hidden 标记隐藏；读者和匿名用户仍被空间 VIEW ACL 拒绝，因此页面列表只对有权限的编辑者/审核人可见。`Drafts.WebHome` 使用内置 Documents 宏列出文章并提供待审核队列入口；编辑者可浏览但不能编辑该系统维护页，普通草稿页面不受此限制。

实现脚本 `scripts/apply_workflow.py` 把配置和界面行为写入 XWiki。`LuxPedia.Code.WorkflowUXScript` 通过 JavaScriptExtension：编辑者的 Done 改为“保存并提交审核”；保存完成后驱动工作流开始/提交；管理员保持原生按钮。XWiki 数据库存放工作流对象和页面状态，脚本用于在其他实例复现设置。

### 新建草稿选择分类后未进入队列的修复

普通 XWiki 页面保存为 `Drafts.文章名.WebHome`。旧代码选择分类时把最后的 `WebHome` 当作文章名，生成 `Knowledge.Technical.WebHome` 一类目标，和已有分类首页冲突。上游工作流返回 `targetexists=true`，因此没有创建工作流对象，也不会进入待审核列表。

现在普通页面使用倒数第二段作为文章名，发布目标为分类下的终端文章页。例如 `Drafts.新文章.WebHome` 选择“技术文档”后，目标为 `Knowledge.Technical.新文章`；终端草稿 `Drafts.新文章` 使用相同规则。单篇文章的快捷提交不包含子页。草稿仍保留在 Drafts，先进入 `validating` 队列；审核人发布后，扩展才在所选分类生成目标副本。已有工作流的文章保留原目标。真实的同名冲突会提示“已保存但未提交审核”，并停止自动重试。

修复脚本后，需在目标实例以管理员配置运行 `python3 scripts/apply_workflow.py --apply`，更新数据库中的 JavaScriptExtension；仅更新仓库文件不会改变已部署实例。部署后重新加载编辑器页面，以免继续使用浏览器缓存的旧脚本。之前尚无工作流对象的草稿可以重新编辑、选择分类并提交；已关联到错误目标的草稿需要管理员单独检查，脚本不会自动重定向已有工作流。

离线浏览器回归测试：安装 Node.js、Python 3、Playwright 和 Chromium 后运行 `node --test tests/workflow_ux.test.cjs`。使用系统 Chromium 时可设置 `CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium`。测试使用本地 HTTP 服务模拟上游表单、REST 和重定向，覆盖普通页、终端页、分类元数据、根目录选择、原目标保留、同名冲突和管理员保存。它验证脚本的浏览器行为，不代替真实 XWiki 的审核发布验收。

### 既有实例验收记录

以下为原有的 2026-11-17 浏览器端到端验收记录，不包含上述修复在真实实例上的重新验收：

- 新建测试草稿：编辑者点击“保存并提交审核”后自动进入 `validating`，默认目标按 `ArticleClass.primaryCategory` 指向 `Knowledge.Technical`；审核人发布后生成 Knowledge 页面。
- 已发布测试文章：编辑者从 Knowledge 的 Go to draft 进入 Drafts 关联草稿；保存并提交后目标仍为原 `Knowledge.Technical` 页面，审核人发布后更新原页面。
- 管理员仍显示原生 Done；编辑者可编辑普通 Drafts 页面，不能直接编辑 Knowledge；读者仍不能访问 Drafts。
- 两个测试页面已清理；Sprint 1 冒烟测试 14 项通过。

邮件通知未验收，当前环境未配置 SMTP；通知失败不影响页面内的提交和发布状态流转。严格职责分离仍未完成：审核人对 Knowledge 的权限需另行审计，不能仅凭此 POC 声称无法绕过流程。

| 验收项 | 预期 | 当前结果 |
| --- | --- | --- |
| 安装 | XWiki 17.10.15 安装 Publication Workflow 2.4.9 | 通过 |
| 编辑者提交 | 编辑者保存后进入 validating | 浏览器端到端通过；无需手选目标位置 |
| 已发布文章修改 | 从原 Knowledge 文章进入关联草稿，发布后位置不变 | 临时既有文章端到端通过；目标保留原 Knowledge 分类路径 |
| 审核发布 | 审核人可发布到目标页 | 通过；页面内 Publish/确认生成或更新目标页 |
| 角色权限 | 编辑者不能直接编辑 Knowledge，读者不能查看 Drafts | 冒烟测试通过；审核人在工作流状态中按扩展授予审核权限 |
| 管理员保存 | 管理员继续使用普通 Done / Save | 浏览器检查通过 |
| 旧版保留与 ACL | 审核期间旧版仍可读，发布不覆盖目标 ACL | 尚未专项验收 |
| 邮件通知 | SMTP 配置后邮件送达 | 未验收；当前没有 SMTP |
| 可选目录树 | includeChildren 复制/删除/权限继承 | 未验收 |

邮件到达依赖 SMTP 和测试用户邮箱，不能以界面提交成功代替送达结果。上游还提供了[人工测试场景](https://github.com/xwiki-contrib/workflow-publication/blob/xwiki-workflow-publication-2.4.9/tests.md)，可作为后续回归清单参考。
