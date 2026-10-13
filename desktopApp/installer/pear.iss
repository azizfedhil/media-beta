; Pear's Windows installer, built by CI (.github/workflows/desktop-ci.yml) from the app image that
; ":desktopApp:createDistributable" makes:
;
;   ISCC.exe /DPearVersion=0.0.1-Alpha /DPearNumericVersion=0.0.1.0 desktopApp\installer\pear.iss
;
; Per-user: it needs no administrator rights and shows no UAC prompt. The app goes to %LocalAppData%\Pear.
;
; STATUS: not compiled yet. Written against Inno Setup 6.2+ and the Inno Download Plugin (IDP), whose include and
; API must be checked against the version we pin before the first build. See docs/desktop/packaging.md.

#ifndef PearVersion
  #define PearVersion "0.0.1-Alpha"
#endif
#ifndef PearNumericVersion
  #define PearNumericVersion "0.0.1.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\build\compose\binaries\main\app\Pear"
#endif

; The pinned libmpv (URL and SHA-256), kept in its own file so the hash is one visible place to change.
#if FileExists(AddBackslash(SourcePath) + "libmpv.pin.iss")
  #include "libmpv.pin.iss"
#endif
#ifndef LibmpvSha256
  #error "libmpv is not pinned. Copy libmpv.pin.example.iss to libmpv.pin.iss, fill it in, and build again."
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
; libmpv and the caches are not user data: they are rebuilt or downloaded again. Settings are asked about in [Code].
Type: filesandordirs; Name: "{localappdata}\Pear\native"
Type: filesandordirs; Name: "{localappdata}\Pear\Cache"

[Code]
// The Inno Download Plugin (https://github.com/PeterDaveHello/inno-download-plugin, zlib licence). Vendor the version we
// pin next to this script, and check its include path and API when first compiling.
#include "idp.iss"

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

function DownloadedPath(): String;
begin
  Result := ExpandConstant('{tmp}\libmpv-download.bin');
end;

// Already there and correct (a reinstall or an update): no download needed.
function LibmpvIsCurrent(): Boolean;
begin
  Result := FileExists(LibmpvPath()) and
    (CompareText(GetSHA256OfFile(LibmpvPath()), '{#LibmpvSha256}') = 0);
end;

procedure InitializeWizard();
begin
  if not LibmpvIsCurrent() then
  begin
    // The download runs on its own page, after the task choices. One URL: a failed download gets IDP's own Retry dialog.
    idpAddFile('{#LibmpvUrl}', DownloadedPath());
    idpDownloadAfter(wpSelectTasks);
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if (CurStep = ssPostInstall) and not LibmpvIsCurrent() then
  begin
    if CompareText(GetSHA256OfFile(DownloadedPath()), '{#LibmpvSha256}') <> 0 then
      RaiseException('The downloaded libmpv does not match the pinned SHA-256, so it was not installed. ' +
        'Run the installer again. If it keeps failing, report it.');
    ForceDirectories(NativeDir());
    if not FileCopy(DownloadedPath(), LibmpvPath(), False) then
      RaiseException('Could not copy libmpv to ' + LibmpvPath() + '.');
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
