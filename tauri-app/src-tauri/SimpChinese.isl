; Inno Setup 6.5.0+ 简体中文语言文件
; 基于官方 Default.isl 翻译

[LangOptions]
LanguageName=简体中文 (Simplified Chinese)
LanguageID=$0804
LanguageCodePage=936

[Messages]

; *** 应用标题
SetupAppTitle=安装
SetupWindowTitle=安装 - %1
UninstallAppTitle=卸载
UninstallAppFullTitle=%1 卸载

; *** 通用
InformationTitle=信息
ConfirmTitle=确认
ErrorTitle=错误

; *** SetupLdr
SetupLdrStartupMessage=将安装 %1。要继续吗？
LdrCannotCreateTemp=无法创建临时文件。安装已中止。
LdrCannotExecTemp=无法执行临时目录中的文件。安装已中止。
HelpTextNote=

; *** 启动错误
LastErrorMessage=%1.%n%n错误 %2: %3
SetupFileMissing=安装目录中缺少文件 %1。请更正问题或获取程序的新副本。
SetupFileCorrupt=安装文件已损坏。请获取程序的新副本。
SetupFileCorruptOrWrongVer=安装文件已损坏，或与此版本的安装程序不兼容。请更正问题或获取程序的新副本。
InvalidParameter=命令行上指定了无效参数：%n%n%1
SetupAlreadyRunning=安装程序正在运行。
WindowsVersionNotSupported=此程序不支持此计算机运行的 Windows 版本。
WindowsServicePackRequired=此程序需要 %1 Service Pack %2 或更高版本。
NotOnThisPlatform=此程序不能在 %1 上运行。
OnlyOnThisPlatform=此程序必须在 %1 上运行。
OnlyOnTheseArchitectures=此程序只能在以下处理器架构的 Windows 版本上安装：%n%n%1
WinVersionTooLowError=此程序需要 %1 版本 %2 或更高版本。
WinVersionTooHighError=此程序不能在 %1 版本 %2 或更高版本上安装。
AdminPrivilegesRequired=安装此程序时必须以管理员身份登录。
PowerUserPrivilegesRequired=安装此程序时必须以管理员或高级用户组成员身份登录。
SetupAppRunningError=安装程序检测到 %1 当前正在运行。%n%n请关闭它的所有实例，然后单击"确定"继续，或"取消"退出。
UninstallAppRunningError=卸载程序检测到 %1 当前正在运行。%n%n请关闭它的所有实例，然后单击"确定"继续，或"取消"退出。

; *** 权限选择
PrivilegesRequiredOverrideTitle=选择安装模式
PrivilegesRequiredOverrideInstruction=选择安装模式
PrivilegesRequiredOverrideText1=%1 可以为所有用户安装（需要管理员权限），或仅为当前用户安装。
PrivilegesRequiredOverrideText2=%1 可以为当前用户安装，或为所有用户安装（需要管理员权限）。
PrivilegesRequiredOverrideAllUsers=为所有用户安装(&A)
PrivilegesRequiredOverrideAllUsersRecommended=为所有用户安装（推荐）(&A)
PrivilegesRequiredOverrideCurrentUser=仅为我安装(&M)
PrivilegesRequiredOverrideCurrentUserRecommended=仅为我安装（推荐）(&M)

; *** 杂项错误
ErrorCreatingDir=安装程序无法创建目录 "%1"。
ErrorTooManyFilesInDir=无法在目录 "%1" 中创建文件，因为该目录包含太多文件。

; *** 通用消息
ExitSetupTitle=退出安装
ExitSetupMessage=安装尚未完成。如果现在退出，程序将不会被安装。%n%n您可以稍后再次运行安装程序来完成安装。%n%n退出安装？
AboutSetupMenuItem=关于安装程序(&A)...
AboutSetupTitle=关于安装程序
AboutSetupMessage=%1 版本 %2%n%3%n%n%1 主页：%n%4
AboutSetupNote=
TranslatorNote=

; *** 按钮
ButtonBack=< 上一步(&B)
ButtonNext=下一步(&N) >
ButtonInstall=安装(&I)
ButtonOK=确定
ButtonCancel=取消
ButtonYes=是(&Y)
ButtonYesToAll=全部是(&A)
ButtonNo=否(&N)
ButtonNoToAll=全部否(&O)
ButtonFinish=完成(&F)
ButtonBrowse=浏览(&B)...
ButtonWizardBrowse=浏览(&R)...
ButtonNewFolder=新建文件夹(&M)

