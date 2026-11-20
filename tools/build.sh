#!/bin/sh
set -e
export JAVA_HOME=/workspace/tmp/toolchain/jdk-17.0.21+9
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/workspace/tmp/toolchain/sdk
export ANDROID_SDK_ROOT=/workspace/tmp/toolchain/sdk
export GRADLE_USER_HOME=/workspace/tmp/gradle-home
cd /workspace/work/pocketcore
/workspace/tmp/toolchain/gradle-8.9/bin/gradle "$@" --no-daemon --console=plain \
  -Dorg.gradle.jvmargs="-Xmx1500m -XX:MaxMetaspaceSize=512m" \
  -Dkotlin.compiler.execution.strategy=in-process
