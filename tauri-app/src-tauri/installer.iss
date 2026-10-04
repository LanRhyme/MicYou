#define MyAppName "MicYou"
#ifndef MyAppVersion
  #define MyAppVersion "2.1.0"
#endif
#define MyAppPublisher "LanRhyme"
#define MyAppURL "https://github.com/MicYou-Dev/MicYou"
#define MyAppExeName "micyou.exe"
#define MyCliExeName "micyou-cli.exe"
#define MyTuiExeName "micyou-tui.exe"

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

[CustomMessages]
chinesesimplified.NetworkGroupDescription=网络设置
chinesesimplified.FirewallTaskDescription=放行 Windows 防火墙（推荐，允许 MicYou 通过防火墙接收来自 Android 设备的音频连接；"仅当前用户"安装时将在安装收尾请求一次管理员授权）
english.NetworkGroupDescription=Network settings
english.FirewallTaskDescription=Add Windows Firewall rules (recommended; allow MicYou to receive audio connections from Android devices through the firewall. For "current user only" installs, administrator approval is requested once at the end of Setup)
chinesesimplified.FirewallAddFailed=未能添加 Windows 防火墙规则（管理员授权被取消或执行失败）。MicYou 首次监听连接时 Windows 可能弹出防火墙授权提示，届时选择允许即可；也可重新运行安装程序，或在"高级安全 Windows Defender 防火墙"中手动放行 micyou.exe、micyou-cli.exe、micyou-tui.exe。
english.FirewallAddFailed=Failed to add the Windows Firewall rules (the elevation request was cancelled or netsh failed). Windows may prompt again the first time MicYou listens for connections; allow it there, re-run Setup, or add inbound rules for micyou.exe, micyou-cli.exe and micyou-tui.exe manually in Windows Defender Firewall with Advanced Security.
chinesesimplified.FirewallRemoveFailed=未能移除 Windows 防火墙规则（管理员授权被取消或执行失败）。可在"高级安全 Windows Defender 防火墙"中手动删除名为 MicYou、MicYou CLI、MicYou TUI 的入站规则。
english.FirewallRemoveFailed=Failed to remove the Windows Firewall rules (the elevation request was cancelled or netsh failed). You can manually delete the inbound rules named MicYou, MicYou CLI and MicYou TUI in Windows Defender Firewall with Advanced Security.

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
; Checked by default, like the firewall component in the qBittorrent installer.
; Offered in both install modes: in "current user only" (non-admin) installs the
; rules are applied through a one-time UAC elevation at the end of Setup (see
; [Code]), so the option must never be hidden there.
Name: "firewall"; Description: "{cm:FirewallTaskDescription}"; GroupDescription: "{cm:NetworkGroupDescription}"