; *** 选择语言
SelectLanguageTitle=选择安装语言
SelectLanguageLabel=选择安装过程中使用的语言。

; *** 通用向导文本
ClickNext=单击"下一步"继续，或"取消"退出安装程序。
BeveledLabel=
BrowseDialogTitle=浏览文件夹
BrowseDialogLabel=在下面的列表中选择一个文件夹，然后单击"确定"。
NewFolderName=新建文件夹

; *** 欢迎页
WelcomeLabel1=欢迎使用 [name] 安装向导
WelcomeLabel2=将在您的计算机上安装 [name/ver]。%n%n建议在继续之前关闭所有其他应用程序。

; *** 密码页
WizardPassword=密码
PasswordLabel1=此安装受密码保护。
PasswordLabel3=请输入密码，然后单击"下一步"继续。密码区分大小写。
PasswordEditLabel=密码(&P)：
IncorrectPassword=您输入的密码不正确。请重试。

; *** 许可协议页
WizardLicense=许可协议
LicenseLabel=请在继续之前阅读以下重要信息。
LicenseLabel3=请阅读以下许可协议。在继续安装之前，您必须接受此协议的条款。
LicenseAccepted=我接受此协议(&A)
LicenseNotAccepted=我不接受此协议(&D)

; *** 信息页
WizardInfoBefore=信息
InfoBeforeLabel=请在继续之前阅读以下重要信息。
InfoBeforeClickLabel=准备好继续安装时，请单击"下一步"。
WizardInfoAfter=信息
InfoAfterLabel=请在继续之前阅读以下重要信息。
InfoAfterClickLabel=准备好继续安装时，请单击"下一步"。

; *** 用户信息页
WizardUserInfo=用户信息
UserInfoDesc=请输入您的信息。
UserInfoName=用户名(&U)：
UserInfoOrg=组织(&O)：
UserInfoSerial=序列号(&S)：
UserInfoNameRequired=您必须输入用户名。

; *** 选择安装位置页
WizardSelectDir=选择安装位置
SelectDirDesc=将 [name] 安装到哪里？
SelectDirLabel3=安装程序将把 [name] 安装到以下文件夹。
SelectDirBrowseLabel=要继续，请单击"下一步"。如果要选择其他文件夹，请单击"浏览"。
DiskSpaceGBLabel=至少需要 [gb] GB 可用磁盘空间。
DiskSpaceMBLabel=至少需要 [mb] MB 可用磁盘空间。
CannotInstallToNetworkDrive=安装程序无法安装到网络驱动器。
CannotInstallToUNCPath=安装程序无法安装到 UNC 路径。
InvalidPath=您必须输入带盘符的完整路径，例如：%n%nC:\APP%n%n或 UNC 路径：%n%n\\server\share
InvalidDrive=您选择的驱动器或 UNC 共享不存在或不可访问。请选择其他位置。
DiskSpaceWarningTitle=磁盘空间不足
DiskSpaceWarning=安装程序需要至少 %1 KB 可用空间来安装，但所选驱动器只有 %2 KB 可用。%n%n要继续吗？
DirNameTooLong=文件夹名称或路径太长。
InvalidDirName=文件夹名称无效。
BadDirName32=文件夹名称不能包含以下字符：%n%n%1
DirExistsTitle=文件夹已存在
DirExists=文件夹：%n%n%1%n%n已存在。要安装到该文件夹 吗？
DirDoesntExistTitle=文件夹不存在
DirDoesntExist=文件夹：%n%n%1%n%n不存在。要创建该文件夹吗？

; *** 选择组件页
WizardSelectComponents=选择组件
SelectComponentsDesc=要安装哪些组件？
SelectComponentsLabel2=选择要安装的组件；清除不想安装的组件。准备好后单击"下一步"。
FullInstallation=完全安装
CompactInstallation=精简安装
CustomInstallation=自定义安装
NoUninstallWarningTitle=组件已存在
NoUninstallWarning=安装程序检测到以下组件已安装在您的计算机上：%n%n%1%n%n取消选择这些组件不会卸载它们。%n%n要继续吗？
ComponentSize1=%1 KB
ComponentSize2=%1 MB
ComponentsDiskSpaceGBLabel=当前选择至少需要 [gb] GB 磁盘空间。
ComponentsDiskSpaceMBLabel=当前选择至少需要 [mb] MB 磁盘空间。

