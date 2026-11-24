# 角色边界、发布工作流与 English-only 界面

> 当前分支增加 [LightRAG 手动同步](rag-sync.md)，策略 16 需要完整重建 JAR 和镜像。


检查基线：`feat.editPermission` 的 `a84c0e0`；目标组件为 XWiki 17.10.15 和 Publication Workflow 2.4.9。本次完成代码审计、服务端单元测试和离线浏览器回归，尚未连接真实实例核查账号分组或运行发布验收。

当前分支要求策略 **`luxpedia-workflow-policy-16`**，须完整重建组件和镜像。草稿现在采用单份关联稿与最长 24 小时独占编辑：只有当前持有人能修改，管理员可强制释放后接手。最新部署命令与管理员入口见[管理员工具](admin-tools.md)，编辑流程见[草稿编辑锁](edit-locks.md)，收藏见[个人收藏](personal-favorites.md)。下文策略 8/9 的标识为历史功能的要求。

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
| 创建、修改未提交草稿 | 创建允许；修改须持有编辑权 | 创建允许；修改须持有编辑权 | 拒绝 | 拒绝 |
| 保存草稿并预览 | 仅当前持有人 | 仅当前持有人 | 拒绝 | 拒绝 |
| 修改知识库文章（关联草稿，审核后更新） | 允许 | 允许 | 拒绝 | 拒绝 |
| 提交审核 | 仅当前持有人 | 仅当前持有人 | 拒绝 | 拒绝 |
| 撤回待审核请求 | 允许 | 仅本次提交人 | 拒绝 | 拒绝 |
| 修改已送审正文、附件或目标 | 管理恢复操作 | 拒绝 | 拒绝 | 拒绝 |
| 批准、发布、退回 | 允许 | 拒绝 | 允许，但不能审核自己的稿件 | 拒绝 |
| 评论已发布内容 | 允许 | 允许 | 允许 | 拒绝 |
| 归档、撤下、删除、配置、成员管理 | 允许 | 拒绝 | 拒绝 | 拒绝 |

编辑者仍在同一组内共享草稿，并非每篇草稿仅限创建人可编辑。管理员保留恢复和修复权限，不属于强制独立审核边界。应避免管理员同时加入普通角色组，防止 script 的显式拒绝影响管理脚本。审核和编辑兼任时，自审仍会被服务端拒绝。文档和分类的 view 限制在提交与发布时继续检查。

`/bin/view/Knowledge/test/` 这类地址对应存储文档 `Knowledge.test.WebHome`。普通内容页不因存储名为 WebHome 或缺少 ArticleClass 而被排除；普通目录页正文可通过关联草稿修改。带 `LuxPedia.Code.CategoryClass` 的分类首页由管理员维护，编辑者不能从发布页打开其关联草稿，已有分类首页草稿也会对编辑者和审核人隐藏。分类列表宏放在受保护渲染器中；分类对象、各级 WebPreferences 和 CategoryAdmin 仍由管理员维护。编辑者可修改普通文章正文、附件和业务元数据，但不能改系统设置或执行脚本。

编辑者在知识库点击顶部原生“编辑”或页面“编辑内容”，都进入关联草稿，不向正式版直接发起 edit 请求。旧实例需升级策略并重新运行完整部署脚本，为之前漏掉的普通页面补登记；安装器要求策略版本 4，避免旧 JAR 继续使用原来的页面分类规则。

编辑者从草稿首页的 **New article / 新建文章** 进入原生创建表单，默认位置为 Drafts，并使用文章模板。草稿首页保留核心 edit 访问，editor 可以直接维护编写区首页的说明正文；它不作为文章送审，也不会启动发布工作流。部署在确认组件已加载后清理旧安装器生成的首页 edit 拒绝规则，不移除其他自定义 ACL，也不开放审核人、读者的创建权限。此修复需运行 `apply_workflow.py --apply` 执行权限迁移，仅运行界面脚本不足以恢复旧实例的入口。

普通角色不应具有 programming 权限；该权限允许执行服务器代码，不属于业务角色隔离的边界。部署脚本保留现有 programming 配置，不尝试授予、撤销或写入包含它的拒绝规则。有资格管理该权限的管理员仍需单独核查其授权及继承分组，并收回普通账号已有的高权限；本次配置不等同于完成该项审计。

