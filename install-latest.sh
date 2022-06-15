#!/bin/sh

set -o errexit
set -o nounset
#set -o xtrace

OS="$(uname)"
if [ "${OS}" = "Linux" ]; then
  	platform=linux
elif [ "${OS}" = "Darwin" ]; then
  	platform=macos
else
	platform=windows
fi

wget --quiet \
    --output-document="${HOME}/bin/wait-for-ports" \
    "https://github.com/ArloL/wait-for-ports/releases/latest/download/wait-for-ports-${platform}"

chmod +x "${HOME}/bin/wait-for-ports"
