# 个人收藏

文章标题旁显示 **☆ Favorite / ★ Favorited**，点击可添加或取消，完成后立即显示结果。左侧导航的独立 **My Favorites** 区域展示最近添加的 5 篇可见文章，**View all favorites** 进入 `Favorites.WebHome`（`/bin/view/Favorites/`），可按标题、分类或路径搜索、分页和取消收藏。标题按钮和原下拉菜单共用同一状态。控件固定使用 English，并沿用现有主题的按钮和排版。

Flamingo 的文章引用优先从 `<html data-xwiki-reference>` 读取，兼容旧 meta 标签和原生模型；读取与地址初始化在 DOM 就绪后进行。旧的 `LuxPedia.Favorites.WebHome` 中英文地址保留跳转，并从帮助中心导航树排除，避免形成第二套入口。原生 Favorites 技术树也排除，由独立收藏区域提供访问。

收藏属于当前登录账号，保存在服务器，换浏览器或设备登录后仍在。添加范围为 Knowledge、Drafts 和 Main 的已存在且有查看权限的页面，不包含 WebPreferences。列表展示现有原生收藏中当前账号仍有查看权限的页面；失权、删除及其他 Wiki 的条目不展示，不能通过收藏扩大文章权限。收藏文章不改变草稿、审核、发布、附件或归档流程。

## 原生数据与修复

继续使用 Favorites Application 1.4.4 的 `Favorites.Code.FavoritesClass` 对象，保存在 `XWiki.用户名`。已有 `pages` 顺序保留，新收藏追加，侧栏倒序取最近 5 条；已有 `pinnedPages` 保留，不当作个人新收藏重复迁移。不使用浏览器本地存储保存收藏内容，本地存储只发送其他标签页的刷新通知。

旧的权限策略只允许用户资料页上的 `XWiki.XWikiUsers` 字段发生变化，因此原生收藏新增／修改 FavoritesClass 也可能被拒绝。策略 9 为固定的当前账号收藏服务增加一次保存范围：只能保存当前登录账号已有的资料页，不允许混入 ACL、组成员或脚本对象修改，也不允许变更资料页正文和标题。普通 REST／对象编辑仍不能直接写收藏对象。

新服务使用同一原生存储，无需向组件添加 Favorites 扩展的类加载器依赖。添加前检查文章存在及当前 VIEW 权限；每次读取也检查 VIEW。取消个人已有收藏可在不读取失权文章内容的情况下完成。写操作要求 POST、CSRF 和用户资料版本，服务端按账号串行处理当前单实例中的修改；版本冲突会刷新列表并提示重试。保存取消或失败不会显示假成功。

原下拉操作由新的客户端入口接管；原生 `AddRemoveFavoriteService` 也替换为只读 GET／受校验 POST 的服务页，避免保留另一条 GET 写入入口。收藏应用配置仅管理员可改。旧 FavoritesPanel 若已配置在自定义布局中，改为完整列表入口，避免它继续输出未经 VIEW 过滤的标题。原用户资料页上的收藏编辑表单未扩展权限，日常维护使用新的“我的收藏”页。

服务仅返回当前账号的数据，不接受另一个账号作为收藏目标。JSON 响应及请求均禁止缓存，导航面板禁用跨用户缓存；页面标题和分类以文本节点输出。原生存储类型异常时会停止并提示错误，不尝试拆分字符串、清空列表或覆盖用户数据。

## 更新部署

此功能需要 **`luxpedia-workflow-policy-9`**。已有策略 8 实例也必须重新构建组件、镜像和运行容器，然后应用完整安装器；只运行 Python 无法修复旧 Java 策略的保存限制。先按已有流程备份数据库和 XWiki 数据卷。

在 Mac 项目目录执行（本地端口 8081）：

```bash
git switch feat/article-translation
git -c http.version=HTTP/1.1 pull --ff-only origin feat/article-translation && \
docker run --rm -v "${PWD}:/workspace" -v manu-wiki-maven-cache:/root/.m2 -w /workspace \
  maven:3.9-eclipse-temurin-17 mvn -f extensions/workflow-policy/pom.xml clean package && \
docker compose -f compose.yaml -f compose.workflow-policy.yaml up -d --build --force-recreate --wait xwiki && \
NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost \
python3 scripts/apply_workflow.py --base-url http://127.0.0.1:8081 --apply
```

Favorites Application 1.4.4 须已安装；安装器会检查其原生类是否存在，不创建替代收藏类。完成后强制刷新浏览器。

若已运行策略 9，只更新本次 Flamingo 信息读取和收藏入口位置修复，不需要再次重建 JAR 或镜像；更新分支后运行完整 Python 安装器并强制刷新即可。检查接口未传 `reference` 时返回 `eligible:false` 是正常行为，不代表账号无法收藏文章。

## 实际实例验收

离线测试覆盖 Java 授权、版本、原生对象读写、保留资料／置顶数据，以及本地 HTTP 浏览器界面，未连接用户本地 XWiki。

用两个普通账号分别验证：标题收藏、旧下拉取消、刷新及重新登录仍保留自己的状态、侧栏 5 条、完整列表搜索和分页。用 reader 收藏有权访问的正式文章，确认不需要文章编辑权限；回收文章或收回 VIEW 后，列表不应输出它的标题或链接。测试跨账号请求、缺失令牌、GET 修改、过期版本均不能写入；原资料、头像、组成员及权限保护仍有效。若部署多个 XWiki 节点，须另行验证共享存储下的并发更新行为。