; *** 选择附加任务页
WizardSelectTasks=选择附加任务
SelectTasksDesc=要执行哪些附加任务？
SelectTasksLabel2=选择安装 [name] 时要执行的附加任务，然后单击"下一步"。

; *** 选择开始菜单文件夹页
WizardSelectProgramGroup=选择开始菜单文件夹
SelectStartMenuFolderDesc=应将程序快捷方式放在哪里？
SelectStartMenuFolderLabel3=安装程序将在以下开始菜单文件夹中创建程序快捷方式。
SelectStartMenuFolderBrowseLabel=要继续，请单击"下一步"。如果要选择其他文件夹，请单击"浏览"。
MustEnterGroupName=您必须输入文件夹名称。
GroupNameTooLong=文件夹名称或路径太长。
InvalidGroupName=文件夹名称无效。
BadGroupName=文件夹名称不能包含以下字符：%n%n%1
NoProgramGroupCheck2=不创建开始菜单文件夹(&D)

; *** 准备安装页
WizardReady=准备安装
ReadyLabel1=安装程序现在准备在您的计算机上安装 [name]。
ReadyLabel2a=单击"安装"继续安装，或单击"上一步"查看或更改设置。
ReadyLabel2b=单击"安装"继续安装。
ReadyMemoUserInfo=用户信息：
ReadyMemoDir=安装位置：
ReadyMemoType=安装类型：
ReadyMemoComponents=已选组件：
ReadyMemoGroup=开始菜单文件夹：
ReadyMemoTasks=附加任务：

; *** 下载
DownloadingLabel2=正在下载文件...
ButtonStopDownload=停止下载(&S)
StopDownload=确定要停止下载吗？
ErrorDownloadAborted=下载已中止
ErrorDownloadFailed=下载失败：%1 %2
ErrorDownloadSizeFailed=获取大小失败：%1 %2
ErrorProgress=无效进度：%1 / %2
ErrorFileSize=无效文件大小：预期 %1，实际 %2

; *** 解压
ExtractingLabel=正在解压文件...
ButtonStopExtraction=停止解压(&S)
StopExtraction=确定要停止解压吗？
ErrorExtractionAborted=解压已中止
ErrorExtractionFailed=解压失败：%1

; *** 解压失败详情
ArchiveIncorrectPassword=密码不正确
ArchiveIsCorrupted=存档已损坏
ArchiveUnsupportedFormat=不支持的存档格式

; *** 准备安装页
WizardPreparing=准备安装
PreparingDesc=安装程序正在准备在您的计算机上安装 [name]。
PreviousInstallNotCompleted=先前程序的安装/删除尚未完成。您需要重启计算机才能完成该安装。%n%n重启计算机后，再次运行安装程序以完成 [name] 的安装。
CannotContinue=安装程序无法继续。请单击"取消"退出。
ApplicationsFound=以下应用程序正在使用需要更新的文件。建议您允许安装程序自动关闭这些应用程序。
ApplicationsFound2=以下应用程序正在使用需要更新的文件。建议您允许安装程序自动关闭这些应用程序。安装完成后，安装程序将尝试重启这些应用程序。
CloseApplications=自动关闭应用程序(&A)
DontCloseApplications=不关闭应用程序(&D)
ErrorCloseApplications=安装程序无法自动关闭所有应用程序。建议您在继续之前手动关闭使用需要更新文件的应用程序。
PrepareToInstallNeedsRestart=安装程序必须重启计算机才能继续。%n%n重启后，请重新运行安装程序以完成 [name] 的安装。%n%n要立即重启吗？

; *** 安装中页
WizardInstalling=正在安装
InstallingLabel=安装程序正在将 [name] 安装到您的计算机上。请稍候...

