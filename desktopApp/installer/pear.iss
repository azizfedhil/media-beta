; Pear's Windows installer, built by CI (.github/workflows/desktop-ci.yml) from the app image that
; ":desktopApp:createDistributable" makes. CI passes the version and the libmpv values the build recorded:
;
;   ISCC.exe /DPearVersion=0.0.3-Alpha /DPearNumericVersion=0.0.3.0 /DLibmpvUrl=<link> /DLibmpvMirrorUrl=<link or empty>
;            /DLibmpvSha256=<hash> desktopApp\installer\pear.iss
;
; Per-user: it needs no administrator rights and shows no UAC prompt. The app goes to %LocalAppData%\Pear.
;
; STATUS: not compiled yet. It uses only Inno Setup 6.2+ and Windows PowerShell (part of Windows 10 and 11), so there is
; no plugin to vendor.

#ifndef PearVersion
  #define PearVersion "0.0.3-Alpha"
#endif
#ifndef PearNumericVersion
  #define PearNumericVersion "0.0.3.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\build\compose\binaries\main\app\Pear"
#endif
#ifndef LibmpvUrl
  #error "LibmpvUrl is not set. CI passes it from gradle.properties (libmpvUrl)."
#endif
#ifndef LibmpvSha256
  #error "LibmpvSha256 is not set. The build writes it to shared/build/libmpv/sha256.txt; pass /DLibmpvSha256=<hash>."
#endif
#ifndef LibmpvMirrorUrl
  #define LibmpvMirrorUrl
#endif

[Setup]
; AppId identifies the product to Windows across versions. Never change it.
AppId={{AB493C30-56E0-4853-8E73-509280B18B77}
AppName=Pear.
AppVersion={#PearVersion}
AppPublisher=MediaHub
DefaultDirName={localappdata}\Pear
DefaultGroupName=Pear.
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes
CloseApplicationsFilter=*.exe,*.dll
RestartApplications=yes
UninstallDisplayName=Pear.
UninstallDisplayIcon={app}\Pear.exe
SetupIconFile=..\packaging\icons\pear.ico
VersionInfoVersion={#PearNumericVersion}
OutputDir=..\build\installer
OutputBaseFilename=PearSetup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\Pear."; Filename: "{app}\Pear.exe"; WorkingDir: "{app}"
Name: "{userdesktop}\Pear."; Filename: "{app}\Pear.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\Pear.exe"; Description: "Open Pear."; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; libmpv and the caches are not user data: they are downloaded or rebuilt. Settings are asked about in [Code].
Type: filesandordirs; Name: "{localappdata}\Pear\native"
Type: filesandordirs; Name: "{localappdata}\Pear\Cache"

[Code]
const
  LibmpvFileName = 'libmpv-2.dll';

function NativeDir(): String;
begin
  Result := ExpandConstant('{localappdata}\Pear\native');
end;

function LibmpvPath(): String;
begin
  Result := NativeDir() + '\' + LibmpvFileName;
end;

// True only when the file is there and its hash matches the one this build was made with.
function LibmpvIsCurrent(): Boolean;
begin
  Result := False;
  if FileExists(LibmpvPath()) then
    Result := CompareText(GetSHA256OfFile(LibmpvPath()), '{#LibmpvSha256}') = 0;
end;

// The download runs in Windows PowerShell, which Windows 10 and 11 include, so the installer needs no plugin.
// It tries the Google Drive link, then the mirror, and moves a file into place only after its hash matches.
// Exit codes: 0 installed, 1 download failed, 2 hash mismatch, 3 not a DLL (often a Google Drive web page).
function FetchScript(): String;
begin
  Result :=
    'param([string]$Url, [string]$Mirror, [string]$Target, [string]$Sha)' + #13#10 +
    '$ErrorActionPreference = "Stop"' + #13#10 +
    '$ProgressPreference = "SilentlyContinue"' + #13#10 +
    '[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12' + #13#10 +
    '$code = 1' + #13#10 +
    'foreach ($u in @($Url, $Mirror)) {' + #13#10 +
    '  if (-not $u) { continue }' + #13#10 +
    '  $part = $Target + ".download"' + #13#10 +
    '  try {' + #13#10 +
    '    Invoke-WebRequest -Uri $u -OutFile $part -UseBasicParsing' + #13#10 +
    '    $fs = [IO.File]::OpenRead($part)' + #13#10 +
    '    $b0 = $fs.ReadByte()' + #13#10 +
    '    $b1 = $fs.ReadByte()' + #13#10 +
    '    $fs.Close()' + #13#10 +
    '    if ($b0 -ne 77 -or $b1 -ne 90) { $code = 3 } elseif ((Get-FileHash -Algorithm SHA256 -Path $part).Hash.ToLower() -ne $Sha.ToLower()) { $code = 2 } else { Move-Item -Force -Path $part -Destination $Target; $code = 0 }' + #13#10 +
    '  } catch { $code = 1 }' + #13#10 +
    '  finally { if (Test-Path $part) { Remove-Item -Force $part } }' + #13#10 +
    '  if ($code -eq 0) { break }' + #13#10 +
    '}' + #13#10 +
    'exit $code' + #13#10;
end;

function RunFetch(): Boolean;
var
  ScriptPath, Params: String;
  ResultCode: Integer;
begin
  Result := False;
  // The script writes into this folder and does not create it. Nothing else in the installer does either, so without
  // this the download fails every time, with the same error on Retry.
  if not ForceDirectories(NativeDir()) then
    Exit;
  ScriptPath := ExpandConstant('{tmp}\fetch-libmpv.ps1');
  if not SaveStringToFile(ScriptPath, FetchScript(), False) then
    Exit;
  Params := '-NoProfile -ExecutionPolicy Bypass -File "' + ScriptPath + '"' +
    ' -Url "{#LibmpvUrl}" -Mirror "{#LibmpvMirrorUrl}" -Target "' + LibmpvPath() + '" -Sha "{#LibmpvSha256}"';
  ResultCode := -1;
  Exec('powershell.exe', Params, '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  DeleteFile(ScriptPath);
  Result := ResultCode = 0;
end;

function FetchLibmpvWithRetry(): Boolean;
var
  Done: Boolean;
begin
  Done := False;
  Result := False;
  while not Done do
  begin
    Result := RunFetch();
    if Result then
      Done := True
    else
      Done := MsgBox('Pear could not download libmpv, its video engine.' + #13#10 + #13#10 +
        'Check your internet connection, then choose Retry. Cancel stops the install.',
        mbError, MB_RETRYCANCEL) <> IDRETRY;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  // After the files are in place and before the Open Pear step, so the app finds libmpv the first time it starts.
  if (CurStep = ssPostInstall) and not LibmpvIsCurrent() then
  begin
    WizardForm.StatusLabel.Caption := 'Downloading libmpv, the video engine...';
    if not FetchLibmpvWithRetry() then
      RaiseException('libmpv was not installed, so Pear cannot play video yet. Run the installer again.');
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
  begin
    // Settings and saved sign-ins are kept unless the user says otherwise. The default answer is No.
    if MsgBox('Also delete your Pear settings and saved sign-ins?' + #13#10 + #13#10 +
        'Choose No to keep them, so a reinstall picks them up again.',
        mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
      DelTree(ExpandConstant('{userappdata}\Pear'), True, True, True);
  end;
end;
