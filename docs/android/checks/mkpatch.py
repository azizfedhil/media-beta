"""Make a git-style unified patch (a/ b/ headers, /dev/null for new files) between two trees.
usage: mkpatch.py BEFORE_DIR AFTER_DIR OUT_PATCH"""
import difflib, os, sys

def files(root):
    out = {}
    for dp, dn, fn in os.walk(root):
        dn[:] = [d for d in dn if d != '.git']
        for f in fn:
            p = os.path.join(dp, f)
            out[os.path.relpath(p, root)] = p
    return out

before, after, out = sys.argv[1], sys.argv[2], sys.argv[3]
b, a = files(before), files(after)
chunks = []
for rel in sorted(set(b) | set(a)):
    if rel.startswith('docs/android/patches/'):
        continue
    old = open(b[rel], 'rb').read() if rel in b else None
    new = open(a[rel], 'rb').read() if rel in a else None
    if old == new:
        continue
    try:
        ol = old.decode('utf-8').splitlines(keepends=True) if old is not None else []
        nl = new.decode('utf-8').splitlines(keepends=True) if new is not None else []
    except UnicodeDecodeError:
        raise SystemExit(f"binary file differs: {rel}")
    fromf = '/dev/null' if old is None else f'a/{rel}'
    tof = '/dev/null' if new is None else f'b/{rel}'
    diff = list(difflib.unified_diff(ol, nl, fromfile=fromf, tofile=tof, n=3))
    if not diff:
        continue
    # keep "--- " and "+++ " lines exactly as git writes them (no timestamps)
    chunks.append(''.join(l if l.endswith('\n') else l + '\n' for l in diff))
open(out, 'w').write(''.join(chunks))
print(f"wrote {out}: {len(chunks)} file(s)")