; *** 安装完成页
FinishedHeadingLabel=完成 [name] 安装向导
FinishedLabelNoIcons=安装程序已在您的计算机上安装了 [name]。
FinishedLabel=安装程序已在您的计算机上安装了 [name]。可以通过已安装的快捷方式启动该应用程序。
ClickFinish=单击"完成"退出安装程序。
FinishedRestartLabel=要完成 [name] 的安装，安装程序必须重启您的计算机。要立即重启吗？
FinishedRestartMessage=要完成 [name] 的安装，安装程序必须重启您的计算机。%n%n要立即重启吗？
ShowReadmeCheck=是，我想查看自述文件
YesRadio=是，立即重启此计算机(&Y)
NoRadio=否，稍后手动重启(&N)
RunEntryExec=运行 %1
RunEntryShellExec=查看 %1

; *** 需要下一张磁盘
ChangeDiskTitle=需要下一张磁盘
SelectDiskLabel2=请插入磁盘 %1 并单击"确定"。%n%n如果此磁盘上的文件在以下显示的文件夹以外的文件夹中，请输入正确的路径或单击"浏览"。
PathLabel=路径(&P)：
FileNotInDir2=无法在 "%2" 中找到文件 "%1"。请插入正确的磁盘或选择其他文件夹。
SelectDirectoryLabel=请指定下一张磁盘的位置。

; *** 安装阶段消息
SetupAborted=安装未完成。%n%n请更正问题并再次运行安装程序。
AbortRetryIgnoreSelectAction=选择操作
AbortRetryIgnoreRetry=重试(&T)
AbortRetryIgnoreIgnore=忽略错误并继续(&I)
AbortRetryIgnoreCancel=取消安装
RetryCancelSelectAction=选择操作
RetryCancelRetry=重试(&T)
RetryCancelCancel=取消

; *** 安装状态消息
StatusClosingApplications=正在关闭应用程序...
StatusCreateDirs=正在创建目录...
StatusExtractFiles=正在解压文件...
StatusDownloadFiles=正在下载文件...
StatusCreateIcons=正在创建快捷方式...
StatusCreateIniEntries=正在创建 INI 条目...
StatusCreateRegistryEntries=正在创建注册表条目...
StatusRegisterFiles=正在注册文件...
StatusSavingUninstall=正在保存卸载信息...
StatusRunProgram=正在完成安装...
StatusRestartingApplications=正在重启应用程序...
StatusRollback=正在回滚更改...

; *** 杂项错误
ErrorInternal2=内部错误：%1
ErrorFunctionFailedNoCode=%1 失败
ErrorFunctionFailed=%1 失败；代码 %2
ErrorFunctionFailedWithMessage=%1 失败；代码 %2.%n%3
ErrorExecutingProgram=无法执行文件：%n%1

; *** 注册表错误
ErrorRegOpenKey=打开注册表项时出错：%n%1\%2
ErrorRegCreateKey=创建注册表项时出错：%n%1\%2
ErrorRegWriteKey=写入注册表项时出错：%n%1\%2

; *** INI 错误
ErrorIniEntry=在文件 "%1" 中创建 INI 条目时出错。

; *** 文件复制错误
FileAbortRetryIgnoreSkipNotRecommended=跳过此文件（不推荐）(&S)
FileAbortRetryIgnoreIgnoreNotRecommended=忽略错误并继续（不推荐）(&I)
SourceIsCorrupted=源文件已损坏
SourceDoesntExist=源文件 "%1" 不存在
SourceVerificationFailed=源文件验证失败：%1
VerificationSignatureDoesntExist=签名文件 "%1" 不存在
VerificationSignatureInvalid=签名文件 "%1" 无效
VerificationKeyNotFound=签名文件 "%1" 使用了未知密钥
VerificationFileNameIncorrect=文件名不正确
VerificationFileTagIncorrect=文件标签不正确
VerificationFileSizeIncorrect=文件大小不正确
VerificationFileHashIncorrect=文件哈希不正确
ExistingFileReadOnly2=无法替换现有文件，因为它被标记为只读。
ExistingFileReadOnlyRetry=移除只读属性并重试(&R)
ExistingFileReadOnlyKeepExisting=保留现有文件(&K)
ErrorReadingExistingDest=尝试读取现有文件时出错：
FileExistsSelectAction=选择操作
FileExists2=文件已存在。
FileExistsOverwriteExisting=覆盖现有文件(&O)
FileExistsKeepExisting=保留现有文件(&K)
FileExistsOverwriteOrKeepAll=对后续冲突执行相同操作(&D)
ExistingFileNewerSelectAction=选择操作
ExistingFileNewer2=现有文件比安装程序尝试安装的文件更新。
ExistingFileNewerOverwriteExisting=覆盖现有文件(&O)
ExistingFileNewerKeepExisting=保留现有文件（推荐）(&K)
ExistingFileNewerOverwriteOrKeepAll=对后续冲突执行相同操作(&D)
ErrorChangingAttr=尝试更改现有文件属性时出错：
ErrorCreatingTemp=尝试在目标目录中创建文件时出错：
ErrorReadingSource=尝试读取源文件时出错：
ErrorCopying=尝试复制文件时出错：
ErrorDownloading=尝试下载文件时出错：
ErrorExtracting=尝试解压存档时出错：
ErrorReplacingExistingFile=尝试替换现有文件时出错：
ErrorRestartReplace=重启替换失败：
ErrorRenamingTemp=尝试重命名目标目录中的文件时出错：
ErrorRegisterServer=无法注册 DLL/OCX：%1
ErrorRegSvr32Failed=RegSvr32 失败，退出代码 %1
ErrorRegisterTypeLib=无法注册类型库：%1

