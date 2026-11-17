# 角色边界、发布界面与中英文切换

检查基线：`feat.editPermission` 的 `a84c0e0`；目标组件为 XWiki 17.10.15 和 Publication Workflow 2.4.9。本次完成代码审计、服务端单元测试和离线浏览器回归，尚未连接真实实例核查账号分组或运行发布验收。

## 已确认的问题与处理

| 问题 | 影响 | 处理 |
| --- | --- | --- |
| 工作流会在文档级给 validator 授予 edit，覆盖空间级审核人只读规则 | 审核人可以编辑草稿正文，改变送审内容 | 保存事件校验审核人只能评论和决定；状态转换时重建草稿角色 ACL |
| 上游 `RolesEnforcerListener` 只检测对象变化，恢复/阻止逻辑仍是 TODO | 有草稿 edit 权限时，不能依赖它阻止直接新增、删除或修改工作流对象、目标和状态 | 所有文档创建/更新事件检查对象差异，直接写入工作流、审核记录、ACL 或脚本对象均取消保存 |
| `canContribute` 包含 validator，archive 也使用此检查 | 审核人可调用作者操作，编辑者可尝试归档已发布内容 | 服务端仅允许编辑者起草、重开和提交；归档、撤下、删除等归管理员 |
| 没有独立审核检查 | 同一账号兼任编辑和审核时可审批自己的提交 | 审核记录由服务端保存提交人、最新正文作者和送审版本；提交人、最新正文作者及草稿创建人不能批准、发布或退回自己的稿件 |
| reader 的配置包含 comment，但角色说明是只读 | 读者可发表评论 | 明确拒绝读者对 Knowledge 的 comment 权限；保存校验也限制评论操作角色 |
| 普通角色没有显式禁止脚本 | 不能把这类账号当作只处理内容的角色 | 增加 script 拒绝规则，保护系统代码和角色组；不依赖按钮隐藏。programming 属于另行受保护的高权限，部署脚本不修改该配置 |
| 新建普通页面的末段是 WebHome | 选择分类后目标撞上分类首页，不能进入审批 | 用文章容器名构建终端发布目标；保留已有工作流目标；跳转前持续持有提交锁，避免重复启动 |

