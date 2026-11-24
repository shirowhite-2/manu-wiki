# Word、附件和视频批量迁入

当前按源资料为 Word/Office 文件与视频的目录设计。实际源 Wiki、版本及导出格式尚未确定，未执行真实资料导入。若后续能拿到原 Wiki 的完整导出，先评估原格式迁移；只导出 Word 往往不能保留页面历史、全部内部链接或原系统权限。

## 先确定内容如何入库

- **Word 转百科正文**：Office Importer 将文档转换为可读、可搜索、可编辑的 Wiki 页面；同时把原 Word 留作附件。需要已配置且工作的 LibreOffice/Office server。这不是在线编辑原 Word 文件，当前项目没有部署在线 Office 编辑服务。
- **只保留原件**：为文件建立条目并挂原文件附件；条目说明可编辑，Word 内的正文不会因此自动变成 Wiki 正文。
- **视频**：不送进 Office 转换器，单独按附件处理，建立所属页面及引用。MP4/WebM 的实际编码需做浏览器播放检查，其他格式可能需要转换；上传成功不等于浏览器一定能播放。

已经导入为文章附件的图片、视频、PDF 和 Office 文件，可通过编辑器的统一附件工具插入正文，详见[图片、视频和文件插入正文](attachments-in-content.md)。这个入口不执行批量导入或 Word 图片提取；迁移仍需建立正确的附件归属和正文引用。

使用管理员做迁移；导入后的 Knowledge 内容自动登记为可发起关联草稿的正式页，后续 editor 的修改依然经过审核。首次迁移不是把 60GB 资料逐篇交由编辑者手工上传和送审。

## 只读盘点和分批清单

取得导出目录后，在项目目录执行：

```bash
python3 scripts/plan_import.py "/绝对路径/导出资料" --output .runtime/import-plan
```

输出目录必须是新的目录，且位于源资料之外。命令不连接 XWiki、不修改源文件、不上传、不转换。默认只读文件属性，大视频不会读入内存：

- `inventory.json`：总文件数、总大小、类型统计、最大单文件、跳过的符号链接和分批信息。
- `office-001.csv`、`video-001.csv` 等：按类型拆分，默认每批约 1 GiB 源文件；一个超过批次大小的文件单独一批，不会被切成分片。
- `doc.file` 保留相对路径，`doc.title` 保留文件显示名并进行 Wiki 字面转义，`doc.name` 根据相对路径生成稳定标识，避免同名文件碰撞；`primaryCategory` 是源目录信息，需在实际导入映射时确认分类规则。

可用 `--batch-mib 512` 缩小批次，或用 `--max-file-mib 1024` 标出超过**实际实例单文件限制**的文件。可选 `--sha256` 按 1 MiB 分块读取原件计算校验值，60GB 全量校验需要额外磁盘读取时间。清单不是已完成导入或校验的记录。

## 挂载源目录，而非上传一个 60GB 压缩包

源目录可以只读挂载进容器：

```bash
export WIKI_IMPORT_SOURCE_DIR="/绝对路径/导出资料"
docker compose -f compose.yaml -f compose.workflow-policy.yaml -f compose.import.yaml up -d xwiki
```

容器内源目录为 `/imports/source`；原有 `/usr/local/xwiki` 持久卷保留。导入工具通过正常接口把附件写入实际存储。不要把普通文件直接复制进 XWiki 的 `store/file` 目录：附件还有数据库元数据和版本记录，文件目录不能代替 API 导入。

## 执行 Office 批量导入

先核对 Office Importer/Office server，再评估安装与 XWiki 17.10.15 兼容的 Batch Import API/Application。项目目前没有自动安装或配置这套导入/转换服务，不应将工具清单当作服务已可用。

1. 用 10–20 份代表性 Word 和 1–2 个视频先做试导入，包含最大的文档、复杂表格、图片及大视频。目标先选新建的 `Knowledge.ImportPilot`，先限管理员查看，核验后按正式库的访问规则开放；不覆盖现有百科页。
2. 在管理员维护的配置区（例如 `LuxPedia.Import`）创建导入配置。元数据 CSV 可以小文件上传，数据目录设置为 `/imports/source`。
3. 映射 `doc.name` 到页面标识、`doc.title` 到标题、`doc.file` 到文件；选择目标 space。分类由管理员先确认，是沿用现有分类、按源目录建分类，还是先集中到导入区。
4. Office 批次：开启 File import（转换为正文），需要保留原件时同时开启 File upload。视频/图片批次关闭 Office 转换，只进行文件上传；页面内播放器和图片引用另行生成或插入。
5. 先运行模拟并选择不覆盖已有页面，核对 Word 表格/图片、原件下载、视频播放、内部链接、搜索和三个角色的实际访问。
6. 再按批执行，记录每批的目标页面、成功/失败文件及校验结果，保留旧链接到新页面的映射。失败按文件或小批重试；不要只凭浏览器超时判定整批失败，否则重复上传可能产生额外附件版本。清单工具本身没有实现实际上传的断点续传。
7. 导入结束后核对总量和校验值，测试 editor 打开关联草稿、预览、送审和再次发布，然后备份数据库及附件存储。

[Batch Import 官方配置](https://extensions.xwiki.org/xwiki/bin/view/Extension/Batch%20Import%20Application)说明 `doc.file`、服务器数据目录、Office 转换、附件上传及模拟功能；[Office Importer](https://www.xwiki.org/xwiki/bin/view/documentation/xs/user/base/page/create/office-importer)说明 Office 转换范围。版本兼容性与大附件路径应在试导入中验证。

## 容量和运行配置

60GB 是源资料总量，**不是 JVM 需要 60GB 内存，也不是每次 HTTP 请求应上传 60GB**。核对：

- 文件附件存储及实际持久卷；当前 Compose 挂载 `/usr/local/xwiki`，但仍需在目标实例确认附件 store。不要把大视频内容堆进数据库附件存储。
- 最大单文件、实例附件限制、反向代理请求限制和超时；以最大视频而不是总资料体积设置并验证。
- 宿主机及 Docker Desktop 虚拟磁盘的空间。先以原件的 2–3 倍可用空间作预算，再按工作副本、附件历史、临时转换和备份策略实测调整；源码 60GB 不代表最终只占 60GB。工作流会保留关联草稿及附件。
- 初始迁移低并发，尤其限制 Office 转换进程数量。当前 JVM 最大堆为 2GB，先看代表性大文件的实际内存、耗时与索引负载，再调整。

XWiki 的[附件存储说明](https://www.xwiki.org/xwiki/bin/view/documentation/xs/admin/store/attachment-store)解释数据库元数据与文件内容的关系；[附件说明](https://www.xwiki.org/xwiki/bin/view/documentation/xs/admin/attachments)解释同名上传会新增附件版本。备份须覆盖数据库和对应附件存储，参见[部署文档](deployment.md)。