; *** 卸载显示名称标记
UninstallDisplayNameMark=%1 (%2)
UninstallDisplayNameMarks=%1 (%2, %3)
UninstallDisplayNameMark32Bit=32 位
UninstallDisplayNameMark64Bit=64 位
UninstallDisplayNameMarkAllUsers=所有用户
UninstallDisplayNameMarkCurrentUser=当前用户

; *** 安装后错误
ErrorOpeningReadme=尝试打开自述文件时出错。
ErrorRestartingComputer=安装程序无法重启计算机。请手动重启。

; *** 卸载程序消息
UninstallNotFound=文件 "%1" 不存在。无法卸载。
UninstallOpenError=无法打开文件 "%1"。无法卸载。
UninstallUnsupportedVer=此版本的卸载程序无法识别卸载日志文件 "%1" 的格式。无法卸载。
UninstallUnknownEntry=卸载日志中遇到未知条目 (%1)。
ConfirmUninstall=确实要完全删除 %1 及其所有组件吗？
UninstallOnlyOnWin64=此安装只能在 64 位 Windows 上卸载。
OnlyAdminCanUninstall=此安装只能由具有管理员权限的用户卸载。
UninstallStatusLabel=正在从您的计算机中删除 %1。请稍候...
UninstalledAll=%1 已成功从您的计算机中删除。
UninstalledMost=%1 卸载完成。%n%n某些元素无法删除。可以手动删除这些文件。
UninstalledAndNeedsRestart=要完成 %1 的卸载，必须重启计算机。%n%n要立即重启吗？
UninstallDataCorrupted=文件 "%1" 已损坏。无法卸载。

; *** 卸载阶段消息
ConfirmDeleteSharedFileTitle=删除共享文件？
ConfirmDeleteSharedFile2=系统指示以下共享文件不再被任何程序使用。要让卸载程序删除此共享文件吗？%n%n如果任何程序仍在使用此文件并且它被删除，这些程序可能无法正常运行。如果不确定，请选择"否"。将文件保留在系统上不会造成任何损害。
SharedFileNameLabel=文件名：
SharedFileLocationLabel=位置：
WizardUninstalling=卸载状态
StatusUninstalling=正在卸载 %1...

; *** 关机原因
ShutdownBlockReasonInstallingApp=正在安装 %1。
ShutdownBlockReasonUninstallingApp=正在卸载 %1。

[CustomMessages]
NameAndVersion=%1 版本 %2
AdditionalIcons=附加快捷方式：
CreateDesktopIcon=创建桌面快捷方式(&D)
CreateQuickLaunchIcon=创建快速启动快捷方式(&Q)
ProgramOnTheWeb=%1 网站
UninstallProgram=卸载 %1
LaunchProgram=启动 %1
AssocFileExtension=将 %1 与 %2 文件扩展名关联(&A)
AssocingFileExtension=正在将 %1 与 %2 文件扩展名关联...
AutoStartProgramGroupDescription=启动：
AutoStartProgram=自动启动 %1
AddonHostProgramNotFound=在您选择的文件夹中找不到 %1。%n%n要继续吗？
