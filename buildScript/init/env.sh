#!/bin/bash

source buildScript/init/env_ndk.sh

if [[ "$OSTYPE" =~ ^darwin ]]; then
  export SRC_ROOT=$PWD
else
  export SRC_ROOT=$(realpath .)
fi

if [[ "$OSTYPE" =~ ^(msys|cygwin|win32) ]]; then
  HOST_TAG=windows-x86_64
  CC_EXT=.cmd
  EXE_EXT=.exe
else
  HOST_TAG=linux-x86_64
  CC_EXT=
  EXE_EXT=
fi

DEPS=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin

# NDK r23+ ships a single unified llvm-strip instead of per-triple *-strip
# binaries; on Windows only the .exe-suffixed form exists.
STRIP=$DEPS/llvm-strip$EXE_EXT

export ANDROID_ARM_CC=$DEPS/armv7a-linux-androideabi21-clang$CC_EXT
export ANDROID_ARM_CXX=$DEPS/armv7a-linux-androideabi21-clang++$CC_EXT
export ANDROID_ARM_CC_21=$DEPS/armv7a-linux-androideabi21-clang$CC_EXT
export ANDROID_ARM_CXX_21=$DEPS/armv7a-linux-androideabi21-clang++$CC_EXT
export ANDROID_ARM_STRIP=$STRIP

export ANDROID_ARM64_CC=$DEPS/aarch64-linux-android21-clang$CC_EXT
export ANDROID_ARM64_CXX=$DEPS/aarch64-linux-android21-clang++$CC_EXT
export ANDROID_ARM64_STRIP=$STRIP

export ANDROID_X86_CC=$DEPS/i686-linux-android21-clang$CC_EXT
export ANDROID_X86_CXX=$DEPS/i686-linux-android21-clang++$CC_EXT
export ANDROID_X86_CC_21=$DEPS/i686-linux-android21-clang$CC_EXT
export ANDROID_X86_CXX_21=$DEPS/i686-linux-android21-clang++$CC_EXT
export ANDROID_X86_STRIP=$STRIP

export ANDROID_X86_64_CC=$DEPS/x86_64-linux-android21-clang$CC_EXT
export ANDROID_X86_64_CXX=$DEPS/x86_64-linux-android21-clang++$CC_EXT
export ANDROID_X86_64_STRIP=$STRIP
