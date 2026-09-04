#define MyAppName "MicYou"
#ifndef MyAppVersion
  #define MyAppVersion "2.0.0"
#endif
#define MyAppPublisher "LanRhyme"
#define MyAppURL "https://github.com/LanRhyme/MicYou"
#define MyAppExeName "micyou.exe"

[Setup]
AppId={{C8E6D8A6-3A1B-4E38-B76B-C9DB2A0058C0}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={autopf}\{#MyAppName}
DisableDirPage=no
DisableProgramGroupPage=yes
UsePreviousAppDir=yes
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=dialog
OutputBaseFilename={#MyAppName}_{#MyAppVersion}_x64-setup
OutputDir=target\release\bundle\inno
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
SetupIconFile=icons\icon.ico
UninstallDisplayIcon={app}\{#MyAppExeName}

[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Default.isl,SimpChinese.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "..\target\release\micyou.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "binaries\micyou-cli-x86_64-pc-windows-msvc.exe"; DestDir: "{app}"; DestName: "micyou-cli.exe"; Flags: ignoreversion
Source: "binaries\micyou-tui-x86_64-pc-windows-msvc.exe"; DestDir: "{app}"; DestName: "micyou-tui.exe"; Flags: ignoreversion
Source: "resources\*"; DestDir: "{app}\resources"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "libs\onnxruntime.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "libs\onnxruntime.dll"; DestDir: "{app}\resources"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; AppUserModelID: "com.lanrhyme.micyou"
Name: "{autoprograms}\{#MyAppName} TUI"; Filename: "{app}\micyou-tui.exe"; IconFilename: "{app}\{#MyAppExeName}"; AppUserModelID: "com.lanrhyme.micyou"
Name: "{autoprograms}\{#MyAppName} CLI"; Filename: "{app}\micyou-cli.exe"; IconFilename: "{app}\{#MyAppExeName}"; AppUserModelID: "com.lanrhyme.micyou"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon; AppUserModelID: "com.lanrhyme.micyou"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent
