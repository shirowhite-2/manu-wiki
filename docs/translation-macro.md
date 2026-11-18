# 默认可用的翻译宏

本项目使用 XWiki Standard 17.10.15。它自带 **Translation / 翻译** 宏（宏标识为 `translation`），不需要另装插件、配置翻译服务或提供 API Key。首次部署仍须完成 XWiki Standard 安装向导；单独启动 Docker 不会自动完成向导及本项目的业务配置。

完整执行 `scripts/apply_workflow.py --apply` 时，界面安装器已经设置英文为默认语言、启用 `en,zh`，并注册 Wiki 范围的 `LuxPedia.Code.InterfaceTranslations` 中英文词条。这些设置持久保存在 Wiki 数据库中，正常重启容器无需重新启用宏。已有实例无需为这个宏重建 JAR 或 Docker 镜像。

## 在编辑器中使用

1. 用 editor 打开可编辑的草稿，选择工具栏的 **宏 → 插入宏**。
2. 搜索 **Translation**、**翻译** 或宏标识 **translation**；它属于 **Content / 内容** 分类。
3. `key` 填写已有词条，例如 `luxpedia.knowledge`；`locale` 留空以跟随当前页面语言。
4. 插入后点击 **保存草稿并预览**，检查实际显示，再按正常流程提交审核。

源码编辑中的等价写法：

```text
{{translation key="luxpedia.knowledge"/}}
```

这个例子在英文界面显示 `Knowledge`，在中文界面显示 `知识库`。要固定显示某种语言，可以指定 `locale`：

```text
{{translation key="luxpedia.knowledge" locale="en"/}}
{{translation key="luxpedia.knowledge" locale="zh"/}}
```

普通的 `key`、`locale` 和文本 `parameters` 用法不需要授予 editor 脚本或编程权限。`scriptParameters` 是引用脚本变量的开发选项，需要脚本权限，本项目的普通编辑者不使用它。

## 词条从哪里来

宏查找已经登记的译文，不会把任意中文或英文正文自动翻译。`key` 也不是待翻译句子；拼错或不存在时，宏可能直接显示这个 key。

项目现有词条由 `scripts/ui/en.json` 和 `scripts/ui/zh.json` 维护，使用时加上 `luxpedia.` 前缀。新增项目词条须在两个文件中使用相同的键，然后重新执行完整安装器；不要直接改生成的 `InterfaceTranslations` 页面，后续部署会按代码重新生成它。文章作者在正文中引用词条，管理员维护词条配置。

## 部署后检查

在项目目录执行以下命令可更新已有实例的业务配置（本例使用本地端口 8081）：

```bash
NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost \
python3 scripts/apply_workflow.py --base-url http://127.0.0.1:8081 --apply
```

用 editor 在测试草稿插入上面的例子并保存预览，分别切换 English / 中文，确认显示对应译文。确认后可按正常流程提交审核，检查发布后的显示。

如果宏选择器中没有这个宏，先清空搜索/分类筛选并刷新编辑器，再让管理员检查 XWiki Standard 安装是否完成，以及核心扩展 `org.xwiki.platform:xwiki-platform-localization-macro:17.10.15` 是否正常加载。不要用开放脚本权限来处理宏缺失。

参考：[官方 Translation Macro 文档](https://extensions.xwiki.org/xwiki/bin/view/Extension/Translation%20Macro)、[XWiki 17.10.15 实现](https://github.com/xwiki/xwiki-platform/blob/xwiki-platform-17.10.15/xwiki-platform-core/xwiki-platform-localization/xwiki-platform-localization-macro/src/main/java/org/xwiki/localization/macro/internal/TranslationMacro.java)。
