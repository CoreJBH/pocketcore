#!/bin/sh
set -e
export JAVA_HOME=/workspace/tmp/toolchain/jdk-17.0.21+9
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/workspace/tmp/toolchain/sdk
export ANDROID_SDK_ROOT=/workspace/tmp/toolchain/sdk
export GRADLE_USER_HOME=/workspace/tmp/gradle-home
# This container has no passwd entry for the build user, so the JVM reports
# user.home as "?" and the debug keystore would be looked up in a nonsense
# directory. Pin it to the directory that holds ~/.android/debug.keystore.
cd /workspace/work/pocketcore
/workspace/tmp/toolchain/gradle-8.9/bin/gradle "$@" --no-daemon --console=plain \
  -Duser.home=/workspace \
  -Dorg.gradle.jvmargs="-Xmx1500m -XX:MaxMetaspaceSize=512m -Duser.home=/workspace" \
  -Dkotlin.compiler.execution.strategy=in-process