组件只依赖 XWiki 核心接口，不在 WEB-INF 中引用 Extension Manager 子类加载器里的工作流 API。原生状态转换通过服务端 Java 调用栈识别，客户端 `action` 参数不构成授权；业务角色、原状态、审核人身份和目标范围仍须逐项通过校验。策略针对 2.4.9 的单篇文章流程，默认拒绝其他流程操作；升级上游版本前必须重新验收。组件不把用户正文视为可执行的管理脚本。

## 界面行为

草稿编辑页的发布分类使用与 Flamingo 面包屑“小房子旁箭头”相同的 XWiki `xwiki-tree` / `xtree` 和 Bootstrap 下拉控件。点击箭头打开树形分类浏览器，逐级展开不会关闭菜单或离开编辑页；选择分类后收起菜单并显示所选位置。只用知识库分类索引中当前用户可见的分类构建树，支持多级分类；不列出系统、用户、草稿、普通文章或外部 Wiki。树不提供新建、移动、重命名、删除或多选操作。

原有文章分类字段保留为隐藏的存储字段，树形选择同步其值和原生 change 事件；模板没有分类字段时使用隐藏的兼容字段，不再显示普通 select。保存预览和延迟加载分类字段均保留正文及分类；当前部署固定为 English，不提供语言切换。分类名称、键和本 Wiki 的 Knowledge 分类引用仍可读写。分类无法识别或树控件加载失败会提示并阻止送审。草稿仍保存在 Drafts，审核通过后才发布；系统新建表单的“位置／父级”仍用于当前保存位置。已有知识库文章的关联草稿保留固定发布目标。

当前部署固定为 English，XWiki 多语言页面功能关闭，页面设置仅启用 `en`，界面不显示语言切换控件；旧的 `xwikilanguage=zh` 地址会归一到 English。主页、知识库入口、分类索引、文档库、帮助、审核列表、元数据标签、源码编辑、视频插入和上传提示均显示 English。文章正文和文件名不会自动翻译。

XWiki Standard 自带的 Translation 宏保留在平台中，但 English-only 部署不再生成中文词条或页面翻译，也不在文章模板中使用该宏。相关配置见[English-only mode](translation-macro.md)。

审核列表的说明文字与列表之间保留独立段落，修复 Wiki 表格被当作普通文本、表头和条目连成一行的问题。审核、分类和文章列表使用明确的 HTML 表格行和转义输出，并保留逐篇 view 检查，避免用户标题中的竖线等 Wiki 表格符号破坏列结构。长标题和发布路径可以在单元格内换行，避免横向撑开页面。空列表提示也仅以当前用户可见条目为依据。

语言配置覆盖原生工作流操作、文章/分类字段标签，以及旧品牌脚本生成的标题和页脚；自定义标题、页脚、主题和模板正文保留。只有与原标准模板逐字匹配的中文模板会补上英文默认和中文版本。浏览器偏好与 XWiki 语言状态不一致时，阅读页会同步到所选语言；编辑、内联和新建表单只同步语言状态，不跳转或丢弃输入。升级后运行完整 `apply_workflow.py --apply`，同时刷新服务端翻译和生成的前端资源；仅执行 `apply_interface.py` 不会更新已有 JavaScript 扩展。

右侧 Publication 显示 Write → Review → Publish、状态、下一步和发布位置。审核人的主要操作是 Approve and publish / Return for changes；Approve without publishing 收进 More options。可见按钮以角色筛选，但实际授权始终在服务端执行。单独的新审核列表只列出用户有权查看的待审稿，不提供一键批量发布。

保留原来的 LuxPedia / Flamingo 主题。新增控件使用原站的按钮、状态标签和文字颜色样式，只增加局部排版，不指定新的品牌色或字体。语言入口优先放入现有导航容器。浏览器回归截图来自没有加载生产主题的测试夹具，页面会明确标注，不能用来代表原站或部署后的整站视觉效果。

