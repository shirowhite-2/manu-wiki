# 外部链接与导入样式

> 当前整合分支已升级为策略 **11**，已安装策略 10 也须重建组件和镜像，按[最新升级命令](admin-tools.md#现有-mac-实例升级8081)执行。下文仅界面更新的描述是该功能初次引入时的情况。


编辑器上方统一使用 **External link — auto detect / 外部链接，自动识别** 输入框，粘贴网址后点击 **Insert link / 插入链接**。保存的是普通文章链接，仍按原来的草稿编辑、预览和审核流程生效。

| 链接 | 查看页行为 |
| --- | --- |
| GitHub 的仓库、代码、提交、Issue 等 | 点击跳转到原网站 |
| Bitbucket 的仓库、代码等 | 点击跳转到原网站 |
| 其他 HTTP / HTTPS 网站 | 点击跳转到原网站 |
| 受支持的 draw.io 官方分享链接 | 保留跳转，额外提供按需图表预览 |

GitHub、Bitbucket 的链接统一按普通链接处理，文件名为 `.drawio` 也不会增加预览。没有代码嵌入、仓库同步或 API 授权流程；私有网站的登录和访问权限由原网站处理。仅 draw.io 自身的受支持分享链接沿用[图表预览](drawio.md)。

## 已导入的内容

- 已经转换为正文超链接的内容自动识别，保留原始文字、样式、地址和普通链接的跳转方式，不需要重新插入。
- 独立的 HTTP / HTTPS 网址文本可在查看页变为可点击链接；代码块和正在编辑的文本不处理。
- 异步插入正文后自动检查新链接；地址改变或链接移除时同步更新图表按钮，重复更新不生成多个按钮。
- 链接识别只影响查看页展示，不写回原始文章，也不替代 Word、MediaWiki 等源格式的导入转换。
- 图表按钮不会混入正文表格的 CSV / Excel 导出值；视频、图片、附件继续使用既有入口。

除用户主动展开 draw.io 图表外，识别过程不请求外部预览服务，不读取 GitHub/Bitbucket API。输入框接受 HTTP / HTTPS 地址，HTML、iframe 代码及其他协议不作为新链接插入。

## 部署

这是策略 10 之上的界面更新，已有策略 10 的实例无需重新编译 JAR：

```bash
git switch feat/article-translation &&
git -c http.version=HTTP/1.1 pull --ff-only origin feat/article-translation &&
NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost \
  python3 scripts/apply_workflow.py --base-url http://127.0.0.1:8081 --apply
```

完成后强制刷新浏览器。若尚未部署策略 10，请先执行[完整组件升级](edit-locks.md)。

本地浏览器检查包括普通链接实际导航、导入样式保留、独立网址、异步更新、原图表预览和表格导出。原生 CKEditor 的 WikiText 转换、真实外网和私有站点登录仍需在部署实例验收。
