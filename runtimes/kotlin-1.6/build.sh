#!/bin/sh

set -e

mkdir -p /usr/local/src/src/main/kotlin/io/openruntimes/kotlin
cp -a /usr/code/. /usr/local/src/src/main/kotlin/io/openruntimes/kotlin

cd /usr/local/src/src/main/kotlin/io/openruntimes/kotlin

# Apply all gradle files to the root project
for filename in ./*.gradle*; do
    if [ ! -f "${filename}" ]; then
        continue;
    fi
    mv "${filename}" "/usr/local/src/${filename}"
    echo "apply from: \"${filename}\"" >> /usr/local/src/build.gradle
done

# Build the jar
cd /usr/local/src
sh gradlew buildJar

# Tar the jar
cd /usr/local/src/build/libs/
tar -zcvf /usr/code/code.tar.gz .