文章元数据表移到使用 REST 数据的界面层，状态以工作流对象为准。新建模板去掉系统生成的 Velocity 前缀。旧草稿在编辑者自己保存时，仅删除与原模板**逐字一致**的元数据前缀；不改写其他用户脚本，也不让管理员重新署名用户正文。升级后应让编辑者重新保存旧稿，移除该前缀。自定义 Velocity / Groovy 正文不再以普通角色执行；确有业务用途的脚本应迁入管理员维护的受保护扩展。


编辑后的百科/编写区首页使用普通 Wiki 内容和受保护的 include 渲染器，避免 editor 保存后因没有脚本权限而破坏默认列表。include 明确使用渲染器作者执行固定代码，实际访问检查仍使用当前用户。分类草稿预览按关联知识库目标加载目录列表；升级不会重新覆盖已编辑正文。

## 保存、预览、撤回与发布后的草稿

编辑者可以修改所有有权查看的草稿文章，并从任意知识库文章的 **Edit content / 编辑内容** 打开关联草稿。正式版正文、附件和发布位置仍通过审核更新；不向编辑者开放直接写正式版、删除文章或更改权限/配置的能力。部署会为根目录及嵌套目录中尚无工作流的历史文章登记正式版关联，不改写其正文、附件或正文作者。

编辑器主按钮改为 **Save draft and preview / 保存草稿并预览**，使用原生保存行为后进入页面的实际查看模式。这里显示当前站点主题下的正文、图片和视频渲染结果；发布后的页面权限与工作流控件会因角色不同而不同。预览不会自动启动审核，编辑者确认后单独点击 **Submit for review / 提交审核**，也可以继续编辑。

**Withdraw review request / 撤回审核请求** 仅向本次提交人或管理员提供。服务端验证当前角色、草稿可见权限、待审状态、版本、POST 和表单令牌，不允许撤回别人的请求、已批准或已发布内容。撤回后恢复草稿编辑权限，保留正文、附件和提交记录；重新送审会建立新提交记录。其他编辑者仍不能改动处于审核中的内容，自审限制保留。

发布后的工作副本标记为隐藏，并从 Drafts 首页的进行中列表排除；不删除它的历史或附件。再次从知识库发起修改时，复用关联草稿并恢复可见状态。已有发布副本在部署初始化时一并处理。

当前服务端策略覆盖两区全部内容正文的编辑、百科审核、撤回及迁移逻辑，安装器要求策略标识 `luxpedia-workflow-policy-8`。必须重新打包 JAR、重建镜像并重建运行容器，再应用脚本；旧策略会在修改业务配置前被拒绝。

本地端口 8081 的更新命令（在项目目录运行）：

```bash
git -c http.version=HTTP/1.1 pull --ff-only origin fix/workflow-roles-i18n && \
docker run --rm -v "${PWD}:/workspace" -v manu-wiki-maven-cache:/root/.m2 -w /workspace \
  maven:3.9-eclipse-temurin-17 mvn -f extensions/workflow-policy/pom.xml clean package && \
docker compose -f compose.yaml -f compose.workflow-policy.yaml up -d --build --force-recreate --wait xwiki && \
NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost \
python3 scripts/apply_workflow.py --base-url http://127.0.0.1:8081 --apply
```

Maven 缓存卷只用于保留下载依赖。`--wait` 等待 XWiki 健康检查通过后再应用配置；完成后强制刷新浏览器。实际实例仍须验证保存预览、附件/媒体、撤回恢复编辑、发布后隐藏和再次修改的完整链路。

## 统一新建文章与原生创建入口

草稿首页的 **New article / 新建文章** 与原生 **Create / 创建** 都进入 XWiki 的模板选择页。旧的新建文章链接固定传入 `template=LuxPedia.Templates.Article`，XWiki 在指定模板时会隐藏模板选择；新版仅预设 Drafts 位置和父页面，不再强制指定模板或模板提供器。

现有文章模板注册为原生可选模板，并限制在 Drafts 及其子级显示、创建。选择该模板后进入编辑器，不自动保存或送审。空白页和其他原生可用模板继续保留，由 XWiki 按权限和适用位置展示；现有自定义文章模板正文保留。完成编写后的保存预览、单独送审、撤回和放弃流程保持原规则。

