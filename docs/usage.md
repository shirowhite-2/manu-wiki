# POC 使用说明

实际通过与待验收项以 `reports/acceptance.md` 为准，以下是使用路径，不把操作说明当成已验证结果。

## 访问与账号

实例位于当前隔离环境的 `http://127.0.0.1:8080/`，不等于用户电脑的 localhost，也不是公网地址。长期访问需迁移到非生产企业服务器或使用平台提供的环境访问方式。不要为了方便直接暴露数据库或关闭权限。

管理员用户名保存在本地 `.secrets/admin_username`，密码在 `.secrets/admin_password`；不要提交或分享这些文件。试点还提供 `poceditor`、`pocreviewer`、`pocreader` 三个合成测试账号，各自密码在 `.secrets/<用户名>_password`。正式邀请试点成员时应在 Administration → Users 中创建个人账号，避免多人共用管理员。

Sprint 1 使用以下组；旧 POC 组不再作为验收依据。结构与权限矩阵见 [Sprint 1 实现与验收](sprint1.md)。

| 角色 | 组 | 目标权限 |
| --- | --- | --- |
| 管理 | `XWiki.LuxManagers` | 用户、权限、扩展及整个实例配置 |
| 编辑者 | `XWiki.LuxEditors` | 阅读 Knowledge，在 Drafts 中创建、修改和提交草稿 |
| 审核人 | `XWiki.LuxReviewers` | 审核、拒绝或发布；工作流进入审核状态后可按流程获得该稿的审核权限 |
| 读者 | `XWiki.LuxReaders` | 阅读 Knowledge 和附件、评论、收藏，不读草稿、不编辑 |
| 未登录用户 | `XWiki.XWikiGuest` | 不读知识页面或附件；不开放自助注册 |

权限在 wiki 和空间的 Administration → Rights 中管理。页面标题、标签、“Draft”状态都不能代替实际 VIEW 权限。新增子空间继承情况要用阅读者及匿名用户再测一次。

`LuxPedia` 是产品帮助、模板、脚本和工作流配置空间：管理员可编辑；编辑者、审核人和读者只读。业务文章应在 `Drafts` 编辑，不要直接改 `LuxPedia` 内的配置页面。

## 知识结构与编辑

- `Knowledge`：已发布示例，含 Technical、Operations、Faults、Archive、DocumentLibrary、CategoryGuide、CategoryIndex。
- `Drafts`：草稿与审核区域。草稿首页可浏览，含“待审核队列”入口和内置文档列表宏；编辑者不能修改系统维护的首页，普通文章页面可编辑。
- `Templates.Technical`：技术知识模板。可复制该页面到 Drafts，然后修改标题、负责人、适用版本、步骤、验证与来源。系统的 Templates 根空间也包含官方模板配置，不要整空间删除。

打开草稿页面 → Edit。默认使用 CKEditor，可编辑标题、正文、列表、表格、链接和图片。编辑者的协作编辑按钮显示为 **保存并提交审核**：保存后自动进入“待审核”状态；管理员仍使用普通 **Done / Save**。待审列表入口在 Drafts 首页的“待审核队列”，审核人也可通过右侧 Workflow 面板发布或退回。源码编辑模式使用其原有保存按钮；要自动提交审核，请使用 CKEditor 的“保存并提交审核”。不要把 Markdown 代码原样当作 XWiki 语法。

新草稿的编辑器上方有“审核通过后发布到分类”选择器，默认使用文章主分类；可在提交前改选目标分类。点击 **保存并提交审核** 后，分类元数据和发布目标会一起更新。修改已发布文章时，从该 Knowledge 文章右侧的 **Workflow → 打开修改草稿** 进入关联草稿；这类草稿的目标锁定为原文章位置，选择器会说明目标并禁止改址。审核人在“待审核队列”或草稿页 Workflow 面板中发布或退回。

分类可通过嵌套页面和 Tags 实现；少量示例使用技术文档、操作手册两类，不复制 BWIpedia 全量内容。中文页面内容可直接编写，界面语言在用户配置或 Localization 中选择。

匿名用户不能创建、编辑、评论、删除或注册；只保留登录页和公开首页。需要操作时先用个人账号登录，不要共用管理员。

## 附件与版本

`Knowledge.Attachments` 包含合成图片、PDF、Word、视频。附件页签支持上传和下载；图片可插入正文。PDF/视频能否内联显示由浏览器及宏配置决定，下载成功不等同于预览成功。

官方镜像内含 LibreOffice，可供 Office 宏转换/预览使用；这不包含浏览器编辑并保存回 Word 的能力。Collabora/ONLYOFFICE 编辑需要第三阶段连接器和引擎，当前未部署。

修改页面后查看底部 History，选择两个版本比较；需要恢复时使用对应版本的 rollback/恢复入口，并确认当前内容和版本号。先在合成页操作，不在真实知识上做恢复演练。重复上传同名附件可产生附件版本，是否保存作者和历史必须另测。

## 搜索与发布

顶部搜索使用 XWiki Solr。可尝试 `MANU-WIKI-PUBLISHED-20261112`、`MANU-WIKI-PDF-20261112`、`MANU-WIKI-DOCX-20261112`。附件解析是异步的，不能用刚上传后的瞬时空结果推断文件不受支持。

Publication Workflow 的配置与角色流程见 `workflow.md`；若尚未安装或验收，则采用人工方式：编辑者在 Drafts 写作，审核者检查，审核通过后由审核者复制到 Knowledge，并在页面注明审核人/日期。此方式只是过渡流程，不冒充扩展自动流转。已发布版本更新也经过草稿审核，阅读者始终只访问 Knowledge。

AI 语义检索、摘要、相关页面推荐与带来源问答仍待已有模型服务接入；当前关键词搜索不能称为语义搜索。配置映射和安全验收见 `ai.md`。
