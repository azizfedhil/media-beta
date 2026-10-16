; Copy this file to libmpv.pin.iss (same folder) and fill in the three values. pear.iss includes libmpv.pin.iss,
; and the installer does not build without it, so an unpinned libmpv can never ship.
;
; The values come from tools/inspect_binary.py run on the file you have checked:
;   LibmpvUrl     the download link (the /download form, not the /view page, which returns HTML)
;   LibmpvSha256  the SHA-256 of that exact file, lowercase or uppercase
;   LibmpvUrlMirror  a second copy (a GitHub Release asset with the same hash). The app's own download uses it when the
;                 Google Drive link fails; the installer does not yet.

#define LibmpvUrl "https://drive.usercontent.google.com/download?id=1zF1TbPHoSBVTRzjPVX7ktmwWb6rAUVM4&export=download&confirm=t"
#define LibmpvUrlMirror "TBD: https://github.com/<owner>/<repo>/releases/download/<tag>/<file>"
#define LibmpvSha256 "TBD: 64 hex characters from tools/inspect_binary.py"