该调整只更新配置、模板提供器和界面脚本；执行完整 `apply_workflow.py --apply` 后强制刷新即可。实际实例需对照两处入口检查模板选择、目标位置和保存预览。

## 知识库归档分类

现有 `Knowledge.Archive.WebHome` 作为统一归档入口，自动汇总当前用户有权查看的已归档正式文章。通过工作流归档后，文章从普通分类/文档库文章列表退出，在“知识库 → 归档”中出现；管理员打开原文章点击 **Restore visibility / 恢复展示** 后，恢复正常列表展示。右侧工作流面板也提供归档入口。

这里采用分类首页汇总展示，保留文章原地址、附件、历史、查看权限和草稿关联。不会物理移动、复制文章或改写主分类；因此已有链接继续有效，恢复时也不需要搬回文件。归档分类原有的资料继续列在该首页，状态按实际工作流显示；原本就存于归档分类的资料恢复后仍属于该分类。

归档和普通文章列表按已登记的工作流正式目标查询，兼容没有旧 ArticleClass 元数据的内容页。分类首页、系统入口及偏好页不混入普通文章清单；元数据缺失时负责人和标签留空。列表不使用隐藏文档过滤器，并逐篇检查当前用户的查看权限，不因汇总而开放其他分类的私有文章。

安装器保留归档分类对象、自定义标题及说明和已有中文翻译，只迁移已知默认表格/模板引用或追加受保护的汇总渲染器；重复应用不会重复追加。此调整不修改 Java 策略，当前策略 8 实例拉取代码后执行完整 `apply_workflow.py --apply` 并强制刷新浏览器即可，无须再次重建 Docker。实际实例需验证归档、归档列表、旧链接、附件和恢复展示。

## 放弃、恢复及批量整理草稿

未送审草稿的预览页新增 **Abandon draft / 放弃草稿**。该操作保留正文、附件、修订历史和关联正式版，仅将草稿标记为已放弃并从全部进行中列表隐藏；正式文章不变。已放弃页面禁止编辑和送审，需先恢复。再次从知识库打开关联草稿时会显示恢复入口，不创建重复副本；恢复保留放弃前的已保存内容，不重置为正式版。

草稿首页提供 **全部进行中 / 我的草稿 / 已放弃**。已放弃视图向普通编辑者列出自己负责的草稿，管理员可查看全部。可勾选个人未送审草稿，使用“放弃所选草稿”，每批最多 20 篇；每篇独立验证版本、权限、POST 和 CSRF，部分失败时只保留成功的操作，提示失败数量。批量操作仅处理所选页面，不递归处理子页。

工作区归属及共享标记由服务端维护：新建草稿或发布后重新开始一轮修改时记录负责人；本轮另一人保存修改后标记为共享，原负责人再次保存也不会抹掉共享标记。个人未送审草稿仅负责人或管理员可放弃；共享及启用归属记录前的旧草稿由管理员整理，避免将他人的工作隐藏。待审核草稿必须先按原有权限撤回审核，再放弃；审核者不能通过此入口改变待审状态。编辑者可恢复自己有查看权限的已放弃草稿，继续协作；恢复本身不授予放弃他人或共享草稿的权限。

归属、共享及放弃状态不能通过普通保存或 REST 对象编辑伪造。发布时不向正式文章复制工作区对象。部署初始化创建固定字段并保留已有放弃状态，重启或再次应用配置不会将已放弃草稿自动恢复。该功能需要策略 `luxpedia-workflow-policy-8`，请使用上方完整重建命令。实际实例仍需验证未送审新稿、知识库关联稿、附件、多人保存、撤回后放弃、批量部分失败和恢复后送审。

## 再次打开关联草稿与删除限制

上游工作流在已有可用草稿时渲染 GET 跳转表单，在需要创建或重开时才渲染 `getEditableDraft` / `editDraft` POST 表单。旧的“编辑文章”按钮只识别 POST，导致已有草稿时误报“暂时无法打开关联草稿”。新版同时处理这两种入口。

