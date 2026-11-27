"""Pinned official libxposed artifacts; API is compile-only, service is packaged."""
from pathlib import Path
import hashlib
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
DEPENDENCIES = {
    'api': '423484a6e1807e7a423c4b88fcd8176d104318259d91791877fed88fe91479d0',
    'service': 'a665e4af4638924047f5d5cb610553e15bc2c0ea2ce64a27afb6dee257ff014c',
    'interface': '4e0b76d6050be1d3dc0d35525180ad7d9b60a47fd8d8fe1d18c2bdc840be9ddb',
}

def prepare():
    folder = ROOT / 'build/dependencies'
    folder.mkdir(parents=True, exist_ok=True)
    jars = {}
    for name, digest in DEPENDENCIES.items():
        aar = folder / f'libxposed-{name}-102.0.0.aar'
        if not aar.exists():
            local = ROOT / 'build' / aar.name
            if local.exists() and hashlib.sha256(local.read_bytes()).hexdigest() == digest:
                aar.write_bytes(local.read_bytes())
            else:
                pending = aar.with_suffix('.download')
                subprocess.run(['curl.exe', '--fail', '--location', '--proto', '=https', '--max-time', '120',
                                f'https://repo.maven.apache.org/maven2/io/github/libxposed/{name}/102.0.0/{name}-102.0.0.aar',
                                '--output', str(pending)], check=True)
                if hashlib.sha256(pending.read_bytes()).hexdigest() != digest:
                    raise ValueError(f'libxposed {name} checksum mismatch')
                pending.replace(aar)
        if hashlib.sha256(aar.read_bytes()).hexdigest() != digest:
            raise ValueError(f'libxposed {name} checksum mismatch')
        jars[name] = aar.with_suffix('.jar')
        with zipfile.ZipFile(aar) as archive:
            jars[name].write_bytes(archive.read('classes.jar'))
    return jars

if __name__ == '__main__':
    for name, jar in prepare().items():
        print(f'{name}: {jar}')
