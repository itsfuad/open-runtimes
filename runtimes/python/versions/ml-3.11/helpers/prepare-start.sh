#!/bin/sh

# Copy contents of the server-env virtual env to the runtime-env virtual env
cp -R /usr/local/server/server-env/* /usr/local/server/src/function/runtime-env/

# Activate virtual env
. /usr/local/server/src/function/runtime-env/bin/activate
export VIRTUAL_ENV="/usr/local/server/src/function/runtime-env"
export PATH="$VIRTUAL_ENV/bin:$PATH"