已有草稿进入其查看页；仅当草稿仍处于可编辑状态，且目标与发起修改的知识库文章一致时，才继续打开编辑器。待审草稿保留锁定和撤回规则。若旧页面保留的草稿链接经 REST 确认返回 404，或原生表单尚未渲染，新版使用包含当前知识库文章引用和新表单令牌的 POST 请求，交给原生工作流查找或从正式版重新创建草稿。401/403、网络及服务错误不会触发重建；不直接写正文、伪造工作流对象或覆盖既有草稿。

草稿文档所有状态都显式拒绝编辑者和审核者 DELETE，部署初始化修复已有草稿；服务端的删除事件校验继续拒绝普通角色删除草稿和知识库文章，包括草稿创建者。管理员保留恢复和删除能力。编辑者若同时属于管理员组，核心管理员权限仍有效，应检查账号是否混入 LuxManagers / XWikiAdminGroup。

已删除草稿可由管理员从回收站还原。如果选择从正式版重新创建，原草稿尚未发布的正文和附件修改不会因此自动找回。这个修复需要策略 `luxpedia-workflow-policy-8`，请重建 JAR、镜像及运行容器，再应用完整配置。实际实例仍须复测删除是否确实成功、回收站恢复、附件及后续送审发布。

## 统一审核入口

审核入口统一为 **Help Center → Workflow → Review queue**，对应 `LuxPedia.Workflow.ReviewQueue`。主页、草稿工具和文章侧栏均链接到这个队列。新增 `LuxPedia.Workflow.WebHome` 作为帮助中心下的工作流入口。

原生导航排除 `PublicationWorkflow.WebHome`，Applications 菜单中仅禁用 `platform.panels.workflowApplication` 这一个导航扩展。旧 `PublicationWorkflow.WebHome` 和 `PublicationWorkflow.Overview` 保留为跳转页，旧地址不再显示独立列表或旧批量审批表单。审批扩展的类、动作脚本、面板、翻译和配置仍然保留；删除整个技术空间会破坏草稿和审批流程。

入口调整本身不依赖新的 Java 接口。当前分支另含下述草稿删除权限修复，整体更新要求策略 8，仍须使用上方完整构建命令。

## 管理入口和用户目录

左侧导航通过原生 NavigationConfiguration 排除原始 `XWiki` 空间树，保留其他排除设置及现有面板内容。管理员在同一导航面板中看到 `XWiki administration → Users` 及管理员、编辑者、审核者、读者、未分配角色入口。普通用户看不到该管理入口；首页、角色目录和服务端查询同时校验 Wiki 级 admin 权限，直接访问地址也不能读取目录。面板禁用跨用户缓存，避免把管理员链接展示给其他账号。

