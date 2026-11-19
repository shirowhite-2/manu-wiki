# 管理文章模板

管理员可在顶部导航直接点击 **Create article template / 创建文章模板**，使用原生创建流程；旁边的 **Article templates / 文章模板** 打开集中管理页面。顶部入口仅在服务端角色接口确认当前账号为管理员后显示，兼容 Flamingo 的原生页面标识及延迟加载的导航栏。

原生 **Administration → Content → Article templates / 管理 → 内容 → 文章模板** 也已注册为正式管理菜单；管理员首页和左侧管理导航继续提供入口。所有管理入口使用同一个 `LuxPedia.Templates.WebHome`。本地管理菜单地址为 `http://127.0.0.1:8081/bin/admin/XWiki/XWikiPreferences?editor=globaladmin&section=LuxPediaTemplates`，独立中心地址为 `http://127.0.0.1:8081/bin/view/LuxPedia/Templates/`。

模板中心按位置列出 `LuxPedia/Templates` 及其子目录中的模板正文，并显示关联配置、编辑正文、模板设置或注册入口。中心本身只允许 Wiki 级管理员查看；模板正文和提供者仍允许业务角色读取，编辑与配置由现有 LuxPedia 空间权限及服务端策略限制为管理员。该入口没有扩大普通角色在其他空间创建模板的权限，也没有新增全站模板配置拦截。

1. 点击 **Create template / 创建模板**，在原生创建页面填写标题，保留位置为 `LuxPedia/Templates`。默认创建终端页，使用普通文章结构作为起始正文。
2. 用原生编辑器编写并保存，返回模板中心。此时模板显示尚未注册。
3. 点击该行的 **Register template / 注册模板**。原生提供者表单默认使用同目录下的 `模板名Provider`，依照 XWiki 的命名规则关联原模板；保存前核对 Template to use。若配置名称已被占用，中心会提示并保留已有页面。
4. 将 Visibility Restrictions 和 Creation Restrictions 都设为 `Drafts`，不要勾选 Creation Restrictions as Suggestions，Action on create 保留 Edit，再保存配置。模板正文与配置保存在同一目录。
5. 编辑者在草稿区点击新建文章，即可通过系统模板列表使用它。业务文章仍保存到 Drafts，经审核后发布到 Knowledge。

中心“已关联模板配置”只表示提供者引用存在，是否出现在新建列表还取决于原生配置和 VIEW 权限。模板正文改变只影响之后创建的文章，不会同步改写已经创建的文章。

保留原有模板、提供者和内容地址；不搬迁第三方或系统内置模板，也不清理自定义模板。全部系统提供者仍可通过中心的系统模板配置链接进入原生管理界面。

部署：拉取 `feat/article-translation`，运行完整 `python3 scripts/apply_workflow.py --base-url http://127.0.0.1:8081 --apply`，然后强制刷新。当前策略 9 实例无需重建 JAR 或 Docker；本次 Java 改动仅为页面渲染测试和测试资源配置。

离线检查覆盖安装幂等、管理员可见性、非管理员不查询模板、原生创建参数、标题转义、已有配置、名称冲突和空列表。尚未连接用户本机验证原生创建/注册的完整浏览器流程。
