#!/bin/bash
set -e

# Fetching sources and running host regression tests do not require an Android NDK.
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
PATCH_ROOT="$PWD/buildScript/lib/core/patches"
apply_local_patch() {
  if git apply --reverse --check "$1" >/dev/null 2>&1; then
    return
  fi
  git apply --check "$1"
  git apply "$1"
}
pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout https://github.com/alaaabd90/sing-box-vload.git sing-box
fi
pushd sing-box
git checkout "$COMMIT_SING_BOX"
apply_local_patch "$PATCH_ROOT/sing-box-tls-nodelay.patch"
popd

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/alaaabd90/libneko-vload.git libneko
fi
pushd libneko
git checkout "$COMMIT_LIBNEKO"
popd

####

if [ ! -d "sing-mux" ]; then
  git clone --no-checkout https://github.com/alaaabd90/sing-mux-vload.git sing-mux
fi
pushd sing-mux
git checkout "$COMMIT_SING_MUX"
apply_local_patch "$PATCH_ROOT/sing-mux-pool.patch"
popd

####

if [ ! -d "sing-runtime" ]; then
  git clone --no-checkout https://github.com/sagernet/sing.git sing-runtime
fi
pushd sing-runtime
git checkout "$COMMIT_SING_RUNTIME"
apply_local_patch "$PATCH_ROOT/sing-udp-peer.patch"
popd

####

popd