用户目录按账号或姓名搜索，每页最多 25 人；数据库查询绑定搜索值并限制结果条数。角色包含嵌套组，支持一个人同时出现在多个角色目录中。原始账号仍为 `XWiki.用户名`，不复制或移动用户页，登录、历史作者及工作流中的用户引用保持有效。账号必须位于 XWiki 空间，见 [XWiki 用户文档](https://www.xwiki.org/xwiki/bin/view/documentation/xs/admin/user/)。

初始化将已有用户资料的查看权限收紧为管理员和本人，将用户组资料收紧为管理员；后续新增账号和保存资料时维护这些权限。只调整文档级 VIEW，保留其他权限和原内容作者。本人仍可修改核心用户字段和头像附件，不能修改资料页上的安全或配置对象。角色成员关系由管理员管理。

前端不再读取角色组 REST 成员列表，改用服务端提供的当前登录账号布尔角色标志；该接口不接受其他用户作为查询参数。登录、主题资源和核心渲染页面仍按 XWiki 原生规则提供，**没有对整个 XWiki 技术空间施加统一 VIEW 拒绝**，以免把登录和编辑器功能一起阻断。

应用后用独立管理员、编辑者、审核者、读者账号验收：管理员能搜索/分页和访问任意资料；普通账号看不到管理入口，不能通过目录或角色组 URL/REST 读取资料；本人资料、头像和密码修改可用；草稿创建、预览、送审、审核继续正常。目录页面支持英文和中文，用户姓名、账号保留原文。此版本要求策略标识 `luxpedia-workflow-policy-8`，请使用上面的完整重建命令。

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
4. 部署脚本先验证组件，再创建审核记录类和权限规则；未加载组件会停止，不会宣称仅靠 ACL 已修复越权。诊断和类初始化页仅允许管理员查看，使用 Velocity 调用组件提供的固定管理员服务，不需要 Groovy / programming 权限。类初始化通过 POST 和表单令牌校验执行，服务自身还会核验当前账号的 Wiki 级管理员权限。之后安装英文默认和中文翻译、系统页、工作流控件与辅助控件。部署更新受保护的 UI/代码页；对于百科/编写区的内容首页，仅迁移已知默认模板，保留已批准的自定义正文、标题和翻译。
5. 旧的待审核稿没有可信提交人记录，将拒绝审批。管理员先退回这些稿件，由编辑者重新保存并提交，建立新的审核记录；不要手工伪造审核对象或修改其 target。
6. 用分离账号测试：普通稿和已发布文章修改均经过送审；审核人发布到所选分类；退回后编辑者恢复编辑；自审、直接 REST 改状态/目标/ACL、改送审正文或附件、读者写入、伪造角色和越界发布全部失败。部署初始化会规范化现有工作流草稿的文档级 ACL，并保留原正文作者，撤掉历史 validator edit 授权；后续合法状态转换会继续维护这些 ACL。这样正文和附件操作都由核心 edit 权限在入口处限制，保存校验另行阻止对象篡改。
7. 检查 English-only 页面、CKEditor 和视频/图表宏，重启容器后重测，确认组件仍在镜像中。前端长缓存可能需要强制刷新。测试附件上传、评论和自定义宏的完整性不能由离线浏览器夹具代替。

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


### 初始化报 StringProperty 不能转换为 ListProperty

如果 JAR 构建成功、容器 Healthy，且 `PolicyStatus` 检测通过，但 `PolicySetup` 在保存 `XWiki.LuxManagers` 或其他用户/角色页面时出现 `StringProperty cannot be cast to ListProperty`，失败点在用户资料可见性迁移。XWiki 原生 GroupsClass、UsersClass 和 LevelsClass 把这些概念上的多值字段存为字符串；旧代码错误调用了要求 ListProperty 的 `getListValue()`。

修复通过读取底层属性类型，分别支持原生字符串和已有列表属性。字符串由 XWiki 对应字段类的解析器处理，保留组/用户名称中的空格；不把列表的 `toString()` 当作属性值。查看权限仍收紧为原定范围，其他权限和内容作者保留。原生字段类创建的测试对象覆盖初始化、保存时再次校验和重复应用的路径，并兼容字符串/列表混合存储。

该修复在服务端组件内，要求策略 `luxpedia-workflow-policy-8`。更新分支后重新打包 JAR、重建镜像并重建 XWiki 容器，再运行完整 `apply_workflow.py --apply`。初始化允许重复执行，之前已完成的步骤会继续校验；仅再次运行旧 Python 命令不会替换运行容器中的旧组件。


### 关联中文页面时出现 ASCII 编码异常

如果用户目录和角色权限已安装，后续在 `bootstrap_workflow_targets` / `wiki.has_object` 处出现 `UnicodeEncodeError: 'ascii' codec can't encode characters`，表示 HTTP 请求路径包含了未编码的中文页面名。Python 的 HTTP 客户端要求请求目标为 ASCII，原页面名应按 UTF-8 做 URL 百分号编码。

REST 路径构造现在逐段编码分类、页面、对象类/属性及附件名称，保留 `/spaces/`、`/pages/` 等层级分隔符；页面内容、标题和实际名称保持原文。从已编码的网页链接提取分类或草稿名称时先解码一次，之后交给路径构造器编码，避免把 `%E4...` 重复编码为 `%25E4...`。从 REST XML 获取的原始名称不做 URL 解码，名称中本来就有 `%20` 的页面仍保留该字面值。

回归通过本地 HTTP 服务验证真实 urllib 发送路径和 UTF-8 请求体，覆盖中文、空格、百分号、加号、井号、问号以及附件/属性名称，并覆盖此次知识库关联调用路径。这项修复只涉及 Python 脚本；已运行策略 8 的实例拉取分支后重新执行完整 `apply_workflow.py --base-url http://127.0.0.1:8081 --apply` 即可，不需要重建 JAR 或 Docker 镜像。