[Files]
Source: "..\target\release\micyou.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "binaries\micyou-cli-x86_64-pc-windows-msvc.exe"; DestDir: "{app}"; DestName: "{#MyCliExeName}"; Flags: ignoreversion
Source: "binaries\micyou-tui-x86_64-pc-windows-msvc.exe"; DestDir: "{app}"; DestName: "{#MyTuiExeName}"; Flags: ignoreversion
Source: "resources\*"; DestDir: "{app}\resources"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "libs\onnxruntime.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "libs\onnxruntime.dll"; DestDir: "{app}\resources"; Flags: ignoreversion

[Icons]
; Each shortcut gets its own AppUserModelID. Sharing one AUMID across GUI/CLI/TUI
; made Windows collapse the three shortcuts into a single Start-menu search
; result / taskbar identity ("overlapping" shortcuts).
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; AppUserModelID: "com.lanrhyme.micyou"
Name: "{autoprograms}\{#MyAppName} TUI"; Filename: "{app}\{#MyTuiExeName}"; IconFilename: "{app}\{#MyAppExeName}"; AppUserModelID: "com.lanrhyme.micyou.tui"
Name: "{autoprograms}\{#MyAppName} CLI"; Filename: "{app}\{#MyCliExeName}"; IconFilename: "{app}\{#MyAppExeName}"; AppUserModelID: "com.lanrhyme.micyou.cli"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon; AppUserModelID: "com.lanrhyme.micyou"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent

[Code]
// Windows Firewall handling, modeled after the qBittorrent installer
// (nsisFirewallW::AddAuthorizedApplication on install /
//  RemoveAuthorizedApplication on uninstall):
//   - the "firewall" task (checked by default) adds inbound allow rules for
//     the GUI, CLI and TUI executables; all three frontends can host the same
//     server that Android devices connect to.
//   - admin ("all users") installs: Setup is already elevated, netsh runs
//     directly.
//   - non-admin ("current user only") installs: the task is still offered;
//     when selected, one single UAC elevation applies all netsh commands via
//     a generated helper script. Silent installs skip the elevation attempt
//     so an unattended run can never stall on an invisible consent prompt.
//   - uninstall removes the rules (elevating through the helper script when
//     needed). A marker file in {app} records that a non-admin install really
//     created rules, so uninstall never prompts UAC without reason.
//   - re-installs/upgrades delete the rules by name before adding them again,
//     so duplicates never accumulate.
// netsh exit codes are ignored where failure is expected/harmless (deleting a
// non-existent rule returns 1).

const
  FirewallMarkerName = '.firewall-rules';

var
  HadFirewallRules: Boolean;

function FirewallMarkerPath: String;
begin
  Result := ExpandConstant('{app}\' + FirewallMarkerName);
end;

procedure RunNetshDirect(const Arguments: String);
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\netsh.exe'), Arguments, '', SW_HIDE,
    ewWaitUntilTerminated, ResultCode);
end;

procedure RemoveFirewallRulesDirect;
begin
  RunNetshDirect('advfirewall firewall delete rule name="{#MyAppName}"');
  RunNetshDirect('advfirewall firewall delete rule name="{#MyAppName} CLI"');
  RunNetshDirect('advfirewall firewall delete rule name="{#MyAppName} TUI"');
end;

procedure AddFirewallRulesDirect;
var
  AppDir: String;
begin
  AppDir := ExpandConstant('{app}');
  // Delete first so re-installs/upgrades never leave duplicate rules behind.
  RemoveFirewallRulesDirect;
  RunNetshDirect('advfirewall firewall add rule name="{#MyAppName}" dir=in action=allow enable=yes profile=any program="' + AppDir + '\{#MyAppExeName}"');
  RunNetshDirect('advfirewall firewall add rule name="{#MyAppName} CLI" dir=in action=allow enable=yes profile=any program="' + AppDir + '\{#MyCliExeName}"');
  RunNetshDirect('advfirewall firewall add rule name="{#MyAppName} TUI" dir=in action=allow enable=yes profile=any program="' + AppDir + '\{#MyTuiExeName}"');
end;

// Writes an ASCII-only helper batch script into {tmp} and returns its path
// ('' on failure). The install directory is passed to the script as quoted
// argument %1 instead of being embedded in the file, so paths containing
// non-ASCII characters can never be mangled by codepage conversions.
function WriteFirewallHelperScript(const Mode: String): String;
var
  Lines: TArrayOfString;
  Path: String;
begin
  Result := '';
  if Mode = 'add' then
  begin
    SetArrayLength(Lines, 9);
    Lines[0] := '@echo off';
    Lines[1] := 'set RC=0';
    Lines[2] := 'netsh advfirewall firewall delete rule name="{#MyAppName}" >nul 2>&1';
    Lines[3] := 'netsh advfirewall firewall delete rule name="{#MyAppName} CLI" >nul 2>&1';
    Lines[4] := 'netsh advfirewall firewall delete rule name="{#MyAppName} TUI" >nul 2>&1';
    Lines[5] := 'netsh advfirewall firewall add rule name="{#MyAppName}" dir=in action=allow enable=yes profile=any program="%~1\{#MyAppExeName}" >nul 2>&1 || set RC=1';
    Lines[6] := 'netsh advfirewall firewall add rule name="{#MyAppName} CLI" dir=in action=allow enable=yes profile=any program="%~1\{#MyCliExeName}" >nul 2>&1 || set RC=1';
    Lines[7] := 'netsh advfirewall firewall add rule name="{#MyAppName} TUI" dir=in action=allow enable=yes profile=any program="%~1\{#MyTuiExeName}" >nul 2>&1 || set RC=1';
    Lines[8] := 'exit /b %RC%';
  end
  else
  begin
    SetArrayLength(Lines, 5);
    Lines[0] := '@echo off';
    Lines[1] := 'netsh advfirewall firewall delete rule name="{#MyAppName}" >nul 2>&1';
    Lines[2] := 'netsh advfirewall firewall delete rule name="{#MyAppName} CLI" >nul 2>&1';
    Lines[3] := 'netsh advfirewall firewall delete rule name="{#MyAppName} TUI" >nul 2>&1';
    Lines[4] := 'exit /b 0';
  end;
  Path := ExpandConstant('{tmp}\micyou-firewall.cmd');
  if SaveStringsToFile(Path, Lines, False) then
    Result := Path;
end;

// Runs the helper script through a single UAC elevation (ShellExecute 'runas').
// Returns True only when the user approved and every netsh add succeeded
// (for Mode = 'add'; 'del' always reports success once it ran).
function RunFirewallHelperElevated(const Mode: String): Boolean;
var
  ScriptPath: String;
  ResultCode: Integer;
begin
  Result := False;
  ScriptPath := WriteFirewallHelperScript(Mode);
  if ScriptPath = '' then
    Exit;
  if Mode = 'add' then
  begin
    if ShellExec('runas', ScriptPath, '"' + ExpandConstant('{app}') + '"', '',
       SW_HIDE, ewWaitUntilTerminated, ResultCode) then
      Result := (ResultCode = 0);
  end
  else
  begin
    if ShellExec('runas', ScriptPath, '', '', SW_HIDE,
       ewWaitUntilTerminated, ResultCode) then
      Result := True;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if (CurStep = ssPostInstall) and WizardIsTaskSelected('firewall') then
  begin
    if IsAdminInstallMode then
    begin
      AddFirewallRulesDirect;
      SaveStringToFile(FirewallMarkerPath, '1', False);
    end
    else if not WizardSilent then
    begin
      if RunFirewallHelperElevated('add') then
        SaveStringToFile(FirewallMarkerPath, '1', False)
      else
        MsgBox(ExpandConstant('{cm:FirewallAddFailed}'), mbInformation, MB_OK);
    end;
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
  begin
    // Read and remove the marker BEFORE the file-removal pass: it is not a
    // recorded file, and any leftover inside {app} would keep the directory
    // from being deleted at the end of the uninstall.
    HadFirewallRules := FileExists(FirewallMarkerPath);
    DeleteFile(FirewallMarkerPath);
  end
  else if CurUninstallStep = usPostUninstall then
  begin
    if IsAdminInstallMode then
    begin
      // Unconditional: also cleans up rules left behind by older builds.
      RemoveFirewallRulesDirect;
    end
    else if HadFirewallRules then
    begin
      if not RunFirewallHelperElevated('del') then
        MsgBox(ExpandConstant('{cm:FirewallRemoveFailed}'), mbInformation, MB_OK);
    end;
  end;
end;
