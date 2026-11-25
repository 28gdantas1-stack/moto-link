#!/usr/bin/env python3
"""Monta o APK do Moto Link: javac -> d8 (dex) -> aapt2 (recursos) -> zip alinhado -> apksigner.
Uso: python3 build.py [versionCode] [versionName]"""
import glob, os, shutil, struct, subprocess, sys, zipfile

A = os.path.dirname(os.path.abspath(__file__))
# Pasta com android-33.jar, aapt2_64, d8.jar e apksigner.jar (veja o README).
T = os.environ.get('MOTOLINK_FERRAMENTAS', os.path.join(A, 'ferramentas'))
if not os.path.isdir(T) and os.path.isdir('/home/claude/apktools/sdk'):
    T = '/home/claude/apktools/sdk'

ANDROID = os.path.join(T, 'android-33.jar')
AAPT2 = os.path.join(T, 'aapt2_64')
D8 = os.path.join(T, 'd8.jar')
SIGNER = os.path.join(T, 'apksigner.jar')
OUT = os.path.join(A, 'out')
VC, VN = (sys.argv[1], sys.argv[2]) if len(sys.argv) > 2 else ('1', '0.1')


def sh(*cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    err = '\n'.join(l for l in (r.stdout + r.stderr).splitlines() if 'JAVA_TOOL_OPTIONS' not in l)
    if r.returncode:
        print(err)
        sys.exit('FALHOU: ' + ' '.join(os.path.basename(c) for c in cmd[:3]))
    return err


shutil.rmtree(OUT, ignore_errors=True)
os.makedirs(os.path.join(OUT, 'classes'))

# 1) Java -> .class
fontes = glob.glob(os.path.join(A, 'src', '**', '*.java'), recursive=True)
print(sh('javac', '-nowarn', '-Xlint:none', '-parameters', '--release', '8', '-classpath', ANDROID,
         '-d', os.path.join(OUT, 'classes'), *fontes) or 'javac ok')

# 2) .class -> classes.dex
classes = glob.glob(os.path.join(OUT, 'classes', '**', '*.class'), recursive=True)
sh('java', '-cp', D8, 'com.android.tools.r8.D8', '--release', '--min-api', '26', '--lib', ANDROID,
   '--output', OUT, *classes)
print('d8 ok')

# 3) recursos + manifesto
sh(AAPT2, 'compile', '--dir', os.path.join(A, 'res'), '-o', os.path.join(OUT, 'res.zip'))
sh(AAPT2, 'link', '-I', ANDROID, '--manifest', os.path.join(A, 'AndroidManifest.xml'),
   '--min-sdk-version', '26', '--target-sdk-version', '33', '--version-code', VC, '--version-name', VN,
   '-o', os.path.join(OUT, 'base.apk'), os.path.join(OUT, 'res.zip'))
print('aapt2 ok')

# 4) junta e alinha (arquivos sem compressão alinhados em 4 bytes)
def montar(src, dex, dst):
    itens = []
    with zipfile.ZipFile(src) as z:
        for i in z.infolist():
            itens.append((i.filename, z.read(i.filename)))
    itens.append(('classes.dex', open(dex, 'rb').read()))
    ordem = {'AndroidManifest.xml': 0, 'classes.dex': 1, 'resources.arsc': 2}
    itens.sort(key=lambda e: (ordem.get(e[0], 9), e[0]))
    with open(dst, 'wb') as f, zipfile.ZipFile(f, 'w') as out:
        for nome, dados in itens:
            guardado = nome == 'resources.arsc' or nome.endswith('.png')
            zi = zipfile.ZipInfo(nome, date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_STORED if guardado else zipfile.ZIP_DEFLATED
            zi.external_attr = 0o644 << 16
            if guardado:
                off = f.tell() + 30 + len(nome.encode()) + 6
                pad = (-off) % 4
                zi.extra = struct.pack('<HHH', 0xD935, 2 + pad, 4) + b'\0' * pad
            out.writestr(zi, dados)


montar(os.path.join(OUT, 'base.apk'), os.path.join(OUT, 'classes.dex'), os.path.join(OUT, 'unsigned.apk'))

# 5) assinatura com chave própria (guarde a pasta assinatura/: atualizações precisam dela)
K = os.path.join(A, 'assinatura')
os.makedirs(K, exist_ok=True)
pem, crt, pk8 = (os.path.join(K, n) for n in ('chave.pem', 'cert.pem', 'chave.pk8'))
if not os.path.exists(pk8):
    sh('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', pem, '-out', crt,
       '-days', '10000', '-subj', '/CN=Moto Link/O=Gabriel/C=BR')
    sh('openssl', 'pkcs8', '-topk8', '-inform', 'PEM', '-outform', 'DER', '-in', pem, '-out', pk8, '-nocrypt')
final = os.path.join(OUT, 'MotoLink.apk')
sh('java', '-jar', SIGNER, 'sign', '--key', pk8, '--cert', crt, '--min-sdk-version', '26',
   '--out', final, os.path.join(OUT, 'unsigned.apk'))
print(sh('java', '-jar', SIGNER, 'verify', '--verbose', final).splitlines()[0:4])
print('APK:', final, os.path.getsize(final), 'bytes')
