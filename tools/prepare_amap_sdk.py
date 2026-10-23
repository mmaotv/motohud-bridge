#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
准备高德 SDK（地图 / 搜索 / 定位）。

高德官方只把类 jar 发布到 Maven，so 与 assets 都塞在 jar 内部，
Android Gradle Plugin 不会自动打包它们，因此需要：

  1. 从 Maven 镜像下载三个 jar；
  2. 合并去重为单 jar（三者存在重复类，直接并存会被 D8 判为 duplicate class）；
  3. 把 jar 内的 lib/<abi>/*.so 解到 app/src/main/jniLibs；
     assets/** 解到 app/src/main/assets。

用法：
    python tools/prepare_amap_sdk.py
    python tools/prepare_amap_sdk.py --mirror https://maven.aliyun.com/repository/public

仅使用 Python 标准库。SDK 版权归高德（AutoNavi）所有，本脚本不重新分发 SDK。
"""
import argparse
import io
import os
import sys
import urllib.request
import zipfile

ARTIFACTS = [
    ("3dmap", "10.0.600"),
    ("search", "9.7.1"),
    ("location", "11.3.101"),
]

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app")
LIBS = os.path.join(APP, "libs")
JNI = os.path.join(APP, "src", "main", "jniLibs")
ASSETS = os.path.join(APP, "src", "main", "assets")
MERGED = os.path.join(LIBS, "amap-sdk-merged.jar")


def download(mirror, artifact, version):
    url = "%s/com/amap/api/%s/%s/%s-%s.jar" % (mirror.rstrip("/"), artifact, version, artifact, version)
    sys.stdout.write("下载 %s ... " % url)
    sys.stdout.flush()
    with urllib.request.urlopen(url, timeout=180) as r:
        data = r.read()
    print("%.1f MB" % (len(data) / 1024.0 / 1024.0))
    return data


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--mirror",
        default="https://maven.aliyun.com/repository/public",
        help="Maven 仓库地址（默认阿里云镜像）",
    )
    args = ap.parse_args()

    os.makedirs(LIBS, exist_ok=True)
    blobs = []
    for artifact, version in ARTIFACTS:
        blobs.append(download(args.mirror, artifact, version))

    # 合并去重（同名类以先出现的为准）
    classes = {}
    natives = {}
    assets = {}
    for data in blobs:
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for name in z.namelist():
                if name.endswith("/"):
                    continue
                if name.startswith("lib/") and name.endswith(".so"):
                    natives.setdefault(name, z.read(name))
                elif name.startswith("assets/"):
                    assets.setdefault(name, z.read(name))
                elif name.endswith(".class") or name.startswith("META-INF/"):
                    classes.setdefault(name, z.read(name))

    with zipfile.ZipFile(MERGED, "w", zipfile.ZIP_DEFLATED) as out:
        for name, data in classes.items():
            out.writestr(name, data)
    print("合并 jar：%s（%d 个类）" % (MERGED, len(classes)))

    for name, data in natives.items():
        dst = os.path.join(JNI, name.replace("lib/", "", 1).replace("/", os.sep))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with open(dst, "wb") as f:
            f.write(data)
        print("so  ->", os.path.relpath(dst, ROOT))

    for name, data in assets.items():
        dst = os.path.join(ASSETS, name.replace("assets/", "", 1).replace("/", os.sep))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with open(dst, "wb") as f:
            f.write(data)
    print("assets -> %s（%d 个文件）" % (os.path.relpath(ASSETS, ROOT), len(assets)))
    print("完成，可直接执行 ./gradlew assembleDebug")


if __name__ == "__main__":
    main()