上游依据：[`setupDraftAccess` / `submitForValidation`](https://github.com/xwiki-contrib/workflow-publication/blob/xwiki-workflow-publication-2.4.9/xwiki-workflow-publication-api/src/main/java/org/xwiki/workflowpublication/internal/DefaultPublicationWorkflow.java)、[`canContribute`](https://github.com/xwiki-contrib/workflow-publication/blob/xwiki-workflow-publication-2.4.9/xwiki-workflow-publication-api/src/main/java/org/xwiki/workflowpublication/internal/DefaultPublicationRoles.java)、[`RolesEnforcerListener`](https://github.com/xwiki-contrib/workflow-publication/blob/xwiki-workflow-publication-2.4.9/xwiki-workflow-publication-api/src/main/java/org/xwiki/workflowpublication/internal/RolesEnforcerListener.java)。

## 预期角色流程

| 操作 | 管理员 / LuxManagers | 编辑者 | 审核人 | 读者 |
| --- | --- | --- | --- | --- |
| 阅读已发布内容 | 允许 | 允许 | 允许 | 允许 |
| 阅读草稿 | 允许 | 允许 | 允许 | 拒绝 |
| 创建、修改未提交草稿 | 允许 | 允许 | 拒绝 | 拒绝 |
| 提交审核 | 允许 | 允许 | 拒绝 | 拒绝 |
| 修改已送审正文、附件或目标 | 管理恢复操作 | 拒绝 | 拒绝 | 拒绝 |
| 批准、发布、退回 | 允许 | 拒绝 | 允许，但不能审核自己的稿件 | 拒绝 |
| 评论已发布内容 | 允许 | 允许 | 允许 | 拒绝 |
| 归档、撤下、删除、配置、成员管理 | 允许 | 拒绝 | 拒绝 | 拒绝 |

编辑者仍在同一组内共享草稿，并非每篇草稿仅限创建人可编辑。管理员保留恢复和修复权限，不属于强制独立审核边界。应避免管理员同时加入普通角色组，防止 script 的显式拒绝影响管理脚本。审核和编辑兼任时，自审仍会被服务端拒绝。文档和分类的 view 限制在提交与发布时继续检查。

普通角色不应具有 programming 权限；该权限允许执行服务器代码，不属于业务角色隔离的边界。部署脚本保留现有 programming 配置，不尝试授予、撤销或写入包含它的拒绝规则。有资格管理该权限的管理员仍需单独核查其授权及继承分组，并收回普通账号已有的高权限；本次配置不等同于完成该项审计。

组件只依赖 XWiki 核心接口，不在 WEB-INF 中引用 Extension Manager 子类加载器里的工作流 API。原生状态转换通过服务端 Java 调用栈识别，客户端 `action` 参数不构成授权；业务角色、原状态、审核人身份和目标范围仍须逐项通过校验。策略针对 2.4.9 的单篇文章流程，默认拒绝其他流程操作；升级上游版本前必须重新验收。组件不把用户正文视为可执行的管理脚本。

## 界面行为

默认 English，Language 下拉框提供 English / 中文。选择会保存在浏览器；阅读模式通过 XWiki 的 `xwikilanguage` 参数切换原生菜单和服务端页面。编辑模式即时翻译自定义控件，同时保留未保存正文和分类，系统菜单在保存或离开编辑器后更新。主页、知识库入口、分类索引、文档库、帮助、审核列表、元数据标签、源码编辑、视频插入和上传提示均使用同一套语言资源。文章正文、用户填写的分类名称和文件名不自动翻译。

右侧 Publication 显示 Write → Review → Publish、状态、下一步和发布位置。审核人的主要操作是 Approve and publish / Return for changes；Approve without publishing 收进 More options。可见按钮以角色筛选，但实际授权始终在服务端执行。单独的新审核列表只列出用户有权查看的待审稿，不提供一键批量发布。

保留原来的 LuxPedia / Flamingo 主题。新增控件使用原站的按钮、状态标签和文字颜色样式，只增加局部排版，不指定新的品牌色或字体。语言入口优先放入现有导航容器。浏览器回归截图来自没有加载生产主题的测试夹具，页面会明确标注，不能用来代表原站或部署后的整站视觉效果。

文章元数据表移到使用 REST 数据的界面层，状态以工作流对象为准。新建模板去掉系统生成的 Velocity 前缀。旧草稿在编辑者自己保存时，仅删除与原模板**逐字一致**的元数据前缀；不改写其他用户脚本，也不让管理员重新署名用户正文。升级后应让编辑者重新保存旧稿，移除该前缀。自定义 Velocity / Groovy 正文不再以普通角色执行；确有业务用途的脚本应迁入管理员维护的受保护扩展。

## 部署与真实实例验收

1. 按现有备份流程备份数据库和 XWiki 数据卷。保留当前镜像和配置以便回退。
2. 安装 JDK 17+ 和 Maven，运行 `mvn -f extensions/workflow-policy/pom.xml clean package`。
3. 将组件打入持久镜像，而非只复制到临时容器：

   ```bash
   docker compose -f compose.yaml -f compose.workflow-policy.yaml build xwiki
   docker compose -f compose.yaml -f compose.workflow-policy.yaml up -d xwiki
   python3 scripts/apply_workflow.py --apply
   ```

   如当前部署使用其他 Compose 覆盖文件，应一并传入。自定义基础镜像必须提供 `/usr/local/tomcat/webapps/ROOT/WEB-INF/lib`，并与 XWiki 17.10.15 兼容。不要仅合并 Python 改动而省略服务端组件。
4. 部署脚本先验证组件，再创建审核记录类和权限规则；未加载组件会停止，不会宣称仅靠 ACL 已修复越权。诊断和类初始化页仅允许管理员查看，使用 Velocity 调用组件提供的固定管理员服务，不需要 Groovy / programming 权限。类初始化通过 POST 和表单令牌校验执行，服务自身还会核验当前账号的 Wiki 级管理员权限。之后安装英文默认和中文翻译、系统页、工作流控件与辅助控件。部署会更新列出的系统维护页，不能将这些页当作用户文章保存自定义正文。
5. 旧的待审核稿没有可信提交人记录，将拒绝审批。管理员先退回这些稿件，由编辑者重新保存并提交，建立新的审核记录；不要手工伪造审核对象或修改其 target。
6. 用分离账号测试：普通稿和已发布文章修改均经过送审；审核人发布到所选分类；退回后编辑者恢复编辑；自审、直接 REST 改状态/目标/ACL、改送审正文或附件、读者写入、伪造角色和越界发布全部失败。部署初始化会规范化现有工作流草稿的文档级 ACL，并保留原正文作者，撤掉历史 validator edit 授权；后续合法状态转换会继续维护这些 ACL。这样正文和附件操作都由核心 edit 权限在入口处限制，保存校验另行阻止对象篡改。
7. 切换中英文、检查 CKEditor 和视频/图表宏、重启容器后重测，确认组件仍在镜像中。前端长缓存可能需要强制刷新。测试附件上传、评论和自定义宏的完整性不能由离线浏览器夹具代替。

本次未构建或运行生产容器，也未获得实例账号和现有文档权限快照，因此不能据此断言线上已经没有越权或已完成发布验收。回退时应使用备份恢复配套的权限和系统页面；单独移除组件会失去保存校验，不能当作安全回退。

## 回归命令

```bash
mvn -f extensions/workflow-policy/pom.xml verify
python3 -m unittest discover -s tests -p interface_config_test.py -v
npm ci
npx playwright install chromium
npm run test:ui
```

使用现有 Chromium 可设置 `CHROMIUM_EXECUTABLE_PATH`。浏览器夹具通过本地 HTTP 服务模拟 XWiki 表单、REST 和 303 跳转，不修改真实 Wiki。Java 测试使用核心 API 模型验证保存策略，仍须按上面的步骤做真实实例验收。

### 已安装 JAR，但诊断页报告 Groovy 不允许执行

如果容器里已有 JAR，而 `PolicyStatus` 页面显示 `The execution of the [groovy] script macro is not allowed`，这是旧安装器依赖 Groovy 编程权限导致的检测失败，并不代表 JAR 不存在。更新本分支后，重新打包 JAR、构建镜像并重建 XWiki 容器，使新增的 `luxpediaWorkflowPolicy` 管理服务加载，再运行 Python 配置脚本。仅更新 Python 文件或重启旧镜像不足以加载新服务；不需要为编辑者、审核人或读者开放脚本或编程权限。

例如本地端口为 8081 时，配置命令是 `python3 scripts/apply_workflow.py --base-url http://127.0.0.1:8081 --apply`。

### 初始化报 `The resolver parameter doesn't contain an Entity Reference of type [WIKI]`

旧版本修复历史草稿 ACL 时，用显式解析器处理 `Drafts.文章名.WebHome` 一类查询结果，却遗漏了当前 Wiki 参数。新版显式传入 `WikiReference`，并校验迁移范围仍在当前 Wiki 的 Drafts 内；真实解析器回归覆盖嵌套页面、转义名称及非默认 Wiki。这项修复属于 Java 组件，需要更新代码、重新打包 JAR、重建镜像并重建 XWiki 容器后，再运行配置脚本。

### 创建 `script,programming` 全局权限规则返回 HTTP 500

如果日志显示 `programming access has been denied`，随后 `RightsFilterListener` 移除了刚写入的规则，表示当前账号虽可管理 Wiki，却没有资格设置 programming 权限。XWiki 对包含 programming 的拒绝规则也执行这项检查。新版只设置普通角色的 script 拒绝规则，保留原有 programming 配置；这项修复只需更新 Python 脚本，不需要重建 JAR 或容器。
